package lib.kasuga.create.client.registration;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackMaterialFactory;
import com.simibubi.create.content.trains.track.TrackModel;
import com.simibubi.create.foundation.data.CreateRegistrate;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lib.kasuga.create.CreateModBeans;
import lib.kasuga.create.registration.CreateClientBridge;
import lib.kasuga.create.registration.bogey.BogeyRendererHandle;
import lib.kasuga.create.registration.track.TrackModelDescriptor;
import lib.kasuga.inject.class_loader.BeanOnlyIn;
import lib.kasuga.registration.core.RegisterContextRegistry;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;

import java.util.function.Supplier;

@Context
@CreateModBeans
@BeanOnlyIn.Client
public final class ClientCreateRegistrationBridge implements CreateClientBridge {
    @Inject
    RegisterContextRegistry contextRegistry;

    @Inject
    ClientTrackMetadataIndex trackMetadataIndex;

    @Inject
    ClientBogeyRendererRegistry bogeyRendererRegistry;

    @PostConstruct
    void init() {
        contextRegistry.register(
                RegisterContextRegistry.Side.CLIENT,
                (registry, ignored) -> registry.setAttachment(CreateClientBridge.class, this)
        );
    }

    @Override
    public void configureTrackModels(TrackMaterialFactory factory, TrackModelDescriptor descriptor) {
        switch (descriptor.mode()) {
            case DEFAULT -> factory.defaultModels();
            case STANDARD -> factory.standardModels();
            case CUSTOM -> factory.customModels(
                    () -> () -> PartialModel.of(descriptor.tie()),
                    () -> () -> PartialModel.of(descriptor.leftSegment()),
                    () -> () -> PartialModel.of(descriptor.rightSegment())
            );
        }
    }

    @Override
    public void trackMaterialCommitted(TrackMaterial material, TrackModelDescriptor descriptor) {
        trackMetadataIndex.put(material, descriptor);
    }

    @Override
    public void trackBlockCreated(TrackBlock block) {
        CreateRegistrate.blockModel(() -> model -> model instanceof TrackModel ? model : new TrackModel(model))
                .accept(block);
    }

    @Override
    public void validateBogeyRenderer(BogeyRendererHandle rendererHandle) {
        if (bogeyRendererRegistry.get(rendererHandle).isEmpty()) {
            throw new IllegalStateException("Missing client bogey renderer: " + rendererHandle.id());
        }
    }

    @Override
    public void addBogeySize(
            BogeyStyle.Builder builder,
            BogeySizes.BogeySize size,
            Supplier<? extends AbstractBogeyBlock<?>> block,
            BogeyRendererHandle rendererHandle
    ) {
        ClientBogeyRendererRegistry.Definition definition = bogeyRendererRegistry.get(rendererHandle).orElseThrow();
        builder.size(
                size,
                block,
                () -> () -> new BogeyStyle.SizeRenderer(definition.renderer().get(), definition.visualizer())
        );
    }
}
