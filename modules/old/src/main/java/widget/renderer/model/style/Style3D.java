package lib.kasuga.widget.renderer.model.style;

import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.dom.DomStyleType;
import lib.kasuga.widget.dom.style.StyleMap;
import lib.kasuga.widget.renderer.model.ModelElementRenderer;

public class Style3D<T, R> implements DomStyleType<T, R> {
    @Override
    public final void applyStyle(StyleMap<DomElement> styleTypes, DomElement instance, T element) {
        if(!(instance.getRenderer() instanceof ModelElementRenderer renderer)) {
            return;
        }
        this.applyStyle(styleTypes, instance, element, renderer);
    }

    protected void applyStyle(StyleMap<DomElement> styleTypes, DomElement instance, T element, ModelElementRenderer renderer) {

    }
}
