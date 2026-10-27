package lib.kasuga.create.proxy;

import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

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
     * What to draw for one style/size.
     *
     * @param model   the model resource location
     * @param yOffset vertical offset added to the block centre, in blocks
     */
    public record Placement(ResourceLocation model, float yOffset) {
        public Placement {
            Objects.requireNonNull(model, "model");
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

    /** Registers a model for one specific size; overrides the style-wide entry for that size only. */
    public static void register(ResourceLocation style, BogeySizes.BogeySize size,
                                ResourceLocation model, float yOffset) {
        Objects.requireNonNull(size, "size");
        MODELS.put(new Key(style, size.id()), new Placement(model, yOffset));
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

    private BogeyModels() {
    }
}
