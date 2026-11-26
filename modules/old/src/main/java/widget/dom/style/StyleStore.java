package lib.kasuga.widget.dom.style;

import lib.kasuga.widget.dom.stylesheet.Stylesheet;
import lib.kasuga.widget.dom.stylesheet.StylesheetContext;
import lib.kasuga.widget.dom.tree.Element;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

public class StyleStore<T extends Element<T>> {
    StyleStore<T> parent;
    protected final T element;
    Set<StyleStore<T>> children = new HashSet<>();
    StyleMap.Mutable<T> styles = new StyleMap.Mutable<>();
    StyleMap.Mutable<T> stashStyles = new StyleMap.Mutable<>();
    StyleMap.Mutable<T> commitStyles = new StyleMap.Mutable<>();

    public StyleStore(T element) {
        this.element = element;
    }

    public void setParent(@Nullable StyleStore<T> parent) {
        if(this.parent != null) {
            this.parent.children.remove(this);
            this.parent = null;
        }
        if(parent != null) {
            this.parent = parent;
            parent.children.add(this);
        }
        notifyUpdate();
    }

    public void notifyUpdate() {
        this.update();
        for (StyleStore<T> child : this.children) {
            child.notifyUpdate();
        }
    }

    protected void update() {
        this.stashStyles.clear();
        Stylesheet<?> stylesheet = this.element.getContext(StylesheetContext.STYLESHEET);
        if(stylesheet != null) {
            //noinspection unchecked
            ((Stylesheet<T>) stylesheet).apply(this.stashStyles, this.element);
        }
        if(this.parent != null)
            this.stashStyles.copy(this.parent.stashStyles,true);
        this.stashStyles.copy(this.styles, false);
        this.commitStyles.apply(this.stashStyles, this.element);
    }

    public <V> void putStyle(StyleType<V, T> type, V value) {
        this.styles.putStyle(type, value);
        if(type.isExtendable()) {
            notifyUpdate();
        } else {
            update();
        }
    }

    public <V> V getStyle(StyleType<V, T> type) {
        return this.styles.getStyle(type);
    }

    public <V> V getComputedStyle(StyleType<V, T> type) {
        return this.commitStyles.getStyle(type);
    }

    public void removeStyle(StyleType<?, T> type) {
        this.styles.removeStyle(type);
        if(type.isExtendable()) {
            notifyUpdate();
        } else {
            update();
        }
    }
}
