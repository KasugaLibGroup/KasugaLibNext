package lib.kasuga.widget.dom.stylesheet;

import lib.kasuga.widget.dom.style.StyleMap;
import lib.kasuga.widget.dom.tree.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class Stylesheet<T extends Element<T>> {
    public record Rule<T extends Element<T>>(
            Predicate<T> selector,
            StyleMap<T> styles
    ) {}

    protected List<Rule<T>> rules = new ArrayList<>();
    public void apply(StyleMap.Mutable<T> style, T element) {
        for (Rule<T> rule : rules) {
            if(rule.selector().test(element)) {
                style.apply(rule.styles(), element);
            }
        }
    }

    public void addStyle(Predicate<T> selector, StyleMap<T> styles) {
        rules.add(new Rule<>(selector, styles));
    }
}
