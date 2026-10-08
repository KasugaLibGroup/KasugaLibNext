package lib.kasuga.rendering.models.mc.api;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Rigid equipment over common skeleton anchors. Render after the model's final interpolated pose is evaluated. */
public final class McModelEquipment implements AutoCloseable {
    @FunctionalInterface public interface Renderer { void render(Context context); }
    public record Context(EquipmentSlot slot, ItemStack stack, ItemDisplayContext displayContext, boolean leftHand,
                          @Nullable LivingEntity entity, PoseStack poseStack, MultiBufferSource buffers,
                          @Nullable Level level, int light, int overlay, int seed) {}
    private record Binding(Supplier<String> anchor, Supplier<ItemStack> stack, Supplier<ItemDisplayContext> display,
                           Supplier<Boolean> left, Transform offset, Renderer renderer) {}
    private static final Renderer ITEM = context -> Minecraft.getInstance().getItemRenderer().renderStatic(
            context.entity(), context.stack(), context.displayContext(), context.leftHand(), context.poseStack(),
            context.buffers(), context.level(), context.light(), context.overlay(), context.seed());

    private final McModelHandle handle;
    private Supplier<? extends LivingEntity> entity;
    private final EnumMap<EquipmentSlot, Binding> bindings = new EnumMap<>(EquipmentSlot.class);
    private final Vector3d origin = new Vector3d();
    private boolean closed;

    public McModelEquipment(McModelHandle handle, Supplier<? extends LivingEntity> entity) {
        this.handle = Objects.requireNonNull(handle); this.entity = Objects.requireNonNull(entity);
    }
    /** Physical hand anchors; the main-hand slot follows the entity's dominant arm, including left-handed entities. */
    public McModelEquipment bindHands(String rightAnchor, String leftAnchor, Transform rightOffset, Transform leftOffset) {
        ensureOpen(); Objects.requireNonNull(rightAnchor); Objects.requireNonNull(leftAnchor);
        Objects.requireNonNull(rightOffset); Objects.requireNonNull(leftOffset);
        Transform rightCopy = rightOffset.copy(), leftCopy = leftOffset.copy();
        bindHand(EquipmentSlot.MAINHAND, rightAnchor, leftAnchor, rightCopy, leftCopy);
        bindHand(EquipmentSlot.OFFHAND, rightAnchor, leftAnchor, rightCopy, leftCopy);
        return this;
    }
    public McModelEquipment bindHands(String rightAnchor, String leftAnchor) {
        return bindHands(rightAnchor, leftAnchor, new Transform(), new Transform());
    }
    private boolean left(EquipmentSlot slot) {
        LivingEntity owner = entity.get();
        boolean mainLeft = owner != null && owner.getMainArm() == HumanoidArm.LEFT;
        return slot == EquipmentSlot.MAINHAND ? mainLeft : !mainLeft;
    }
    private void bindHand(EquipmentSlot slot, String right, String left, Transform rightOffset, Transform leftOffset) {
        // Offset is selected at render time along with the physical arm.
        bindings.put(slot, new Binding(() -> left(slot) ? left : right, () -> stack(slot),
                () -> left(slot) ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                () -> left(slot), new Transform(), context -> {
                    context.poseStack().mulPose((context.leftHand() ? leftOffset : rightOffset).transform());
                    ITEM.render(context);
                }));
    }
    private ItemStack stack(EquipmentSlot slot) {
        LivingEntity owner = entity.get(); return owner == null ? ItemStack.EMPTY : owner.getItemBySlot(slot);
    }
    /** Head items such as pumpkins; humanoid armor layers are supplied through a custom Renderer. */
    public McModelEquipment bindHeadItem(String anchor, Transform offset) {
        return bind(EquipmentSlot.HEAD, anchor, () -> stack(EquipmentSlot.HEAD), ItemDisplayContext.HEAD, false, offset, ITEM);
    }
    public McModelEquipment bind(EquipmentSlot slot, String anchor, Supplier<ItemStack> stack,
                                 ItemDisplayContext display, boolean leftHand, Transform offset) {
        return bind(slot, anchor, stack, display, leftHand, offset, ITEM);
    }
    /** Slot-neutral extension for custom equipment renderers, armor layers or other host-owned displays. */
    public McModelEquipment bind(EquipmentSlot slot, String anchor, Supplier<ItemStack> stack,
                                 ItemDisplayContext display, boolean leftHand, Transform offset, Renderer renderer) {
        ensureOpen(); Objects.requireNonNull(slot); Objects.requireNonNull(anchor); Objects.requireNonNull(stack);
        Objects.requireNonNull(display); Objects.requireNonNull(offset); Objects.requireNonNull(renderer);
        if (anchor.isBlank()) throw new IllegalArgumentException("Anchor name must be nonblank");
        bindings.put(slot, new Binding(() -> anchor, stack, () -> display, () -> leftHand, offset.copy(), renderer));
        return this;
    }
    public boolean unbind(EquipmentSlot slot) { ensureOpen(); return bindings.remove(slot) != null; }

    /** PoseStack must be in camera-relative world space, without an extra entity translation. Does not tick or sample animation. */
    public int render(PoseStack poseStack, MultiBufferSource buffers, Vec3 cameraPosition, int light, int overlay) {
        if (closed || handle.instance() == null || !handle.isMounted() || !handle.shown()) return 0;
        Objects.requireNonNull(poseStack); Objects.requireNonNull(buffers); Objects.requireNonNull(cameraPosition);
        origin.set(cameraPosition.x, cameraPosition.y, cameraPosition.z);
        int rendered = 0;
        for (var entry : new ArrayList<>(bindings.entrySet())) {
            if (closed) break;
            Binding binding = entry.getValue();
            ItemStack stack = Objects.requireNonNull(binding.stack.get(), "equipment stack");
            if (stack.isEmpty()) continue;
            var transform = handle.instance().getSkeletonInstance().anchorTransformRelative(binding.anchor.get(), origin);
            if (transform == null) continue;
            poseStack.pushPose();
            try {
                poseStack.mulPose(transform.transform()); poseStack.mulPose(binding.offset.transform());
                LivingEntity owner = entity.get();
                binding.renderer.render(new Context(entry.getKey(), stack, binding.display.get(), binding.left.get(), owner,
                        poseStack, buffers, owner == null ? Minecraft.getInstance().level : owner.level(), light, overlay,
                        owner == null ? handle.instanceLoc().hashCode() : owner.getId()));
                rendered++;
            } finally { poseStack.popPose(); }
        }
        return rendered;
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Equipment attachment closed"); }
    @Override public void close() { if (closed) return; closed = true; bindings.clear(); entity = null; }
}
