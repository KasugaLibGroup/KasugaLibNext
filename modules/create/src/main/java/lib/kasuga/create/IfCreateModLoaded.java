package lib.kasuga.create;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import net.neoforged.fml.ModList;

public class IfCreateModLoaded implements Condition {
    @Override
    public boolean matches(ConditionContext context) {
        ModList modList = ModList.get();
        if (modList == null) {
            // Unit tests can load a second copy of the mod classes from the test classpath, where
            // FML has not installed its ModList. Disable that copy instead of failing with an NPE.
            context.fail("FML ModList is not initialized");
            return false;
        }
        if (!modList.isLoaded("create")) {
            context.fail("Create mod was not loaded");
            return false;
        }
        return true;
    }
}
