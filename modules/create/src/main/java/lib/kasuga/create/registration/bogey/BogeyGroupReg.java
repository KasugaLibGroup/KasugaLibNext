package lib.kasuga.create.registration.bogey;

import com.simibubi.create.AllBogeyStyles;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import lib.kasuga.create.registration.CreateRegistrationEvent;
import lib.kasuga.create.registration.CreateRegistrationStages;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.core.ResourceLocationModifiers;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class BogeyGroupReg extends Reg<BogeyGroupReg, BogeyStyle> {
    private final String name;
    private final String cycleGroup;
    private final List<SizeDescriptor> sizes = new ArrayList<>();
    private Component displayName;
    private Supplier<SoundEvent> soundEvent;
    private ParticleOptions contactParticle;
    private ParticleOptions smokeParticle;
    private CompoundTag defaultData;
    private BogeyStyle delegate;

    public BogeyGroupReg(String name, String cycleGroup) {
        this.name = Objects.requireNonNull(name, "name");
        this.cycleGroup = Objects.requireNonNull(cycleGroup, "cycleGroup");
    }

    public BogeyGroupReg displayName(Component displayName) {
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        return this;
    }

    public BogeyGroupReg translationKey(String translationKey) {
        return displayName(Component.translatable(translationKey));
    }

    public BogeyGroupReg sound(Supplier<SoundEvent> soundEvent) {
        this.soundEvent = Objects.requireNonNull(soundEvent, "soundEvent");
        return this;
    }

    public BogeyGroupReg contactParticle(ParticleOptions contactParticle) {
        this.contactParticle = Objects.requireNonNull(contactParticle, "contactParticle");
        return this;
    }

    public BogeyGroupReg smokeParticle(ParticleOptions smokeParticle) {
        this.smokeParticle = Objects.requireNonNull(smokeParticle, "smokeParticle");
        return this;
    }

    public BogeyGroupReg defaultData(CompoundTag defaultData) {
        this.defaultData = Objects.requireNonNull(defaultData, "defaultData").copy();
        return this;
    }

    public BogeyGroupReg bogey(
            BogeySizeReg size,
            BogeyBlockReg<?> block,
            BogeyRendererHandle rendererHandle
    ) {
        sizes.add(new SizeDescriptor(
                Objects.requireNonNull(size, "size"),
                Objects.requireNonNull(block, "block"),
                Objects.requireNonNull(rendererHandle, "rendererHandle")
        ));
        return this;
    }

    @Override
    public void register(RegisterContext<?> context) {
        context.onStage(CreateRegistrationStages.PREPARE, event -> {
            ResourceLocation id = resolveId(name);
            ResourceLocation cycleId = resolveId(cycleGroup);
            event.submit(
                    CreateRegistrationEvent.Phase.BOGEY_STYLE,
                    id,
                    this,
                    () -> validate(event, id),
                    () -> commit(event, id, cycleId)
            );
        });
    }

    private void validate(CreateRegistrationEvent event, ResourceLocation id) {
        if (delegate != null) {
            throw new IllegalStateException("Bogey style already committed: " + id);
        }
        if (sizes.isEmpty()) {
            throw new IllegalStateException("Bogey style has no sizes: " + id);
        }
        if (AllBogeyStyles.BOGEY_STYLES.containsKey(id)) {
            throw new IllegalStateException("Bogey style ID is already registered: " + id);
        }
        HashSet<ResourceLocation> configuredSizes = new HashSet<>();
        for (SizeDescriptor descriptor : sizes) {
            ResourceLocation sizeId = descriptor.size().resolveId();
            if (!configuredSizes.add(sizeId)) {
                throw new IllegalStateException("Bogey style " + id + " configures size twice: " + sizeId);
            }
            if (!descriptor.size().isCommitted()
                    && !event.contains(CreateRegistrationEvent.Phase.BOGEY_SIZE, sizeId, descriptor.size())) {
                throw new IllegalStateException("Bogey style " + id + " refers to an unregistered size: " + sizeId);
            }
            if (descriptor.block().getSizeReg() != descriptor.size()) {
                throw new IllegalStateException("Bogey style " + id + " binds a block to the wrong size");
            }
            ResourceLocation blockId = descriptor.block().resolveId();
            if (!descriptor.block().isRegistered()
                    && !event.contains(CreateRegistrationEvent.Phase.BOGEY_BLOCK, blockId, descriptor.block())) {
                throw new IllegalStateException("Bogey style " + id + " refers to an unregistered block: " + blockId);
            }
            event.clientBridge().validateBogeyRenderer(descriptor.rendererHandle());
        }
    }

    private void commit(CreateRegistrationEvent event, ResourceLocation id, ResourceLocation cycleId) {
        BogeyStyle.Builder builder = new BogeyStyle.Builder(id, cycleId);
        if (displayName != null) {
            builder.displayName(displayName);
        }
        if (soundEvent != null) {
            builder.soundEvent(soundEvent);
        }
        if (contactParticle != null) {
            builder.contactParticle(contactParticle);
        }
        if (smokeParticle != null) {
            builder.smokeParticle(smokeParticle);
        }
        if (defaultData != null) {
            builder.defaultData(defaultData.copy());
        }
        for (SizeDescriptor descriptor : sizes) {
            event.clientBridge().addBogeySize(
                    builder,
                    descriptor.size().getEntry(),
                    descriptor.block()::getEntry,
                    descriptor.rendererHandle()
            );
        }

        BogeyStyle style = builder.build();
        BogeyMetadataIndex.put(style, new BogeyMetadataIndex.StyleMetadata(id, cycleId));
        delegate = style;
    }

    private ResourceLocation resolveId(String path) {
        ResourceLocation id = transform(
                ResourceLocationModifiers.ID,
                ResourceLocation.withDefaultNamespace(path)
        );
        return applyProperties(ResourceLocation.class, id);
    }

    @Override
    public BogeyStyle getEntry() {
        if (delegate == null) {
            throw new IllegalStateException("Bogey style has not been committed: " + name);
        }
        return delegate;
    }

    private record SizeDescriptor(
            BogeySizeReg size,
            BogeyBlockReg<?> block,
            BogeyRendererHandle rendererHandle
    ) {
    }
}
