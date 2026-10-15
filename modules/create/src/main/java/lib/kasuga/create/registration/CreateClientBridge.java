package lib.kasuga.create.registration;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackMaterialFactory;
import lib.kasuga.create.registration.bogey.BogeyRendererHandle;
import lib.kasuga.create.registration.track.TrackModelDescriptor;

import java.util.function.Supplier;

public interface CreateClientBridge {
    void configureTrackModels(TrackMaterialFactory factory, TrackModelDescriptor descriptor);

    void trackMaterialCommitted(TrackMaterial material, TrackModelDescriptor descriptor);

    void trackBlockCreated(TrackBlock block);

    void validateBogeyRenderer(BogeyRendererHandle rendererHandle);

    void addBogeySize(
            BogeyStyle.Builder builder,
            BogeySizes.BogeySize size,
            Supplier<? extends AbstractBogeyBlock<?>> block,
            BogeyRendererHandle rendererHandle
    );

    static CreateClientBridge noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final CreateClientBridge INSTANCE = new CreateClientBridge() {
            @Override
            public void configureTrackModels(TrackMaterialFactory factory, TrackModelDescriptor descriptor) {
                switch (descriptor.mode()) {
                    case DEFAULT -> factory.defaultModels();
                    case STANDARD -> factory.standardModels();
                    case CUSTOM -> {
                        // Custom PartialModels exist only on the physical client.
                    }
                }
            }

            @Override
            public void trackMaterialCommitted(TrackMaterial material, TrackModelDescriptor descriptor) {
            }

            @Override
            public void trackBlockCreated(TrackBlock block) {
            }

            @Override
            public void validateBogeyRenderer(BogeyRendererHandle rendererHandle) {
            }

            @Override
            public void addBogeySize(
                    BogeyStyle.Builder builder,
                    BogeySizes.BogeySize size,
                    Supplier<? extends AbstractBogeyBlock<?>> block,
                    BogeyRendererHandle rendererHandle
            ) {
                builder.size(size, block, () -> () -> null);
            }
        };

        private NoopHolder() {
        }
    }
}
