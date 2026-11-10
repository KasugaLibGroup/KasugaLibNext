package lib.kasuga.rendering.models.mc.scene;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpongeSchematicTest {
    @Test void paletteIndicesCanUseMultipleBytes() {
        assertArrayEquals(new int[]{0,127,128,16384},SpongeSchematicData.decodeVarints(new byte[]{0,127,(byte)128,1,(byte)128,(byte)128,1},4));
    }
    @Test void truncatedOverflowAndTrailingDataAreRejected() {
        assertThrows(IllegalArgumentException.class,()->SpongeSchematicData.decodeVarints(new byte[]{(byte)128},1));
        assertThrows(IllegalArgumentException.class,()->SpongeSchematicData.decodeVarints(new byte[]{(byte)255,(byte)255,(byte)255,(byte)255,127},1));
        assertThrows(IllegalArgumentException.class,()->SpongeSchematicData.decodeVarints(new byte[]{0,0},1));
        assertThrows(IllegalArgumentException.class,()->SpongeSchematicData.decodeVarints(new byte[]{},1));
    }
}
