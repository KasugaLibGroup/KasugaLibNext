package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeInputStoreTest {

    private static final InputKey<Boolean> POWERED = new InputKey<>("machine/powered", Boolean.class);
    private static final InputKey<Double> SPEED = new InputKey<>("machine/speed", Double.class);
    private static final InputTriggerKey START = new InputTriggerKey("machine/start");

    @Test
    void aFrozenSnapshotDoesNotObserveLaterWrites() {
        ScopeInputStore store = new ScopeInputStore();
        AnimationInputPort input = store.inputPort();

        input.write(POWERED, true);
        ScopeInputSnapshot first = store.freeze();
        input.write(POWERED, false);
        ScopeInputSnapshot second = store.freeze();

        assertTrue(first.value(POWERED).orElseThrow());
        assertFalse(second.value(POWERED).orElseThrow());
    }

    @Test
    void aTriggerIsConsumedOnlyWhenItsSnapshotCommits() {
        ScopeInputStore store = new ScopeInputStore();
        store.inputPort().trigger(START);

        ScopeInputSnapshot attempted = store.freeze();
        ScopeInputSnapshot retry = store.freeze();

        assertTrue(attempted.triggered(START));
        assertTrue(retry.triggered(START));

        store.commit(attempted);
        ScopeInputSnapshot afterCommit = store.freeze();

        assertFalse(afterCommit.triggered(START));
    }

    @Test
    void writesMadeWhileAFrozenSnapshotIsProcessingRemainForTheNextStep() {
        ScopeInputStore store = new ScopeInputStore();
        store.inputPort().write(SPEED, 1.0);
        ScopeInputSnapshot first = store.freeze();

        store.inputPort().write(SPEED, 2.0);
        store.inputPort().trigger(START);
        store.commit(first);
        ScopeInputSnapshot second = store.freeze();

        assertEquals(1.0, first.value(SPEED).orElseThrow(), 1e-9);
        assertEquals(2.0, second.value(SPEED).orElseThrow(), 1e-9);
        assertTrue(second.triggered(START));
    }

    @Test
    void typedKeysCannotReturnAValueOfAnotherType() {
        ScopeInputStore store = new ScopeInputStore();
        store.inputPort().write(POWERED, true);

        ScopeInputSnapshot snapshot = store.freeze();

        assertTrue(snapshot.value(POWERED).isPresent());
        assertTrue(snapshot.value(SPEED).isEmpty());
    }
}
