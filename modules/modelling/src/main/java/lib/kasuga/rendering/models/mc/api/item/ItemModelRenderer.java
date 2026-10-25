package lib.kasuga.rendering.models.mc.api.item;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.models.mc.api.McModelHandle;
import lib.kasuga.rendering.models.mc.backend.MCBackend;
import lib.kasuga.rendering.models.mc.backend.MCBackendContext;
import lib.kasuga.rendering.models.mc.backend.schedule.ModelRenderScheduler;
import lib.kasuga.rendering.models.mc.backend.schedule.RenderScheduleMode;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The {@link BlockEntityWithoutLevelRenderer} (BEWLR) that draws pipeline models for items in the GUI,
 * in hand and on the ground.
 *
 * <p>Vanilla calls this instead of drawing item quads when the item's baked model reports
 * {@code isCustomRenderer()} (that is what {@code models/item/<id>.json = {"parent":
 * "minecraft:builtin/entity"}} produces). One instance is shared by every mapped item; the model is
 * resolved per {@link Item} through {@link ItemModelRenderers#binding}.
 *
 * <p>Each mapped item owns one {@link ModelInstance} at the origin, scheduled as
 * {@code VANILLA_RENDERER} so the world pass skips it, then marked for this frame and drawn through
 * {@link MCBackend#renderInstance}. The caller's {@link PoseStack} provides placement, so the same code
 * serves GUI icons, first/third person and dropped items.
 *
 * <p>Lifecycle: instances are created lazily (and retried until the model is published), mounted
 * idempotently (self-healing after resource reloads) and never destroyed here — the item keeps them for
 * the client's lifetime, like the vanilla BEWLR keeps its chest/shield models.
 */
public final class ItemModelRenderer extends BlockEntityWithoutLevelRenderer {

    /**
     * GUI-only vertical nudge, in the model's local space (negative = down on screen, because the GUI pose
     * flips Y). The display transform scales by 0.625 afterwards, so 0.5f lands at ~5 px at GUI scale 1;
     * use ~0.8f for a full half-slot (8 px).
     */
    private static final float GUI_DOWN_SHIFT = -0.5f;

    private final Map<Item, McModelHandle> handles = new IdentityHashMap<>();

    public ItemModelRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                             MultiBufferSource buffer, int light, int overlay) {
        ItemModelRenderers.Binding binding = ItemModelRenderers.binding(stack.getItem());
        if (binding == null) return;
        MCBackend backend = PipelineRegistry.backend();
        if (backend == null) return;

        McModelHandle handle = handles.computeIfAbsent(stack.getItem(),
                item -> McModelHandle.of(binding.model(), binding.modelName(), instanceLoc(item), null)
                        .scheduleVanillaRenderer());
        if (!handle.mount()) return;                 // model not published yet: retry on the next frame
        ModelInstance instance = handle.instance();
        if (instance == null) return;

        MCBackendContext backendContext = new MCBackendContext(
                null, pose, Minecraft.getInstance().renderBuffers(),
                buffer instanceof MultiBufferSource.BufferSource source ? source : null,
                null, null,
                RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                0, Minecraft.getInstance().getTimer(), Minecraft.getInstance().level);
        pose.pushPose();
        try {
            applyDisplayTransform(pose, context);
            // Widen the schedule gate for this one draw, then restore it: the instance stays
            // VANILLA_RENDERER otherwise, which is what keeps the world pass from drawing it at the origin.
            ModelRenderScheduler.setMode(instance, RenderScheduleMode.ALWAYS);
            try {
                backend.renderInstance(instance, backendContext);
            } finally {
                ModelRenderScheduler.setMode(instance, RenderScheduleMode.VANILLA_RENDERER);
            }
        } finally {
            pose.popPose();
        }
    }

    /**
     * Reproduces the block-item display transforms that a normal baked block model gets from
     * {@code block/block.json}'s {@code display} section. A {@code builtin/entity} model has
     * {@code ItemTransforms.NO_TRANSFORMS}, so without this the model would be drawn unrotated at full
     * size (and not isometric in the GUI).
     *
     * <p>{@code ItemRenderer} has already applied {@code translate(-0.5)} for the vanilla
     * {@code [0,1]} model space; our models are origin-centred, so that offset is cancelled first.
     */
    private static void applyDisplayTransform(PoseStack pose, ItemDisplayContext context) {
        pose.translate(0.5f, 0.5f, 0.5f);
        switch (context) {
            case GUI -> {
                transform(pose, 30, 225, 0, 0, 0, 0, 0.625f, false);
                // GUI-only nudge. ItemRenderer flips Y for the GUI (scale(16, -16, 16)), so a negative Y
                // here moves the icon DOWN on screen. Units are the model's local space, i.e. the shift is
                // multiplied by the 0.625 display scale above — 0.5f ≈ 5 px at GUI scale 1.
                pose.translate(0f, GUI_DOWN_SHIFT, 0f);
            }
            case GROUND -> transform(pose, 0, 0, 0, 0, 3, 0, 0.25f, false);
            case FIXED -> transform(pose, 0, 0, 0, 0, 0, 0, 0.5f, false);
            case THIRD_PERSON_RIGHT_HAND -> transform(pose, 75, 45, 0, 0, 2.5f, 0, 0.375f, false);
            case THIRD_PERSON_LEFT_HAND -> transform(pose, 75, 45, 0, 0, 2.5f, 0, 0.375f, true);
            case FIRST_PERSON_RIGHT_HAND -> transform(pose, 0, 45, 0, 0, 0, 0, 0.4f, false);
            case FIRST_PERSON_LEFT_HAND -> transform(pose, 0, 45, 0, 0, 0, 0, 0.4f, true);
            default -> { /* HEAD / NONE: vanilla block items have no transform there either */ }
        }
    }

    /** Same composition as {@code ItemTransform#apply(boolean, PoseStack)}. */
    private static void transform(PoseStack pose, float rx, float ry, float rz,
                                  float tx, float ty, float tz, float scale, boolean leftHand) {
        pose.translate(leftHand ? -tx : tx, ty, tz);
        pose.mulPose(new Quaternionf().rotationXYZ(
                (float) Math.toRadians(rx),
                (float) Math.toRadians(leftHand ? -ry : ry),
                (float) Math.toRadians(leftHand ? -rz : rz)));
        pose.scale(scale, scale, scale);
    }

    /** Stable instance key per item; the item is the identity, not the stack. */
    private static ResourceLocation instanceLoc(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return ResourceLocation.fromNamespaceAndPath("kasuga_lib", "item/" + id.getNamespace() + "/" + id.getPath());
    }
}
