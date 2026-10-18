package lib.kasuga.rendering.models.uml.dynamic.multiplexer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplexerTest {

    record TestContext(Map<String, String> properties, int power) implements Context {
        TestContext {
            properties = Map.copyOf(properties);
        }

        @Override
        public String property(String name) {
            return properties.get(name);
        }

        @Override
        public lib.kasuga.rendering.models.uml.dynamic.multiplexer.Blackboard data() {
            return lib.kasuga.rendering.models.uml.dynamic.multiplexer.Blackboard.empty();
        }
    }

    static final class TestVariant extends Variant<TestVariant> {
        TestVariant(String id) {
            super(id);
        }
    }

    private static TestContext input(int power) {
        return new TestContext(Map.of(), power);
    }

    @Test
    void transitionsBetweenVariantsWithCrossFade() {
        Multiplexer<TestContext, TestVariant> def = Multiplexer.define(TestVariant::new, mux -> {
            TestVariant off = mux.variant("off", v -> {});
            TestVariant on = mux.variant("on", v -> {});
            mux.transition(off, on, t -> t.when(in -> in.power() >= 1).crossFade(0.2f));
            mux.transition(on, off, t -> t.when(in -> in.power() < 1).crossFade(0.2f));
            mux.initial(off);
        });

        MuxState<TestVariant> state = def.newState();
        assertSame(def.variant("off"), state.current());
        assertFalse(state.inTransition());

        TestContext powered = input(15);
        def.advance(state, powered, 0.05f);
        assertTrue(state.inTransition());
        assertSame(def.variant("off"), state.from());
        assertSame(def.variant("on"), state.to());
        assertTrue(state.alpha() < 1f);

        for (int i = 0; i < 5; i++) {
            def.advance(state, powered, 0.05f);
        }
        assertFalse(state.inTransition());
        assertSame(def.variant("on"), state.current());
    }

    @Test
    void instantSwitchWhenCrossFadeZero() {
        Multiplexer<TestContext, TestVariant> def = Multiplexer.define(TestVariant::new, mux -> {
            TestVariant a = mux.variant("a", v -> {});
            TestVariant b = mux.variant("b", v -> {});
            mux.transition(a, b, t -> t.when(in -> in.power() > 0));
            mux.initial(a);
        });

        MuxState<TestVariant> state = def.newState();
        def.advance(state, input(5), 0.05f);
        assertSame(def.variant("b"), state.current());
        assertFalse(state.inTransition());
    }

    @Test
    void definitionIsStatelessAndShareable() {
        Multiplexer<TestContext, TestVariant> def = Multiplexer.define(TestVariant::new, mux -> {
            TestVariant x = mux.variant("x", v -> {});
            mux.initial(x);
        });

        MuxState<TestVariant> s1 = def.newState();
        MuxState<TestVariant> s2 = def.newState();
        assertNotSame(s1, s2);
        assertSame(def.variant("x"), s1.current());
        assertSame(def.variant("x"), s2.current());
    }

    @Test
    void selectReturnsTargetWithoutMutatingState() {
        Multiplexer<TestContext, TestVariant> def = Multiplexer.define(TestVariant::new, mux -> {
            TestVariant a = mux.variant("a", v -> {});
            TestVariant b = mux.variant("b", v -> {});
            mux.transition(a, b, t -> t.when(in -> in.power() > 0));
            mux.initial(a);
        });

        MuxState<TestVariant> state = def.newState();
        TestContext powered = input(5);
        assertSame(def.variant("b"), def.select(powered, state));
        assertSame(def.variant("a"), state.current());
    }
}
