package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.FogType;

import java.util.HashMap;
import java.util.Map;

/** Main-view range shared by distant geometry and the terrain depth projection that occludes it. */
public final class DistantWorldRendering {
    private static final Map<ResourceLocation, Float> RANGES = new HashMap<>();

    private DistantWorldRendering() {}

    public static void setRange(ResourceLocation owner, float blocks) {
        RenderSystem.assertOnRenderThread();
        if (!Float.isFinite(blocks) || blocks < 0) throw new IllegalArgumentException("Invalid distant render range");
        if (blocks == 0) RANGES.remove(owner);
        else RANGES.put(owner, blocks);
    }

    public static float farPlane(float vanilla) {
        if (RANGES.isEmpty() || Minecraft.getInstance().level == null || MinecraftWorldViews.currentView() != null) return vanilla;
        float farPlane = vanilla;
        for (float range : RANGES.values()) farPlane = Math.max(farPlane, range * 1.25f);
        return farPlane;
    }

    /** Keeps the level's fog color and shape, limiting the distance change to this draw pass. */
    public static void withFog(float range, Runnable draw) {
        if (Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
            draw.run();
            return;
        }
        float start = RenderSystem.getShaderFogStart();
        float end = RenderSystem.getShaderFogEnd();
        try {
            RenderSystem.setShaderFogStart(Math.max(start, range * .75f));
            RenderSystem.setShaderFogEnd(Math.max(end, range));
            draw.run();
        } finally {
            RenderSystem.setShaderFogStart(start);
            RenderSystem.setShaderFogEnd(end);
        }
    }
}
