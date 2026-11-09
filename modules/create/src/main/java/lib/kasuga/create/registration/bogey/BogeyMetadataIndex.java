package lib.kasuga.create.registration.bogey;

import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.WeakHashMap;

public final class BogeyMetadataIndex {
    public record SizeMetadata(ResourceLocation id, float wheelRadius) {
    }

    public record StyleMetadata(ResourceLocation id, ResourceLocation cycleGroup) {
    }

    private BogeyMetadataIndex() {
    }

    private static final class Holder {
        private static final WeakHashMap<BogeySizes.BogeySize, SizeMetadata> SIZES = new WeakHashMap<>();
        private static final WeakHashMap<BogeyStyle, StyleMetadata> STYLES = new WeakHashMap<>();
    }

    static void put(BogeySizes.BogeySize size, SizeMetadata metadata) {
        synchronized (Holder.SIZES) {
            Holder.SIZES.put(size, metadata);
        }
    }

    static void put(BogeyStyle style, StyleMetadata metadata) {
        synchronized (Holder.STYLES) {
            Holder.STYLES.put(style, metadata);
        }
    }

    public static Optional<SizeMetadata> get(BogeySizes.BogeySize size) {
        synchronized (Holder.SIZES) {
            return Optional.ofNullable(Holder.SIZES.get(size));
        }
    }

    public static Optional<StyleMetadata> get(BogeyStyle style) {
        synchronized (Holder.STYLES) {
            return Optional.ofNullable(Holder.STYLES.get(style));
        }
    }

    public static void clear() {
        synchronized (Holder.SIZES) {
            Holder.SIZES.clear();
        }
        synchronized (Holder.STYLES) {
            Holder.STYLES.clear();
        }
    }
}
