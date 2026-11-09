package lib.kasuga.create.registration.track;

import com.simibubi.create.content.trains.track.TrackMaterial;

import java.util.Optional;
import java.util.WeakHashMap;

public final class TrackMaterialMetadataIndex {
    private TrackMaterialMetadataIndex() {
    }

    private static final class Holder {
        private static final WeakHashMap<TrackMaterial, TrackMaterialMetadata> INDEX = new WeakHashMap<>();
    }

    public static void put(TrackMaterial material, TrackMaterialMetadata metadata) {
        synchronized (Holder.INDEX) {
            Holder.INDEX.put(material, metadata);
        }
    }

    public static Optional<TrackMaterialMetadata> get(TrackMaterial material) {
        synchronized (Holder.INDEX) {
            return Optional.ofNullable(Holder.INDEX.get(material));
        }
    }

    public static void remove(TrackMaterial material) {
        synchronized (Holder.INDEX) {
            Holder.INDEX.remove(material);
        }
    }

    public static void clear() {
        synchronized (Holder.INDEX) {
            Holder.INDEX.clear();
        }
    }

    public static Class<?> backingMapType() {
        return Holder.INDEX.getClass();
    }
}
