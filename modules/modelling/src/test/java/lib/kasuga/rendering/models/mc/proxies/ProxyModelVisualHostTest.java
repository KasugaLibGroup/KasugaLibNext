package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyCommandPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyComposition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyControllerPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyRuntime;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyVisualPort;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProxyModelVisualHostTest {
    private static final ResourceLocation HOST = ResourceLocation.parse("test:contraption/first/block/2/3/4");

    @AfterEach
    void cleanup() {
        BlockEntityProxyDefinitions.clear();
        ProxyRuntime.GLOBAL.clear();
    }

    @Test
    void externalAnchorUsesHostRotationOnceAndDoesNotTranslateLocalBlockPositionTwice() {
        Transform anchor = new Transform().translate(100f, 20f, -30f).rotate(0f, 90f, 0f, true);
        Transform root = ProxyModelProxy.placementRoot(new BlockPos(2, 3, 4), 0.25f, 90f, anchor);

        assertVector(new Vector3f(100.5f, 20.25f, -30.5f), root.getPosition());
        assertVector(new Vector3f(0f, 0f, -1f), root.applyDirection(new Vector3f(0f, 0f, -1f)));
        assertVector(new Vector3f(100f, 20f, -30f), anchor.getPosition());
    }

    @Test
    void ordinaryWorldRootRetainsItsBlockPlacement() {
        Transform root = ProxyModelProxy.placementRoot(new BlockPos(2, 3, 4), 0.25f, 90f, null);
        assertVector(new Vector3f(2.5f, 3.25f, 4.5f), root.getPosition());
        assertVector(new Vector3f(1f, 0f, 0f), root.applyDirection(new Vector3f(0f, 0f, -1f)));
    }

    @Test
    void virtualHostTicksReuseTheRegisteredCompositionAndFrameUpdatesDoNotAdvanceItsClock() {
        Object type = new Object();
        ProxyModelBlockEntity model = new CapturedModel();
        BlockEntityProxyDefinitions.<ProxyModelBlockEntity, String, Void, Void, Void>registerForType(
                type, ResourceLocation.parse("test:captured_model"), ignored -> ProxyComposition.of(
                        LogicalStepPolicy.fixed(0.05, 1), entity -> "state",
                        ProxyControllerPort.none(), (entity, output) -> {},
                        ProxyCommandPort.none(), ProxyVisualPort.none(), List.of()));
        model.bindVisualHost(HOST, new Transform().translate(100f, 0f, 0f));

        assertTrue(ProxyTickers.tickHost(type, model, model, 1L, 0.05));
        var session = model.proxySession();
        assertNotNull(session);
        long revision = session.revision();
        assertTrue(revision > 0);
        assertTrue(ProxyTickers.tickHost(type, model, model, 1L, 0.05));
        assertEquals(revision, session.revision());
        model.bindVisualHost(HOST, new Transform().translate(101f, 0f, 0f));
        session.publishFrame(null);
        assertSame(session, model.proxySession());
        assertEquals(revision, session.revision());
        assertEquals(HOST, model.visualIdentity());
        assertEquals(101f, model.visualHostTransform().getPosition().x);

        model.releaseVisualHost();
        assertTrue(session.isClosed());
        assertNull(model.proxySession());
        assertNull(model.visualHostTransform());
    }

    @Test
    void changingHostRetiresTheOldSessionAndCopiesTheNewPose() {
        Object type = new Object();
        ProxyModelBlockEntity model = new CapturedModel();
        BlockEntityProxyDefinitions.<ProxyModelBlockEntity, String, Void, Void, Void>registerForType(
                type, ResourceLocation.parse("test:changed_host"), ignored -> ProxyComposition.of(
                        LogicalStepPolicy.fixed(0.05, 1), entity -> "state",
                        ProxyControllerPort.none(), (entity, output) -> {},
                        ProxyCommandPort.none(), ProxyVisualPort.none(), List.of()));
        model.bindVisualHost(HOST, new Transform());
        ProxyTickers.tickHost(type, model, model, 1L, 0.05);
        var session = model.proxySession();
        Transform next = new Transform().translate(20f, 0f, 0f);
        model.bindVisualHost(ResourceLocation.parse("test:contraption/second/block/2/3/4"), next);
        next.translate(500f, 0f, 0f);

        assertTrue(session.isClosed());
        assertNull(model.proxySession());
        assertEquals(20f, model.visualHostTransform().getPosition().x);
        model.visualHostTransform().translate(100f, 0f, 0f);
        assertEquals(20f, model.visualHostTransform().getPosition().x);
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-5f);
        assertEquals(expected.y, actual.y, 1e-5f);
        assertEquals(expected.z, actual.z, 1e-5f);
    }

    private static final class CapturedModel extends ProxyModelBlockEntity {
        private CapturedModel() {
            super(null, new BlockPos(2, 3, 4), null,
                    ResourceLocation.parse("test:models/vehicle.bbmodel"), null, 0f, Map.of());
        }

        @Override
        public boolean isValidBlockState(BlockState state) {
            return true;
        }
    }
}
