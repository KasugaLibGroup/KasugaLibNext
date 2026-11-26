package lib.kasuga.widget.dom.tree;

import com.google.common.reflect.TypeToken;

import javax.annotation.Nullable;

public interface AttributeType<T, D extends Element<D>> {
    public void onAttributeUpdate(D element, @Nullable T attribute);
    public T validate(Object object);

    public static class Default<T, D extends Element<D>> implements AttributeType<T, D> {
        TypeToken<T> token;
        protected Default() {
            token = new TypeToken<T>(getClass()) {};
        }
        @Override
        public void onAttributeUpdate(D element, @org.jetbrains.annotations.Nullable T attribute) {

        }

        @SuppressWarnings("unchecked")
        @Override
        public T validate(Object object) {
             return (T) token.getRawType().cast(object);
        }
    }
    public static <T, D extends Element<D>> AttributeType<T, D> createDefault() {

        return new Default<>();
    }
}
