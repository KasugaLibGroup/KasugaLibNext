package lib.kasuga.create.proxy;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import lib.kasuga.rendering.models.mc.proxies.ModelPart;
import lib.kasuga.rendering.models.mc.proxies.ProxyConfig;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Id-based lookup and pose translation for the train-side bogey path.
 *
 * <p>A bogey on a train only knows its style as an NBT id, so {@link BogeyModels#resolve(ResourceLocation,
 * ResourceLocation)} has to answer without a {@code BogeyStyle}; these tests pin that down, plus the
 * coordinate lift that turns Create's pose origin into the placement frame (the one place where a
 * wrong constant puts every train bogey 1.5 blocks off).
 */
class ContraptionBogeyModelsTest {

    private static final ResourceLocation STYLE = ResourceLocation.fromNamespaceAndPath("kasuga_lib", "test_style");
    private static final ResourceLocation OTHER_STYLE = ResourceLocation.fromNamespaceAndPath("kasuga_lib", "other_style");
    private static final BogeySizes.BogeySize SIZE = new BogeySizes.BogeySize(
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "test_size"), 0.5f);
    private static final BogeySizes.BogeySize OTHER_SIZE = new BogeySizes.BogeySize(
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "other_size"), 0.5f);
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/block/bogey/frame.obj");

    @AfterEach
    void clearRegistry() {
        BogeyModels.clear();
    }

    @Test
    void styleWideEntryAnswersForAnySize() {
        BogeyModels.register(STYLE, MODEL, 0.25f);
        BogeyModels.Placement placement = BogeyModels.resolve(STYLE, SIZE.id());
        assertNotNull(placement);
        assertEquals(MODEL, placement.model());
        assertEquals(0.25f, placement.yOffset());
    }

    @Test
    void sizeEntryWinsOverTheStyleWideOne() {
        BogeyModels.register(STYLE, MODEL, 0f);
        ResourceLocation wheel = ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/block/bogey/wheel.obj");
        BogeyModels.register(STYLE, SIZE, List.of(ModelPart.of(MODEL), ModelPart.at(wheel, new Transform())), -1f);

        List<ModelPart> parts = BogeyModels.resolve(STYLE, SIZE.id()).parts();
        assertEquals(2, parts.size());
        assertEquals(wheel, parts.get(1).model());
        assertEquals(-1f, BogeyModels.resolve(STYLE, SIZE.id()).yOffset());

        // Another size still falls back to the style-wide single-model entry.
        assertEquals(1, BogeyModels.resolve(STYLE, OTHER_SIZE.id()).parts().size());
        assertEquals(0f, BogeyModels.resolve(STYLE, OTHER_SIZE.id()).yOffset());
    }

    @Test
    void unknownOrMissingStyleResolvesToNull() {
        assertNull(BogeyModels.resolve(OTHER_STYLE, SIZE.id()));
        assertNull(BogeyModels.resolve(null, SIZE.id()));
        BogeyModels.register(STYLE, MODEL, 0f);
        assertNull(BogeyModels.resolve(OTHER_STYLE, null));
    }

    @Test
    void styleIdComesFromTheBogeyNbt() {
        CompoundTag data = new CompoundTag();
        NBTHelper.writeResourceLocation(data, AbstractBogeyBlockEntity.BOGEY_STYLE_KEY, STYLE);
        assertEquals(STYLE, BogeyModels.styleIdOf(data));
        assertNull(BogeyModels.styleIdOf(new CompoundTag()));
        assertNull(BogeyModels.styleIdOf(null));
    }

    @Test
    void theContraptionRootIsLiftedFromCreatesOriginToThePlacementFrame() {
        org.joml.Matrix4f pose = new org.joml.Matrix4f().translate(10f, 20f, 30f);
        Transform root = ContraptionBogeyProxy.rootOf(pose, new org.joml.Vector3i(1, 2, 3));

        org.joml.Vector3f position = root.getPosition();
        assertEquals(11f, position.x, 1e-5f);
        // Create's origin sits 1.5 + 1/128 below the block centre in both hosts; the lift is derived
        // from BogeyRoots.CREATE_Y_OFFSET so a placed bogey and a carriage bogey land identically.
        assertEquals(22f + ContraptionBogeyProxy.CREATE_ORIGIN_LIFT, position.y, 1e-5f);
        assertEquals(22f + 1.5f + 1f / 128f, position.y, 1e-5f);
        assertEquals(-BogeyRoots.CREATE_Y_OFFSET, ContraptionBogeyProxy.CREATE_ORIGIN_LIFT, 0f);
        assertEquals(33f, position.z, 1e-5f);
    }

    @Test
    void thePoseMatrixIsNotAliased() {
        org.joml.Matrix4f pose = new org.joml.Matrix4f().translate(1f, 2f, 3f);
        Transform root = ContraptionBogeyProxy.rootOf(pose, new org.joml.Vector3i());
        root.translate(0f, 5f, 0f);
        // The caller's matrix must be untouched: the visual reuses its pose stack every frame.
        assertEquals(2f, pose.m31(), 1e-5f);
        assertTrue(root.getPosition().y > pose.m31());
    }

    @Test
    void theElementKeepsTheLastPublishedPose() {
        Transform first = new Transform().translate(0f, 0f, 0f);
        ContraptionBogey bogey = new ContraptionBogey(STYLE, SIZE.id(), 15f, first);
        assertEquals(15f, bogey.wheelAngle());

        Transform second = new Transform().translate(4f, 5f, 6f);
        bogey.update(90f, second);
        assertEquals(90f, bogey.wheelAngle());
        assertEquals(4f, bogey.root().getPosition().x, 1e-5f);
        assertNotSame(second, bogey.root());

        Transform exposed = bogey.root();
        exposed.translate(100f, 0f, 0f);
        assertEquals(4f, bogey.root().getPosition().x, 1e-5f);
    }

    @Test
    void tickSupplierAndFramePathUseTheSameNonZeroPlacementOffset() {
        BogeyModels.register(STYLE, SIZE, List.of(ModelPart.of(MODEL)), 0.75f);
        ContraptionBogey bogey = new ContraptionBogey(
                STYLE, SIZE.id(), 0f, new Transform().translate(0f, 10f, 0f));
        ContraptionBogeyProxy template = new ContraptionBogeyProxy();

        ProxyConfig config = template.configure(bogey);
        assertNotNull(config);
        Transform tickRoot = config.rootTransform().get();
        assertEquals(10.75f, tickRoot.getPosition().y, 1e-5f);

        bogey.update(30f, new Transform().translate(0f, 20f, 0f));
        Transform frameRoot = bogey.normalizedRoot();
        assertEquals(20.75f, frameRoot.getPosition().y, 1e-5f);
        assertEquals(tickRoot.getPosition().y + 10f, frameRoot.getPosition().y, 1e-5f);
    }

    @Test
    void concurrentReadsSeeOneImmutableRootAndAnglePair() throws Exception {
        ContraptionBogey bogey = new ContraptionBogey(
                STYLE, SIZE.id(), 0f, new Transform());
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> {
                try {
                    started.countDown();
                    for (int generation = 1; generation <= 10_000; generation++) {
                        bogey.update(generation, new Transform().translate(generation, 0f, 0f));
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    running.set(false);
                }
            });
            Future<?> reader = executor.submit(() -> {
                try {
                    started.await();
                    while (running.get() || failure.get() != null) {
                        ContraptionBogey.HostFrame frame = bogey.hostFrame();
                        Transform root = frame.root();
                        assertEquals(frame.wheelAngle(), root.getPosition().x, 0f);
                        root.translate(100_000f, 0f, 0f);
                        assertEquals(frame.wheelAngle(), frame.root().getPosition().x, 0f);
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                    running.set(false);
                }
            });
            writer.get(10, TimeUnit.SECONDS);
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        if (failure.get() != null) {
            throw new AssertionError("HostFrame publication was inconsistent", failure.get());
        }
    }

    @Test
    void normalizedRootReadsOneFrameAndAppliesYOffsetExactlyOnce() {
        ContraptionBogey bogey = new ContraptionBogey(
                STYLE, SIZE.id(), 42f, new Transform().translate(1f, 10f, 2f));
        bogey.placementYOffset(0.75f);

        ContraptionBogey.HostFrame frame = bogey.hostFrame();
        Transform normalized = bogey.normalizedRoot(frame);

        assertEquals(42f, frame.wheelAngle());
        assertEquals(1f, normalized.getPosition().x, 1e-5f);
        assertEquals(10.75f, normalized.getPosition().y, 1e-5f);
        assertEquals(2f, normalized.getPosition().z, 1e-5f);
    }

    @Test
    void hostFrameRejectsNonFiniteWheelAngles() {
        Transform root = new Transform();
        assertThrows(IllegalArgumentException.class,
                () -> new ContraptionBogey.HostFrame(Float.NaN, root));
        assertThrows(IllegalArgumentException.class,
                () -> new ContraptionBogey.HostFrame(Float.POSITIVE_INFINITY, root));
        assertThrows(IllegalArgumentException.class,
                () -> new ContraptionBogey.HostFrame(Float.NEGATIVE_INFINITY, root));

        ContraptionBogey bogey = new ContraptionBogey(STYLE, SIZE.id(), 0f, root);
        assertThrows(IllegalArgumentException.class, () -> bogey.update(Float.NaN, root));
    }

    @Test
    void framePublicationRunsTheConsumerInsideTheSameHostFrameLock() throws Exception {
        ContraptionBogey bogey = new ContraptionBogey(
                STYLE, SIZE.id(), 0f, new Transform());
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<ContraptionBogey.HostFrame> observed = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> publisher = executor.submit(() -> bogey.publishFrame(
                    1f, new Transform().translate(1f, 0f, 0f), frame -> {
                        observed.set(frame);
                        callbackEntered.countDown();
                        try {
                            releaseCallback.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }));
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));

            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                bogey.update(2f, new Transform().translate(2f, 0f, 0f));
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(second.isDone(),
                    "a second frame update must wait until the published callback completes");
            assertEquals(1f, observed.get().wheelAngle());
            assertEquals(1f, observed.get().root().getPosition().x, 1e-5f);

            releaseCallback.countDown();
            publisher.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
        assertEquals(2f, bogey.hostFrame().wheelAngle());
    }
}
