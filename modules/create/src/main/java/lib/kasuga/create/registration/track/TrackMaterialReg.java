package lib.kasuga.create.registration.track;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackMaterialFactory;
import lib.kasuga.create.registration.CreateRegistrationEvent;
import lib.kasuga.create.registration.CreateRegistrationStages;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.core.ResourceLocationModifiers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public final class TrackMaterialReg extends Reg<TrackMaterialReg, TrackMaterial> {
    private final String name;
    private String langName;
    private Supplier<? extends TrackBlock> blockSupplier;
    private ResourceLocation particle;
    private TrackMaterial.TrackType trackType = TrackMaterial.TrackType.STANDARD;
    private ItemLike[] sleepers;
    private ItemLike[] rails;
    private boolean replaceable;
    private TrackModelDescriptor models = TrackModelDescriptor.defaultModels();
    private UnaryOperator<Vec3> railOffset = vector -> vector.scale(.965f);
    private TrackMaterial delegate;

    public TrackMaterialReg(String name) {
        this.name = Objects.requireNonNull(name, "name");
        this.langName = name;
    }

    public TrackMaterialReg lang(String langName) {
        this.langName = Objects.requireNonNull(langName, "langName");
        return this;
    }

    public TrackMaterialReg block(Supplier<? extends TrackBlock> blockSupplier) {
        this.blockSupplier = Objects.requireNonNull(blockSupplier, "blockSupplier");
        return this;
    }

    public TrackMaterialReg block(TrackReg<?> track) {
        return block(track::getEntry);
    }

    public TrackMaterialReg particle(ResourceLocation particle) {
        this.particle = Objects.requireNonNull(particle, "particle");
        return this;
    }

    public TrackMaterialReg trackType(TrackMaterial.TrackType trackType) {
        this.trackType = Objects.requireNonNull(trackType, "trackType");
        return this;
    }

    public TrackMaterialReg trackType(
            ResourceLocation id,
            TrackMaterial.TrackType.TrackBlockFactory factory
    ) {
        return trackType(new TrackMaterial.TrackType(id, factory));
    }

    public TrackMaterialReg sleeper(ItemLike... sleepers) {
        this.sleepers = sleepers.clone();
        return this;
    }

    public TrackMaterialReg rails(ItemLike... rails) {
        this.rails = rails.clone();
        return this;
    }

    public TrackMaterialReg replaceable(boolean replaceable) {
        this.replaceable = replaceable;
        return this;
    }

    public TrackMaterialReg defaultModels() {
        this.models = TrackModelDescriptor.defaultModels();
        return this;
    }

    public TrackMaterialReg standardModels() {
        this.models = TrackModelDescriptor.standardModels();
        return this;
    }

    public TrackMaterialReg customModels(
            ResourceLocation tie,
            ResourceLocation leftSegment,
            ResourceLocation rightSegment
    ) {
        this.models = TrackModelDescriptor.custom(tie, leftSegment, rightSegment);
        return this;
    }

    public TrackMaterialReg simpleTrackModelOffset(float halfDistance) {
        this.railOffset = vector -> vector.scale(halfDistance);
        return this;
    }

    public TrackMaterialReg trackModelOffset(UnaryOperator<Vec3> railOffset) {
        this.railOffset = Objects.requireNonNull(railOffset, "railOffset");
        return this;
    }

    @Override
    public void register(RegisterContext<?> context) {
        context.onStage(CreateRegistrationStages.PREPARE, event -> {
            ResourceLocation id = resolveId();
            event.submit(
                    CreateRegistrationEvent.Phase.TRACK_MATERIAL,
                    id,
                    this,
                    () -> validate(id),
                    () -> commit(event, id)
            );
        });
    }

    private void validate(ResourceLocation id) {
        if (delegate != null) {
            throw new IllegalStateException("Track material already committed: " + id);
        }
        if (blockSupplier == null) {
            throw new IllegalStateException("Track material " + id + " has no track block supplier");
        }
        if (particle == null) {
            throw new IllegalStateException("Track material " + id + " has no particle texture");
        }
        if (TrackMaterial.ALL.containsKey(id)) {
            throw new IllegalStateException("Track material ID is already registered: " + id);
        }
    }

    private void commit(CreateRegistrationEvent event, ResourceLocation id) {
        TrackMaterialFactory factory = TrackMaterialFactory.make(id)
                .lang(langName)
                .block(() -> () -> blockSupplier.get())
                .particle(particle)
                .trackType(trackType);
        if (sleepers != null) {
            factory.sleeper(sleepers);
        }
        if (rails != null) {
            factory.rails(rails);
        }
        event.clientBridge().configureTrackModels(factory, models);

        TrackMaterial material = factory.build();
        TrackMaterialMetadata metadata = new TrackMaterialMetadata(id, replaceable, railOffset);
        TrackMaterialMetadataIndex.put(material, metadata);
        delegate = material;
        event.clientBridge().trackMaterialCommitted(material, models);
    }

    private ResourceLocation resolveId() {
        ResourceLocation id = transform(
                ResourceLocationModifiers.ID,
                ResourceLocation.withDefaultNamespace(name)
        );
        return applyProperties(ResourceLocation.class, id);
    }

    @Override
    public TrackMaterial getEntry() {
        if (delegate == null) {
            throw new IllegalStateException("Track material has not been committed: " + name);
        }
        return delegate;
    }

    public String getName() {
        return name;
    }
}
