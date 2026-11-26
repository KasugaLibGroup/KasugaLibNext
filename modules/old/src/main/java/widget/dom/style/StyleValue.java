package lib.kasuga.widget.dom.style;

import lib.kasuga.widget.dom.tree.Element;

import java.util.Objects;
import java.util.TreeMap;

public class StyleValue<T extends Element<T>, R> {
    TreeMap<ApplicableStyle<?, R,T>, Object> styles = new TreeMap<>();

    R initialValue;
    R computedValue;

    public StyleValue(R initialValue) {
        this.initialValue = initialValue;
        this.computedValue = initialValue;
    }

    public <G> void add(ApplicableStyle<G, R, T> applier, G value) {
        styles.put(applier, value);
        compute();
    }

    public void remove(ApplicableStyle<?, R, T> applier) {
        styles.remove(applier);
    }

    public void compute() {
        R computed = compute(initialValue);
        if(!Objects.equals(computed, computedValue)) {
            computedValue = computed;
            this.notifyUpdate();
        }
    }

    private void notifyUpdate() {

    }

    @SuppressWarnings("unchecked")
    public R compute(R initialValue) {
        R value = initialValue;
        for(ApplicableStyle<?, R, T> applier : styles.keySet()) {
            Object styleValue = styles.get(applier);
            value = ((StyleApplier<R, Object>) applier).applyValue(value, styleValue);
        }
        return value;
    }
}
