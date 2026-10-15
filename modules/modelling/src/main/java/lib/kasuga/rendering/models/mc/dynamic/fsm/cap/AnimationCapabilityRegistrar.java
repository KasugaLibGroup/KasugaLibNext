package lib.kasuga.rendering.models.mc.dynamic.fsm.cap;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Registers {@link AnimationCapabilities} with NeoForge for every {@link BlockEntityType}
 * and {@link EntityType}. The generic {@link BlockEntityAnimationProvider} and
 * {@link EntityAnimationProvider} only return a value when the owner implements
 * {@link lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationHost}, so this is safe.
 */
@Context
public final class AnimationCapabilityRegistrar {

    @Inject
    @Named("modEventBus")
    IEventBus modEventBus;

    @PostConstruct
    public void init() {
        modEventBus.addListener(this::onRegisterCapabilities);
    }

    private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            event.registerBlockEntity(
                    AnimationCapabilities.MACHINE_BLOCK,
                    type,
                    BlockEntityAnimationProvider.INSTANCE
            );
        }
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            event.registerEntity(
                    AnimationCapabilities.MACHINE_ENTITY,
                    type,
                    EntityAnimationProvider.INSTANCE
            );
        }
    }
}
