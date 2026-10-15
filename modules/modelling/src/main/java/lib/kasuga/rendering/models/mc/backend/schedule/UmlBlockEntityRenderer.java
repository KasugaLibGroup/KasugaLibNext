package lib.kasuga.rendering.models.mc.backend.schedule;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Bridges a UML model to vanilla's block-entity dispatch.
 *
 * <p>Vanilla decides per frame whether a {@code BlockEntityRenderer} runs:
 * the section frustum test ({@code ClientHooks.isBlockEntityRendererVisible})
 * plus this renderer's {@link #shouldRender} view-distance check gate
 * {@code BlockEntityRenderDispatcher.render}. Reaching {@link #render(T, float, PoseStack, MultiBufferSource, int, int)}
 * therefore proves vanilla visibility, and that is exactly what we forward to
 * the {@link ModelRenderScheduler}: instances mounted with
 * {@code RenderScheduleMode.VANILLA_RENDERER} are drawn by the global
 * AFTER_ENTITIES pipeline only on frames their block entity actually rendered
 * — otherwise sampling and upload are skipped entirely.</p>
 *
 * <p>Register with {@code BlockEntityRenderers.register(type, context ->
 * new UmlBlockEntityRenderer<>(context, be -> ...model...)}) after mounting
 * the model with the scheduler mode above.</p>
 */
public final class UmlBlockEntityRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {

    /** Resolves which mounted instance this block entity drives this frame. */
    @FunctionalInterface
    public interface ModelResolver<T extends BlockEntity> {
        @Nullable ModelInstance resolve(T blockEntity);
    }

    private final ModelResolver<T> resolver;
    private final int viewDistance;

    public UmlBlockEntityRenderer(ModelResolver<T> resolver) {
        this(resolver, 64);
    }

    /** Provider-style constructor for direct {@code BlockEntityRenderers.register} lambdas. */
    public UmlBlockEntityRenderer(BlockEntityRendererProvider.Context context,
                                  ModelResolver<T> resolver) {
        this(resolver, 64);
    }

    public UmlBlockEntityRenderer(ModelResolver<T> resolver, int viewDistance) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.viewDistance = Math.max(1, viewDistance);
    }

    @Override
    public void render(T blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        ModelInstance instance = resolver.resolve(blockEntity);
        if (instance != null) {
            ModelRenderScheduler.markRenderedThisFrame(instance);
        }
    }

    @Override
    public int getViewDistance() {
        return viewDistance;
    }
}
