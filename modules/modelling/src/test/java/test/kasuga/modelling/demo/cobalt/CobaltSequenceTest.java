package test.kasuga.modelling.demo.cobalt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CobaltSequenceTest {
    @Test void recordMustBeInsertedBeforePlaybackAndRepeatedPlayDoesNotRewind() {
        var sequence = new CobaltSequence();
        assertFalse(sequence.play());
        assertFalse(sequence.insert());
        assertTrue(sequence.grab());
        assertTrue(sequence.insert());
        assertFalse(sequence.insert());
        sequence.tick(1);
        assertFalse(sequence.play());
        sequence.tick(1.1f);
        assertTrue(sequence.ready());
        assertTrue(sequence.play());
        sequence.tick(2);
        assertFalse(sequence.play());
        assertEquals(2, sequence.playbackTime(), 1e-6);
        sequence.tick(14);
        assertEquals(CobaltSequence.Phase.OCEAN, sequence.phase());
    }

    @Test void releaseAllowsPickingTheDiscUpAgain() {
        var sequence = new CobaltSequence();
        sequence.grab();
        assertTrue(sequence.release());
        assertFalse(sequence.release());
        assertTrue(sequence.grab());
    }

    @Test void waterInteractionStaysDisabledUntilTheOceanPhase() {
        var sequence = new CobaltSequence();
        assertFalse(sequence.waterInteractionEnabled());
        assertTrue(sequence.grab());
        assertFalse(sequence.waterInteractionEnabled());
        assertTrue(sequence.insert());
        sequence.tick(CobaltSequence.INSERT_SECONDS + .01f);
        assertFalse(sequence.waterInteractionEnabled());
        assertTrue(sequence.play());
        assertFalse(sequence.waterInteractionEnabled());
        sequence.tick(CobaltSequence.OPEN_SECONDS - .01f);
        assertFalse(sequence.waterInteractionEnabled());
        sequence.tick(.02f);
        assertTrue(sequence.waterInteractionEnabled());
    }
}
