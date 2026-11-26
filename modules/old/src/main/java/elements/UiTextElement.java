package lib.kasuga.widget.renderer.ui.elements;

import lib.kasuga.widget.dom.DomAttributeType;
import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.dom.DomSchema;
import lib.kasuga.widget.dom.tree.AttributeType;
import lib.kasuga.widget.renderer.ElementRenderer;
import lib.kasuga.widget.renderer.ui.UiElement;

public class UiTextElement extends UiElement {

    protected static DomAttributeType<String> TEXT = DomAttributeType.createDefault();

    public UiTextElement(DomSchema schema) {
        super(schema);
    }

    @Override
    protected ElementRenderer createRenderer(DomElement domElement) {
        return new UiTextRenderer(this);
    }

    public String getText() {
        return this.getAttribute(TEXT);
    }

    public void setText(String text) {
        this.setAttribute(TEXT, text);
    }
}
