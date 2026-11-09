package lib.kasuga.create.proxy;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import net.createmod.catnip.nbt.NBTHelper;
import lib.kasuga.rendering.models.mc.proxies.ModelPart;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry from a Create {@link BogeyStyle} (optionally narrowed to a {@link BogeySizes.BogeySize})
 * to the model this library's pipeline draws for it, plus that model's vertical offset.
 *
 * <p>Create's {@code BogeyStyle} only describes its own Flywheel renderer and the block used to
 * place the bogey; it carries no model identity for an external render pipeline. Content registers
 * the mapping from {@link lib.kasuga.create.registration.bogey.BogeyGroupReg#onCommitted(java.util.function.Consumer)}
 * — the style id does not exist before that callback runs — and {@link BogeyProxy} looks it up when
 * the proxy materializes.
 *
 * <h2>Vertical offset</h2>
 * <p>Create's own bogey models are authored with their origin at the rail plane, which is why its
 * visual drops the model by {@code 1.5 + 1/128} blocks. OBJ exports usually put the origin at the
 * model's base or centre instead, so the offset is per entry rather than global:
 * {@link BogeyRoots#CREATE_Y_OFFSET} reproduces Create's convention, {@code 0} centres a
 * centre-origin model in its block.
 *
 * <h2>Lookup rules</h2>
 * <ul>
 *     <li>an exact {@code (style, size)} entry wins;</li>
 *     <li>otherwise the style-wide entry is used;</li>
 *     <li>otherwise {@code null}, and the proxy falls back to its own default model.</li>
 * </ul>
 *
 * <p>Writes happen during mod initialization; reads happen on the first tick of each host and
 * later when a block entity is rebuilt after a style change.
 */
public final class BogeyModels {

    /**
     * What to draw for one style/size: one or more independently placed model parts.
     *
     * @param parts   the parts, in content order; each gets its own pipeline instance keyed
     *                {@code <proxy identity>/part/<index>}
     * @param yOffset vertical offset from the block centre applied to the proxy root, in blocks
     */
    public record Placement(List<ModelPart> parts, float yOffset) {

        public Placement {
            Objects.requireNonNull(parts, "parts");
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("a placement needs at least one model part");
            }
            parts = List.copyOf(parts);
        }

        /** Single-model placement: one part centred on the proxy root. */
        public Placement(ResourceLocation model, float yOffset) {
            this(List.of(ModelPart.of(model)), yOffset);
        }

        /** The model drawn by the first part — what a single-model registration shows. */
        public ResourceLocation model() {
            return parts.get(0).model();
        }
    }

    private record Key(ResourceLocation style, @Nullable ResourceLocation size) {
        private Key {
            Objects.requireNonNull(style, "style");
        }
    }

    private static final Map<Key, Placement> MODELS = new ConcurrentHashMap<>();

    /** Registers one model for every size of a style, using Create's own vertical convention. */
    public static void register(ResourceLocation style, ResourceLocation model) {
        register(style, model, BogeyRoots.CREATE_Y_OFFSET);
    }

    /** Registers a model for one specific size, using Create's own vertical convention. */
    public static void register(ResourceLocation style, BogeySizes.BogeySize size, ResourceLocation model) {
        register(style, size, model, BogeyRoots.CREATE_Y_OFFSET);
    }

    /** Registers one model for every size of a style. */
    public static void register(ResourceLocation style, ResourceLocation model, float yOffset) {
        MODELS.put(new Key(style, null), new Placement(model, yOffset));
    }

    /** Registers one model for one specific size; overrides the style-wide entry for that size only. */
    public static void register(ResourceLocation style, BogeySizes.BogeySize size,
                                ResourceLocation model, float yOffset) {
        Objects.requireNonNull(size, "size");
        MODELS.put(new Key(style, size.id()), new Placement(model, yOffset));
    }

    /**
     * Registers a multi-part model group for every size of a style.
     *
     * <p>Parts are positioned relative to the proxy root (the block centre plus {@code yOffset});
     * reference values are usually copied from the renderer the models came from, minus the main
     * body's offset, so the model group keeps the same internal layout.
     */
    public static void register(ResourceLocation style, List<ModelPart> parts, float yOffset) {
        MODELS.put(new Key(style, null), new Placement(parts, yOffset));
    }

    /** Registers a multi-part model group for one specific size. */
    public static void register(ResourceLocation style, BogeySizes.BogeySize size,
                                List<ModelPart> parts, float yOffset) {
        Objects.requireNonNull(size, "size");
        MODELS.put(new Key(style, size.id()), new Placement(parts, yOffset));
    }

    /**
     * Resolves the placement for a style/size pair.
     *
     * @param style the style to look up
     * @param size  the block's size, or {@code null} when the block is not a Create bogey block
     * @return the registered placement, or {@code null} when neither the exact nor the style-wide
     *         entry exists
     */
    public static @Nullable Placement resolve(BogeyStyle style, @Nullable BogeySizes.BogeySize size) {
        Objects.requireNonNull(style, "style");
        if (size != null) {
            Placement exact = MODELS.get(new Key(style.id, size.id()));
            if (exact != null) {
                return exact;
            }
        }
        return MODELS.get(new Key(style.id, null));
    }

    /** Drops every mapping; intended for tests and reload harnesses, not for live content. */
    public static void clear() {
        MODELS.clear();
    }

    /**
     * Resolves a placement from ids alone — for hosts that have the style only as NBT, which is the
     * case for a bogey mounted on a train ({@code CarriageBogey} stores the style id in its
     * {@code bogeyData}, not a {@code BogeyStyle} reference).
     *
     * @param styleId the committed style id, or {@code null} when the NBT carried none
     * @param sizeId  the size id, or {@code null} to look up the style-wide entry
     * @return the registered placement, or {@code null}
     */
    public static @Nullable Placement resolve(@Nullable ResourceLocation styleId, @Nullable ResourceLocation sizeId) {
        if (styleId == null) {
            return null;
        }
        if (sizeId != null) {
            Placement exact = MODELS.get(new Key(styleId, sizeId));
            if (exact != null) {
                return exact;
            }
        }
        return MODELS.get(new Key(styleId, null));
    }

    /**
     * Reads the style id Create stores in a bogey's NBT ({@code "BogeyStyle"}).
     *
     * @param bogeyData the tag handed to a bogey visual, possibly {@code null} or empty
     * @return the style id, or {@code null} when the tag does not carry one
     */
    public static @Nullable ResourceLocation styleIdOf(@Nullable CompoundTag bogeyData) {
        if (bogeyData == null || !bogeyData.contains(AbstractBogeyBlockEntity.BOGEY_STYLE_KEY)) {
            return null;
        }
        return NBTHelper.readResourceLocation(bogeyData, AbstractBogeyBlockEntity.BOGEY_STYLE_KEY);
    }

    private BogeyModels() {
    }
}
