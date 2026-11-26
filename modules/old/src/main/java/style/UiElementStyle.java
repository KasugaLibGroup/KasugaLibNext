package lib.kasuga.widget.renderer.ui.style;

import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.dom.DomStyleType;
import lib.kasuga.widget.dom.style.StyleMap;
import lib.kasuga.widget.renderer.ui.UiElementRenderer;

public class UiElementStyle<T> implements DomStyleType<T> {
    @Override
    public final void applyStyle(StyleMap<DomElement> styleTypes, DomElement instance, T element) {
        if(!(instance.getRenderer() instanceof UiElementRenderer renderer)) {
            return;
        }
        this.applyStyle(styleTypes, instance, element, renderer);
    }

    protected void applyStyle(StyleMap<DomElement> styleTypes, DomElement instance, T element, UiElementRenderer renderer) {

    }
}
