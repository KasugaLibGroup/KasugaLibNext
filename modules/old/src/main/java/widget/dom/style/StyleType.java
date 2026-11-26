package lib.kasuga.widget.dom.style;

import lib.kasuga.widget.dom.tree.Element;

public interface StyleType<T, D extends Element<D>> {
    public default boolean isExtendable() {
        return true;
    }

    public default void applyStyle(StyleMap<D> styleTypes, D instance, T element) {}
}
