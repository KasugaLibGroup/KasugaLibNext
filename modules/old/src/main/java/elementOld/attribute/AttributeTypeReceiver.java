package lib.kasuga.elementOld.attribute;

import java.util.function.Function;

public interface AttributeTypeReceiver<S, T extends Attribute<D>, D> {
    AttributeType<T, D> getAttributeType();
    T create(S source);

    public static <S, T extends Attribute<D>, D> AttributeTypeReceiver<S, T, D> create(AttributeType<T, D> type, Function<S, T> creator) {
        return new AttributeTypeReceiver<S, T, D>() {
            @Override
            public AttributeType<T, D> getAttributeType() {
                return type;
            }

            @Override
            public T create(S source) {
                return creator.apply(source);
            }
        };
    }
}
