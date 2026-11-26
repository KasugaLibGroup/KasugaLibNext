package lib.kasuga.elementOld.attribute;

import javax.annotation.Nullable;

public interface AttributeType<T extends Attribute<D>, D> {
    public void onAttributeUpdate(D delegate, @Nullable T attribute);
}
