package lib.kasuga.elementOld.reactivity;

import java.util.function.Supplier;

public class Computed<T> {
    private Supplier<T> computedSupplier;

    public Computed(Supplier<T> supplier) {

    }

    public static <T> Computed<T> computed(Supplier<T> supplier) {
        return new Computed<>(supplier);
    }
}
