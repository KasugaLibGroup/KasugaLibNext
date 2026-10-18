package lib.kasuga.rendering.models.uml.dynamic.fsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Build-time fail-fast from doc/fsm-design-review.md (block 1.1): duplicate state/layer ids and cross-layer
 * transition references throw at build time instead of degrading into dead/silent machines.
 */
class BuildValidationTest {

    @Test
    void duplicateStateIdThrows() {
        assertThrows(IllegalStateException.class, () ->
                StateMachine.builder(new Object())
                        .layer("l", layer -> {
                            layer.state("a");
                            layer.state("a");
                        })
                        .build());
    }

    @Test
    void duplicateLayerIdThrows() {
        assertThrows(IllegalStateException.class, () ->
                StateMachine.builder(new Object())
                        .layer("l", layer -> layer.state("a"))
                        .layer("l", layer -> layer.state("b"))
                        .build());
    }

    @Test
    void crossLayerTransitionThrows() {
        State<Object> foreign = new State<>("foreign"); // belongs to no layer in the machine below
        assertThrows(IllegalArgumentException.class, () ->
                StateMachine.builder(new Object())
                        .layer("l", layer -> {
                            State<Object> a = layer.state("a");
                            layer.transition("t", a, foreign); // 'to' is not in this layer
                        })
                        .build());
    }
}
