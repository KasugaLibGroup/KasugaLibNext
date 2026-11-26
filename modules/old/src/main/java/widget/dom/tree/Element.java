package lib.kasuga.widget.dom.tree;

import lib.kasuga.widget.dom.context.ContextType;

import javax.annotation.Nullable;
import java.util.*;

public class Element<T extends Element<T>> {

    protected T parent;

    List<T> children = new ArrayList<>();

    AttributeStore<T> attributes = new AttributeStore<T>(this);
    private Map<ContextType<?>, Object> contextMap = new HashMap<>();

    protected static <T extends Element<T>> void removeChildFromParent(T element) {
        if(element.parent != null) {
            element.setParent(null);
        }
    }

    protected void setParent(@Nullable T parent) {
        this.parent = parent;
    }

    @SuppressWarnings("unchecked")
    protected T self() {
        return (T) this;
    }

    public int addChild(T element) {
        removeChildFromParent(element);
        int size = children.size();
        children.add(element);
        element.setParent(self());
        return size;
    }

    public int removeChild(T element) {
        int index = children.indexOf(element);
        children.remove(element);
        element.setParent(null);
        return index;
    }

    public int removeChildAt(int index) {
        if(index < 0 || index > this.children.size())
            return -1;
        return removeChild(this.children.get(index));
    }

    public int addChildBefore(T source, T element) {
        removeChildFromParent(element);
        int index = children.indexOf(source);
        if(index < 0)
            index = 0;
        children.add(index, element);
        element.setParent(self());
        return index;
    }

    public int addChildAfter(T source, T element) {
        removeChildFromParent(element);
        int index = children.indexOf(source) + 1;
        if(index > children.size())
            index = children.size();
        children.add(index, element);
        element.setParent(self());
        return index;
    }

    public <V> void setAttribute(AttributeType<V, T> type, V attribute) {
        attributes.setAttribute(type, attribute);
    }

    public <V> V getAttribute(AttributeType<V, T> type) {
        return attributes.getAttribute(type);
    }

    public void removeAttribute(AttributeType<?, T> type) {
        attributes.removeAttribute(type);
    }

    public Element<T> getParentNode() {
        return parent;
    }

    public Collection<T> getChildren() {
        return children;
    }

    public <C> void provideContext(ContextType<C> contextType, C value) {
        this.contextMap.put(contextType, value);
    }

    @SuppressWarnings("unchecked")
    public <C> C getContext(ContextType<C> contextType) {
        if(this.contextMap.containsKey(contextType))
            return (C) this.contextMap.get(contextType);
        if(this.parent != null)
            return this.parent.getContext(contextType);
        return null;
    }
}
