package lib.kasuga.rendering.models.uml.dynamic.physics.box3d;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class NativeBox3DAvailabilityTest {
    @Test
    void unavailableLibraryCanDisablePhysicsWithoutThrowing() {
        assumeFalse(NativeBox3D.available(), "requires a runtime without the Box3D library");

        assertFalse(NativeBox3D.availableOrWarn());
        assertFalse(NativeBox3D.availableOrWarn(), "repeated checks remain harmless");
        assertNotNull(NativeBox3D.loadFailure());

        ModelInstance instance = ModelInstanceFixture.minimal();
        assertNull(instance.enablePhysics(), "optional physics must degrade to a disabled result");
        assertDoesNotThrow(() -> instance.tick(1f / 60f),
                "the ordinary model tick loop must keep working without Box3D");
    }
}
