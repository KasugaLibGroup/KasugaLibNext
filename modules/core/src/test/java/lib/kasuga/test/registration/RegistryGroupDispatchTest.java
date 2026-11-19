package lib.kasuga.test.registration;

import lib.kasuga.registration.Reg;
import lib.kasuga.registration.RegistryGroup;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.stages.RegisterStageContext;
import lib.kasuga.registration.stages.RegistrationStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryGroupDispatchTest {
    @Test
    void disabledGroupSkipsItsEntireSubtree() {
        RegistryGroup root = new RegistryGroup();
        RegistryGroup child = new RegistryGroup().setParent(root);
        CountingReg registration = new CountingReg().setParent(child);
        boolean[] enabled = {false};
        child.dispatchWhen(() -> enabled[0]);
        RegisterContext<RegisterStageContext> context =
                new RegisterContext<>(RegistrationStage.REGISTER_EVENT, null);

        root.dispatchRegister(context);
        assertEquals(0, registration.dispatches);
        assertFalse(child.isDispatchEnabled());
        enabled[0] = true;
        assertTrue(child.isDispatchEnabled());
        root.dispatchRegister(context);
        assertEquals(1, registration.dispatches);
    }

    private static final class CountingReg extends Reg<CountingReg, Void> {
        private int dispatches;

        @Override
        public Void getEntry() {
            return null;
        }

        @Override
        public void register(RegisterContext<?> context) {
            dispatches++;
        }
    }
}
