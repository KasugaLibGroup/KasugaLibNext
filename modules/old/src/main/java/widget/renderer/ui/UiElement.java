package lib.kasuga.widget.renderer.ui;

import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.dom.DomSchema;
import lib.kasuga.widget.renderer.ElementRenderer;

public class UiElement extends DomElement {
    public UiElement(DomSchema schema) {
        super(schema);
    }

    @Override
    protected ElementRenderer createRenderer(DomElement domElement) {
        return new UiElementRenderer(this);
    }
}
