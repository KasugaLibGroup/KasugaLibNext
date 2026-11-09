package lib.kasuga.rendering.models.mc.proxies;

import org.jetbrains.annotations.Nullable;

/** Preserves the first cleanup failure while retaining later failures as suppressed. */
final class CleanupFailures {

    private CleanupFailures() {
    }

    static <T extends Throwable> @Nullable T accumulate(@Nullable T current, T next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }
}
