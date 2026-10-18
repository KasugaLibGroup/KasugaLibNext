package lib.kasuga.rendering.models.mc.backend.schedule;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Bridges a UML model to vanilla's entity dispatch.
 *
 * <p>Vanilla collects an entity into the render queue only when
 * {@link EntityRenderer#shouldRender} passes — tracking distance via
 * {@code EntityType.getClientTrackingRange} plus the section frustum test.
 * When the entity is culled, this renderer's {@code render()} is never
 * invoked; by marking the model there, instances mounted with
 * {@code RenderScheduleMode.VANILLA_RENDERER} inherit exactly vanilla's
 * scheduling: culled entities neither sample nor draw.</p>
 *
 * <p>The base implementation performs no drawing itself (the global pipeline
 * draws the model); subclasses may override {@link #render} and call
 * {@code super.render} to add vanilla parts on top, and must still supply
 * {@link #getTextureLocation} for vanilla UI overlays (e.g. spectator menu).</p>
 */
public abstract class UmlModelEntityRenderer<T extends Entity> extends EntityRenderer<T> {

    protected UmlModelEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    /** The mounted instance driven by this entity, or {@code null} when it has none. */
    @Nullable
    protected abstract ModelInstance resolveModel(T entity);

    /** Fallback texture for vanilla overlay paths that query it. */
    protected abstract ResourceLocation modelTexture(T entity);

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        ResourceLocation texture = modelTexture(entity);
        return texture != null ? texture : ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        ModelInstance instance = resolveModel(entity);
        if (instance != null) {
            ModelRenderScheduler.markRenderedThisFrame(instance);
        }
    }
}
