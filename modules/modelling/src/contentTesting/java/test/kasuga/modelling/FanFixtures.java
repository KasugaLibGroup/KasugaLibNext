package test.kasuga.modelling;

import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import net.minecraft.resources.ResourceLocation;

/**
 * Shared constants of the fan fixtures. Kept out of {@link FanBlockEntity} so deleting or rewriting the
 * block entity does not break the definition registration in {@link ModellingContentTest}.
 */
public final class FanFixtures {

    /** The state machine definition ({@code kasuga_lib:fan_machine}, registered by {@link ModellingContentTest}). */
    public static final Id MACHINE_ID = Id.fromNamespaceAndPath("kasuga_lib", "fan_machine");

    /** The clip every gear state references ({@code kasuga_lib:fan_fsm}). */
    public static final Id CLIP_ID = Id.fromNamespaceAndPath("kasuga_lib", "fan_fsm");

    /** The fan geometry ({@code models/be/test_fan_be.bbmodel}). */
    public static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/be/test_fan_be.bbmodel");

    /** Layer state ids in active-index order — index = gear 0..3. */
    public static final String[] GEAR_STATE_IDS = {"off", "g1", "g2", "g3"};

    /** The single external trigger (right-click): cycles {@code off→g1→g2→g3→off}. */
    public static final StateVar<Boolean> CYCLE = StateVar.trigger(Id.fromNamespaceAndPath("kasuga_lib", "fan/cycle"));

    private FanFixtures() {
    }
}
