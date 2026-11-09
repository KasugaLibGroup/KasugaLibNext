package lib.kasuga.create.client.registration;

import com.simibubi.create.content.trains.track.TrackMaterial;
import io.micronaut.context.annotation.Context;
import lib.kasuga.create.CreateModBeans;
import lib.kasuga.create.registration.track.TrackModelDescriptor;
import lib.kasuga.inject.class_loader.BeanOnlyIn;

import java.util.Optional;
import java.util.WeakHashMap;

@Context
@CreateModBeans
@BeanOnlyIn.Client
public final class ClientTrackMetadataIndex {
    private final WeakHashMap<TrackMaterial, TrackModelDescriptor> index = new WeakHashMap<>();

    public void put(TrackMaterial material, TrackModelDescriptor metadata) {
        synchronized (index) {
            index.put(material, metadata);
        }
    }

    public Optional<TrackModelDescriptor> get(TrackMaterial material) {
        synchronized (index) {
            return Optional.ofNullable(index.get(material));
        }
    }

    public void remove(TrackMaterial material) {
        synchronized (index) {
            index.remove(material);
        }
    }

    public void clear() {
        synchronized (index) {
            index.clear();
        }
    }
}
