package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.models.uml.backend.ModelLightingMode;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class ModelLightingTest {
    @Test
    void readsTheWorldsNativeShadeCoefficientsAndKeepsItemLightingSeparate() {
        BlockAndTintGetter level = (BlockAndTintGetter) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{BlockAndTintGetter.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getShade")) throw new AssertionError("Unexpected world access");
                    assertEquals(true, args[1]);
                    return switch ((Direction) args[0]) {
                        case DOWN, UP -> .9f;
                        case NORTH, SOUTH -> .8f;
                        case EAST, WEST -> .6f;
                    };
                });
        ModelLighting world = ModelLighting.world(ModelLightingMode.BLOCK, level);
        assertEquals(.9f, world.downShade());
        assertEquals(.9f, world.upShade());
        assertEquals(1, world.shaderMode());
        assertEquals(2, ModelLighting.item(ModelLightingMode.BLOCK).shaderMode());
        assertSame(ModelLighting.PBR, ModelLighting.world(ModelLightingMode.PBR, level));
    }

    @Test
    void itemLightBypassesWorldOriginSamplingEvenWithoutALoadedWorld() {
        MCBackendContext worldContext = new MCBackendContext(null, new PoseStack(), null, null,
                null, null, new Matrix4f(), new Matrix4f(), 0, null, null);
        MCBackendContext itemContext = worldContext.withItemLighting(0, 10 << 16);
        MCBackend.BackendTransform origin = new MCBackend.BackendTransform(null, null, null,
                false, false, true, true, 1, 1, -1, -1);
        assertNull(worldContext.getItemLighting());
        assertEquals(0, MCBackend.resolveLightData(origin, itemContext).packedLight());
        assertEquals(10 << 16, itemContext.getItemLighting().packedOverlay());
        assertEquals(1f, MCBackend.resolveLightData(origin, itemContext).brightness());
    }
}
