package lib.kasuga.rendering.models.mc.dynamic.fsm;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lib.kasuga.registration.data_driven.reload.ReloadHandlerRegistry;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;

/**
 * Registers the modelling reload handlers with {@link ReloadHandlerRegistry} at context startup, so the
 * reload orchestrator routes the two FSM reload types through the registry instead of hard-coded keys.
 *
 * <p>The order is load-bearing: state machines are registered <strong>before</strong> clips, matching
 * the orchestrator's historical dispatch/register order (definitions first, clips second) so last-wins
 * across a mixed file is unchanged. Both handlers are bound to the process-wide {@link FsmRegistries#GLOBAL}
 * buckets, the same ones the process-wide orchestrator reads.
 *
 * <p>Registering the same type name again replaces the handler in place (the registry keeps its
 * position), so a second {@code @PostConstruct} in tests is idempotent.
 */
@Context
public final class FsmReloadHandlerRegistrar {

    @PostConstruct
    public void init() {
        FsmDefinitions definitions = FsmRegistries.GLOBAL.definitions();
        FsmAnimationClips clips = FsmRegistries.GLOBAL.clips();
        ReloadHandlerRegistry.register(new FsmReloadHandler(definitions, clips));
        ReloadHandlerRegistry.register(new FsmClipsReloadHandler(clips));
    }
}
