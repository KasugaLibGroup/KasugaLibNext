package lib.kasuga.rendering.models.mc.proxies;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.animation.FormulaTransformSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.ParameterSpec;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM contract tests for the multi-part content shapes: what {@link ProxyConfig} accepts, what
 * the part instance keys look like, and what a {@link ModelPart.Spin} is allowed to say.
 *
 * <p>Nothing here touches a level or the pipeline — the runtime behaviour (bind / spin / release)
 * needs a game test, but every content-authoring mistake the layer can catch early is caught here.
 */
class ModelPartsTest {

    private static final ResourceLocation FRAME = id("frame");
    private static final ResourceLocation WHEEL = id("wheel");

    private static final ParameterSpec<Float> ANGLE = ParameterSpec.<Float>parameter(
                    Id.fromNamespaceAndPath("kasuga_lib", "test/wheel_angle"), Float.class, Codec.FLOAT)
            .defaultValue(0f)
            .build();

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_lib", "test/" + path);
    }

    @Test
    void partInstanceKeysAreStableIndexedAndNamespacePreserving() {
        assertEquals(ResourceLocation.fromNamespaceAndPath("kasuga_lib", "proxy/block/0/1/2/part/0"),
                ProxyInstance.partInstanceKey(
                        ResourceLocation.fromNamespaceAndPath("kasuga_lib", "proxy/block/0/1/2"), 0));
        assertEquals(ResourceLocation.fromNamespaceAndPath("kasuga_lib", "proxy/block/0/1/2/part/3"),
                ProxyInstance.partInstanceKey(
                        ResourceLocation.fromNamespaceAndPath("kasuga_lib", "proxy/block/0/1/2"), 3));
    }

    @Test
    void twoPartsOfTheSameModelStillGetDistinctInstanceKeys() {
        assertNotEquals(ProxyInstance.partInstanceKey(id("proxy/x"), 1),
                ProxyInstance.partInstanceKey(id("proxy/x"), 2));
    }

    @Test
    void unresolvedIdentityStillProducesALegalKey() {
        ResourceLocation key = ProxyInstance.partInstanceKey(null, 0);
        assertEquals("kasuga_lib", key.getNamespace());
        assertTrue(key.getPath().endsWith("/part/0"));
    }

    @Test
    void partsAreCopiedAndImmutable() {
        ProxyConfig config = ProxyConfig.parts(List.of(ModelPart.of(FRAME)));
        assertEquals(1, config.parts().size());
        assertThrows(UnsupportedOperationException.class,
                () -> config.parts().add(ModelPart.of(WHEEL)));
        assertTrue(config.parts().get(0).spin() == null);
    }

    @Test
    void aConfigCarryingBothSingleModelAndPartsIsRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new ProxyConfig(null, FRAME, null, null, List.of(ModelPart.of(FRAME))));
        assertTrue(thrown.getMessage().contains("drawn twice"), thrown.getMessage());
    }

    @Test
    void emptyConfigIsOnlyEmptyWhenItAsksForNothing() {
        assertTrue(new ProxyConfig(null, null, null, null).isEmpty());
        assertFalse(ProxyConfig.model(FRAME).isEmpty());
        assertFalse(ProxyConfig.parts(List.of(ModelPart.of(FRAME))).isEmpty());
        assertFalse(ProxyConfig.machine(Id.fromNamespaceAndPath("kasuga_lib", "m"), null).isEmpty());
    }

    @Test
    void legacySingleModelConfigCarriesNoParts() {
        ProxyConfig config = new ProxyConfig(null, FRAME, "name", null);
        assertTrue(config.parts().isEmpty());
        assertEquals(FRAME, config.model());
        assertEquals("name", config.modelName());
        assertNull(config.machine());
    }

    @Test
    void withRootKeepsPartsAndModelShape() {
        ProxyConfig multi = ProxyConfig.parts(List.of(ModelPart.of(FRAME), ModelPart.of(WHEEL)))
                .withRoot(Transform::new);
        assertEquals(2, multi.parts().size());
        assertNull(multi.model());
        assertEquals(Transform.class, multi.rootTransform().get().getClass());

        ProxyConfig single = ProxyConfig.model(FRAME).withRoot(Transform::new);
        assertEquals(FRAME, single.model());
        assertTrue(single.parts().isEmpty());
    }

    @Test
    void staticPartDefaultsToIdentityAtTheRoot() {
        ModelPart part = ModelPart.of(FRAME);
        assertNull(part.modelName());
        assertNull(part.spin());
        assertTrue(part.local().isIdentity());
    }

    @Test
    void localIsDefensivelyCopiedAtBothBoundaries() {
        Transform source = new Transform().translate(1f, 2f, 3f);
        ModelPart part = ModelPart.at(FRAME, source);

        source.translate(10f, 20f, 30f);
        assertPosition(part.local(), 1f, 2f, 3f);

        Transform exposed = part.local();
        exposed.translate(100f, 200f, 300f);
        assertPosition(part.local(), 1f, 2f, 3f);
    }

    @Test
    void spinDefaultsToOneToOneAndRejectsNonFiniteScales() {
        ModelPart.Spin param = ModelPart.Spin.param(ANGLE);
        assertEquals(1f, param.scale());
        assertEquals(ANGLE, ((ModelPart.Spin.Param) param).angle());
        assertEquals(ModelPart.TimeDomain.TICK, param.timeDomain());

        ModelPart.Spin tick = ModelPart.Spin.tick(() -> 12f);
        assertEquals(1f, tick.scale());
        assertEquals(ModelPart.TimeDomain.TICK, tick.timeDomain());

        ModelPart.Spin frame = ModelPart.Spin.frame(() -> 12f);
        assertEquals(1f, frame.scale());
        assertEquals(ModelPart.TimeDomain.FRAME, frame.timeDomain());

        assertThrows(IllegalArgumentException.class, () -> new ModelPart.Spin.Param(ANGLE, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> ModelPart.Spin.tick(() -> 0f, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> ModelPart.Spin.frame(() -> 0f, Float.NaN));
    }

    @Test
    void machineSpinReadsZeroWithoutAMachine() {
        ModelPart.Spin spin = ModelPart.Spin.param(ANGLE);
        assertEquals(ModelPart.TimeDomain.TICK, spin.timeDomain());
        assertEquals(0f, ((ModelPart.Spin.Param) spin).degrees(null), 1e-6f);
    }

    @Test
    void tickAndFrameAnglesHaveNoPartialTickParameter() {
        ModelPart.Spin tick = ModelPart.Spin.tick(() -> 45f);
        ModelPart.Spin frame = ModelPart.Spin.frame(() -> 90f);
        assertEquals(ModelPart.TimeDomain.TICK, tick.timeDomain());
        assertEquals(ModelPart.TimeDomain.FRAME, frame.timeDomain());
    }

    @Test
    void spinningKeepsModelNameAndLocalPlacement() {
        Transform local = new Transform().translate(1f, 2f, 3f);
        ModelPart part = ModelPart.at(WHEEL, local)
                .spinning(ModelPart.Spin.tick(() -> 7f));
        assertEquals(WHEEL, part.model());
        assertEquals(local, part.local());
        assertEquals(ModelPart.TimeDomain.TICK, part.motion().timeDomain());
    }

    @Test
    void aCompleteMotionKeepsModelAndLocalPlacement() {
        Transform local = new Transform().translate(1f, 2f, 3f);
        Transform motion = new Transform()
                .translate(0.25f, 0f, -0.5f)
                .rotate(10f, 20f, 30f, true)
                .scale(1f, 1f, 2f);
        ModelPart part = ModelPart.at(WHEEL, local)
                .moving(ModelPart.tickTransform(machine -> motion));

        assertEquals(WHEEL, part.model());
        assertEquals(local, part.local());
        assertNull(part.spin());
        assertEquals(ModelPart.TimeDomain.TICK, part.motion().timeDomain());
    }

    @Test
    void transformFactoriesCarryTheirExplicitTimeDomain() {
        Transform motion = new Transform().translate(1f, 0f, 0f);
        ModelPart.Motion tick = ModelPart.tickTransform(machine -> motion);
        ModelPart.Motion frame = ModelPart.frameTransform(() -> motion);

        assertEquals(ModelPart.TimeDomain.TICK, tick.timeDomain());
        assertEquals(ModelPart.TimeDomain.FRAME, frame.timeDomain());
    }

    @Test
    void formulaFactoriesSampleOnePartInBothTimeDomains() {
        AnimationClip clip = new AnimationClip(
                Id.fromNamespaceAndPath("kasuga_lib", "formula_motion"),
                1f,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(AnimationClip.FormulaTransformTrack.part(
                        "part_06",
                        new AnimationClip.FormulaTransform(
                                new AnimationClip.FormulaVector("query.wheel_angle", "", ""),
                                AnimationClip.FormulaVector.EMPTY,
                                AnimationClip.FormulaVector.EMPTY
                        )
                ))
        );
        FormulaTransformSampler sampler = new FormulaTransformSampler(clip);

        ModelPart.Motion tick = ModelPart.tickFormula(
                sampler,
                "part_06",
                input -> input.assignQuery("wheel_angle", 12f)
        );
        ModelPart.Motion frame = ModelPart.frameFormula(
                sampler,
                "part_06",
                input -> input.assignQuery("wheel_angle", 24f)
        );

        Transform tickTransform = ((ModelPart.TransformMotion.Tick) tick).source().atTick(null);
        Transform frameTransform = ((ModelPart.TransformMotion.Frame) frame).source().current();
        assertPosition(tickTransform, 12f, 0f, 0f);
        assertPosition(frameTransform, 24f, 0f, 0f);
        assertThrows(IllegalArgumentException.class,
                () -> ModelPart.tickFormula(sampler, "missing", ignored -> {
                }));
    }

    @Test
    void fullMotionRejectsNonFiniteTransforms() {
        Transform invalid = new Transform();
        invalid.transform().m30(Float.NaN);
        PartMotionSampler tick = new PartMotionSampler(ModelPart.tickTransform(machine -> invalid));
        PartMotionSampler frame = new PartMotionSampler(ModelPart.frameTransform(() -> invalid));
        assertNull(tick.initialSnapshot(null).sample(1f));
        assertNull(frame.snapshot().sample(0.5f));
    }

    private static void assertPosition(Transform transform, float x, float y, float z) {
        assertEquals(x, transform.getPosition().x, 1e-6f);
        assertEquals(y, transform.getPosition().y, 1e-6f);
        assertEquals(z, transform.getPosition().z, 1e-6f);
    }
}
