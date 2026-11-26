package lib.kasuga.widget.dom.style;

import com.google.common.collect.ImmutableSet;
import lib.kasuga.widget.dom.tree.Element;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public interface StyleMap<T extends Element<T>> extends Iterable<StyleType<?, T>> {

    public <D> D getStyle(StyleType<D, T> type);

    public Collection<StyleType<?, T>> keySet();

    public static <T extends Element<T>> Immutable<T> of(Map<StyleType<?, T>, Object> styles) {
        return new Immutable<>(styles);
    }

    public static class Immutable<T extends Element<T>> implements StyleMap<T> {

        private final Map<StyleType<?, T>, Object> styles;

        public Immutable(Map<StyleType<?, T>, Object> styles) {
            this.styles = styles;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <D> D getStyle(StyleType<D, T> type) {
            return (D) styles.get(type);
        }

        @Override
        public Collection<StyleType<?, T>> keySet() {
            return styles.keySet();
        }

        @NotNull
        @Override
        public Iterator<StyleType<?, T>> iterator() {
            return styles.keySet().iterator();
        }
    }

    public static class Mutable<T extends Element<T>> implements StyleMap<T> {
        protected HashMap<StyleType<?, T>, Object> styles = new HashMap<>();
        public <D> void putStyle(StyleType<D, T> type, D value) {
            styles.put(type, value);
        }

        public <D> void removeStyle(StyleType<D, T> type) {
            styles.remove(type);
        }

        @SuppressWarnings("unchecked")
        public <D> D getStyle(StyleType<D, T> type) {
            return (D) styles.get(type);
        }

        @Override
        public Collection<StyleType<?, T>> keySet() {
            return styles.keySet();
        }

        @NotNull
        @Override
        public Iterator<StyleType<?, T>> iterator() {
            return styles.keySet().iterator();
        }

        public void copy(StyleMap<T> original, boolean extendableOnly) {
            for (StyleType<?, T> type : original) {
                if(extendableOnly && !type.isExtendable())
                    continue;
                this.styles.put(type, original.getStyle(type));
            }
        }

        public void clear() {
            this.styles.clear();
        }

        public void apply(StyleMap<T> original, T instance) {
            Set<StyleType<?, T>> allTypes = ImmutableSet
                    .<StyleType<?,T>>builder()
                    .addAll(this.styles.keySet())
                    .addAll(original.keySet())
                    .build();

            for (StyleType<?, T> type : allTypes) {
                if(this.getStyle(type) != original.getStyle(type)) {
                    //noinspection unchecked
                    ((StyleType<Object, T>)type).applyStyle(this, instance, original.getStyle(type));
                    this.styles.put(type, original.getStyle(type));
                }
            }
        }
    }
}
