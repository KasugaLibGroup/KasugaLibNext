package lib.kasuga.rendering.effect.builtin.cloud;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.cloud.gl.CloudVolumeRenderer;
import lib.kasuga.rendering.effect.EffectRenderer;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import org.joml.Vector3d;
import org.joml.Vector3f;

final class CloudEffectRenderer implements EffectRenderer<CumulonimbusCloud>, AutoCloseable {
    private CloudVolumeRenderer renderer;
    private CloudVolumeRenderer.Pass pass;
    private float partialTick;

    @Override public void begin(WorldRenderPipelineContext context) {
        RenderSystem.assertOnRenderThread();
        if (!CloudEffects.isEnabled()) return;
        if (renderer == null) renderer = new CloudVolumeRenderer();
        if (!renderer.isReady()) return;
        partialTick = context.partialTick().getGameTimeDeltaPartialTick(false);
        float angle = context.level().getSunAngle(partialTick);
        // Match vanilla's sky transform: Y(-90 degrees), then X(sun angle).
        var sun = new Vector3f(-(float) Math.sin(angle), (float) Math.cos(angle), 0);
        float daylight = Math.max(0, sun.y);
        float sunlight = daylight * (1 - context.level().getRainLevel(partialTick) * .65f);
        var cloudColor = context.level().getCloudColor(partialTick);
        var ambient = new Vector3f((float) cloudColor.x, (float) cloudColor.y, (float) cloudColor.z).mul(.32f);
        var camera = context.camera().getPosition();
        pass = renderer.begin(context.modelViewMatrix(), context.projectionMatrix(), new Vector3d(camera.x, camera.y, camera.z),
                sun, new Vector3f(.90f, .84f, .74f).mul(sunlight),
                ambient);
        var skyColor = context.level().getSkyColor(camera, partialTick);
        pass.atmosphereColor(new Vector3f((float) skyColor.x, (float) skyColor.y, (float) skyColor.z));
    }

    public void renderSky(WorldRenderPipelineContext context) {
        if (!CloudEffects.isEnabled() || !CloudEffects.isSkyEnabled()
                || !Float.isFinite(context.level().effects().getCloudHeight())) return;
        try {
            begin(context);
            if (pass != null) {
                var camera = context.camera().getPosition();
                pass.drawSky(CloudEffects.sky().sample(camera.x, camera.y, camera.z, partialTick,
                        context.level().getRainLevel(partialTick)));
            }
        } finally { end(context); }
    }

    @Override public void render(CumulonimbusCloud cloud, WorldRenderPipelineContext context) {
        if (pass != null) pass.draw(cloud.volume().sample(partialTick));
    }
    @Override public void end(WorldRenderPipelineContext context) {
        if (pass != null) { pass.close(); pass = null; }
    }
    @Override public void close() {
        if (!RenderSystem.isOnRenderThread()) { RenderSystem.recordRenderCall(this::close); return; }
        if (pass != null) { pass.close(); pass = null; }
        if (renderer != null) renderer.close();
    }
}
