package lib.kasuga.rendering.models.uml.backend;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ElementChangesTest {
    @Test void independentReadersRetainChangesAcrossRepeatedWritesAndLongAbsences() {
        var changes = new ElementChanges(4);
        var first = new BitSet(); var late = new BitSet();
        changes.mark(1); long version = changes.collectSince(0,first);
        for (int i=0;i<1000;i++) changes.mark(2);
        changes.mark(3);
        changes.collectSince(version,first); changes.collectSince(0,late);
        assertEquals(BitSet.valueOf(new long[]{12}),first);
        assertEquals(BitSet.valueOf(new long[]{14}),late);
        changes.collectSince(changes.version(),late); assertTrue(late.isEmpty());
    }
}
