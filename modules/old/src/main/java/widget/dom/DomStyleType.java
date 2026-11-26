package lib.kasuga.widget.dom;

import lib.kasuga.widget.dom.style.ApplicableStyle;
import org.jetbrains.annotations.NotNull;

public interface DomStyleType<T, R> extends ApplicableStyle<T, R, DomElement> {
    default int getPriority() {
        return 0;
    }
    @Override
    default int compareTo(@NotNull ApplicableStyle<?, ?, ?> o) {
        if(o instanceof DomStyleType<?,?> other) {
            return Integer.compare(this.getPriority(), other.getPriority());
        } else {
            return 0;
        }
    }

    @Override
    default R applyValue(R original, T value) {
        return original;
    }
}
