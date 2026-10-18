package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.mc.multiplexer.McContext;
import lib.kasuga.rendering.models.mc.multiplexer.McVariant;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Blackboard;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Multiplexer;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.MuxState;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.SelectorPredicates;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the typed extension channels: {@link StateVar}-keyed values on {@link StateContext} (FSM), and
 * custom {@link Blackboard} channels on {@link McContext} (multiplexer) — no framework type edited to add data.
 */
class ExtensibilityTest {

    static final StateVar<Float> SPEED = StateVar.of(id("test/speed"), Float.class, Codec.FLOAT, 0f);
    static final StateVar<String> MODE = StateVar.of(id("test/mode"), String.class, Codec.STRING, "idle");
    static final StateVar<String> NOTE = StateVar.of(id("test/note"), String.class, Codec.STRING, "");
    static final Blackboard.Key<Boolean> ARMED = Blackboard.Key.of("armed");

    static final class Actor {}

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_lib", path);
    }

    private static Id id(String path) {
        return Id.fromNamespaceAndPath("kasuga_lib", path);
    }

    @Test
    void contextStateMapTyped() {
        Actor owner = new Actor();
        StateMachine<Actor> machine = StateMachine.<Actor>builder(owner)
                .layer("l", layer -> {
                    State<Actor> a = layer.state("a");
                    State<Actor> b = layer.state("b");
                    layer.initial(a);
                    layer.transition("a2b", a, b)
                            .when(ctx -> ctx.get(SPEED) > 0.5f)
                            .onFire(ctx -> ctx.set(MODE, "fast"));
                })
                .build();

        // defaults read through when unset
        assertEquals(0f, machine.vars().get(SPEED));

        machine.mutableVars().set(SPEED, 0.6f);
        machine.tick();

        assertEquals("b", machine.layer("l").active().id());
        assertEquals("fast", machine.vars().get(MODE));

        machine.mutableVars().set(NOTE, "hello");
        assertEquals("hello", machine.vars().get(NOTE));
    }

    @Test
    void multiplexerCustomChannel() {
        Multiplexer<McContext, McVariant> def = Multiplexer.define(McVariant::new, mux -> {
            McVariant off = mux.variant("off", v -> v.model(rl("off")));
            McVariant on = mux.variant("on", v -> v.model(rl("on")));
            mux.transition(off, on, t -> t.when(SelectorPredicates.dataFlag(ARMED)));
            mux.transition(on, off, t -> t.when(in -> !Boolean.TRUE.equals(in.data().get(ARMED))));
            mux.initial(off);
        });

        MuxState<McVariant> state = def.newState();
        McContext input = new McContext(
                Map.of("powered", "false"), List.of(), 0, 0L, Set.of());

        input.data().put(ARMED, true);
        def.advance(state, input, 0f);
        assertSame(def.variant("on"), state.current());

        input.data().put(ARMED, false);
        def.advance(state, input, 0f);
        assertSame(def.variant("off"), state.current());
    }
}
