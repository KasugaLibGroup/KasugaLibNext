package lib.kasuga.widget.dom.style;

import lib.kasuga.widget.dom.tree.Element;

public interface ApplicableStyle<V, R, T extends Element<T>> extends StyleApplier<R, V>, StyleType<V, T>, Comparable<ApplicableStyle<?, ?, ?>> {

}
