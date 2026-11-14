package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.context.BuildContext;

/**
 * One session-scoped hook that runs after every selected definition has been applied.
 */
@FunctionalInterface
public interface BuildFinalizer {
    Object afterBuild(BuildContext context, PreparedContent content);
}
