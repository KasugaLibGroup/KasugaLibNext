package lib.kasuga.rendering.effect;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;
import org.jetbrains.annotations.ApiStatus;

/** Dispatches registered pipelines from the single NeoForge world-render event hook. */
@ApiStatus.Internal
public final class WorldRenderPipelineDispatcher {
    private static final Logger LOGGER = LogUtils.getLogger();

    private WorldRenderPipelineDispatcher() {}

    public static void dispatch(RenderLevelStageEvent event) {
        var pipelines = WorldRenderPipelineRegistry.pipelines(event.getStage());
        if (pipelines.isEmpty()) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        for (WorldRenderPipelineRegistry.RegisteredPipeline entry : pipelines) {
            WorldRenderPipelineContext context = WorldRenderPipelineContext.from(
                    event, minecraft, entry.descriptor(), entry.compiledPipeline()
            );
            context.poseStack().pushPose();
            try {
                entry.pipeline().render(context);
            } catch (RuntimeException exception) {
                LOGGER.error("World render pipeline {} failed at stage {}", entry.id(), entry.stage(), exception);
            } finally {
                context.poseStack().popPose();
            }
        }
    }
}
