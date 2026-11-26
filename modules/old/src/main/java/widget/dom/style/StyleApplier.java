package lib.kasuga.widget.dom.style;

public interface StyleApplier<R, T> {
    public R applyValue(R original, T value);
}
