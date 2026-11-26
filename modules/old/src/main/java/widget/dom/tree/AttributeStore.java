package lib.kasuga.widget.dom.tree;

import java.util.HashMap;
import java.util.Map;

public class AttributeStore<T extends Element<T>> {
    private final Element<T> element;
    Map<AttributeType<?, T>, Object> attributes = new HashMap<>();

    public AttributeStore(Element<T> element) {
        this.element = element;
    }

    public <V> void setAttribute(AttributeType<V, T> type, V attribute) {
        attributes.put(type, attribute);
        type.onAttributeUpdate(element.self(), attribute);
    }

    public void removeAttribute(AttributeType<?, T> type) {
        attributes.remove(type);
        type.onAttributeUpdate(element.self(), null);
    }

    @SuppressWarnings("unchecked")
    public <V> V getAttribute(AttributeType<V, T> type) {
        return (V) attributes.get(type);
    }
}
