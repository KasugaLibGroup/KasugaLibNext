package lib.kasuga.rendering.models.mc.dynamic.fsm.cap;

import lib.kasuga.rendering.models.uml.dynamic.fsm.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.*;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationHost;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.EntityCapability;

/**
 * NeoForge capability constants exposing {@link AnimationHost} from blocks/entities. The host unwraps
 * to its {@code StateMachine<?>}; consumers cast to their owner type. Attach via the existing
 * {@code BlockEntityReg.addCapability} codegen modifier, or raw {@code RegisterCapabilitiesEvent} for entities.
 */
public final class AnimationCapabilities {

    public static final BlockCapability<AnimationHost, Void> MACHINE_BLOCK =
            BlockCapability.create(ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "animation_machine"),
                    AnimationHost.class, Void.class);

    public static final EntityCapability<AnimationHost, Void> MACHINE_ENTITY =
            EntityCapability.create(ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "animation_machine_entity"),
                    AnimationHost.class, Void.class);

    private AnimationCapabilities() {}
}
