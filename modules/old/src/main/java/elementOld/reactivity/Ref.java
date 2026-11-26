package lib.kasuga.elementOld.reactivity;

public class Ref<T> {
    private T value;

    public Ref(T initial) {
        this.value = initial;
    }

    public static <T> Ref<T> ref() {
        return ref(null);
    }
    public static <T> Ref<T> ref(T initial) {
        return new Ref<>(initial);
    }

    public T get() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
        this.dispatchChange();
    }

    private void dispatchChange() {

    }
}
