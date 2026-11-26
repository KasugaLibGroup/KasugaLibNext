package lib.kasuga.widget.dom;

import com.google.common.reflect.TypeToken;
import lib.kasuga.elementOld.attribute.Attribute;
import lib.kasuga.widget.dom.tree.AttributeType;
import org.jetbrains.annotations.Nullable;

public interface DomAttributeType<T> extends AttributeType<T, DomElement> {
    public static class Default<T> implements DomAttributeType<T> {
        TypeToken<T> token;
        protected Default() {
            token = new TypeToken<T>(getClass()) {
            };
        }

        @Override
        public void onAttributeUpdate(DomElement element, @Nullable T attribute) {}

        @SuppressWarnings("unchecked")
        @Override
        public T validate(Object object) {
            return (T) token.getRawType().cast(object);
        }
    }

    public static <T> DomAttributeType<T> createDefault() {
        return new Default<T>();
    }
}
