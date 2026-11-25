package lib.kasuga.core.compat;

import java.lang.reflect.Method;
import java.lang.reflect.InaccessibleObjectException;
import java.util.Objects;

/** A cached, lazy probe for an optional compatibility capability. */
public final class CompatCapability<T> {
    @FunctionalInterface
    public interface Probe<T> {
        T run() throws ReflectiveOperationException;
    }

    public record Result<T>(boolean available, T value, String reason, Throwable cause) {
        public Result {
            if (available && value == null) {
                throw new IllegalArgumentException("Available capability result must have a value");
            }
        }

        private static <T> Result<T> available(T value) {
            return new Result<>(true, value, "Available", null);
        }

        private static <T> Result<T> unavailable(String reason, Throwable cause) {
            return new Result<>(false, null, reason, cause);
        }
    }

    private final String name;
    private final Probe<T> probe;
    private volatile Result<T> result;

    public CompatCapability(String name, Probe<T> probe) {
        this.name = Objects.requireNonNull(name, "name");
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    public boolean isAvailable() {
        return result().available();
    }

    public Result<T> result() {
        Result<T> cached = result;
        if (cached != null) return cached;
        synchronized (this) {
            cached = result;
            if (cached == null) {
                cached = runProbe();
                result = cached;
            }
        }
        return cached;
    }

    private Result<T> runProbe() {
        try {
            T value = probe.run();
            if (value == null) {
                IllegalStateException failure = new IllegalStateException("Probe returned null");
                return Result.unavailable(name + ": " + describe(failure), failure);
            }
            return Result.available(value);
        } catch (ReflectiveOperationException | InaccessibleObjectException | LinkageError | SecurityException failure) {
            return Result.unavailable(name + ": " + describe(failure), failure);
        }
    }

    /** Loads an optional class without initialization, reporting absence as a normal incompatibility. */
    public static Class<?> requireClass(String className, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(className, false, loader);
    }

    /** Finds a public method and verifies its exact return type to detect signature drift. */
    public static Method requireMethod(Class<?> owner, String methodName, Class<?> returnType,
                                       Class<?>... parameterTypes) throws ReflectiveOperationException {
        Method method = owner.getMethod(methodName, parameterTypes);
        if (method.getReturnType() != returnType) {
            throw new NoSuchMethodException(owner.getName() + "." + methodName
                    + " return type changed: expected " + returnType.getTypeName()
                    + ", found " + method.getReturnType().getTypeName());
        }
        return method;
    }

    private static String describe(Throwable failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
        return detail;
    }
}
