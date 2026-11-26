package lib.kasuga.elementOld.attribute;

import java.util.HashMap;
import java.util.Map;

public class AttributeManager<D> {
    private final D delegate;
    Map<AttributeType<?, D>, Attribute<D>> attributes = new HashMap<>();

    AttributeManager(D delegate) {
        this.delegate = delegate;
    }

    public <S, T extends Attribute<D>> void setAttribute(AttributeTypeReceiver<S, T, D> receiver, S source) {
        setAttribute(receiver.getAttributeType(), receiver.create(source));
    }

    public <T extends Attribute<D>> void setAttribute(AttributeType<T, D> type, T attribute) {
        this.attributes.put(type, attribute);
        type.onAttributeUpdate(this.delegate, attribute);
    }

    public <T extends Attribute<D>> void removeAttribute(AttributeType<T, D> type) {
        this.attributes.remove(type);
        type.onAttributeUpdate(this.delegate, null);
    }

    @SuppressWarnings("unchecked")
    public <T extends Attribute<D>> T getAttribute(AttributeType<T, D> type) {
        return (T) this.attributes.get(type);
    }
}
