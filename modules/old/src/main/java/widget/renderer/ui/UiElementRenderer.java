package lib.kasuga.widget.renderer.ui;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.renderer.ElementRenderer;

public class UiElementRenderer implements ElementRenderer {
    private final UiElement element;

    public UiElementRenderer(UiElement uiElement) {
        this.element = uiElement;
    }

    @Override
    public void addElement(DomElement element, int index) {
        ElementRenderer elementRenderer = element.getRenderer();
        UiElementRenderer uiRenderer = elementRenderer instanceof UiElementRenderer ? (UiElementRenderer) elementRenderer : null;

    }

    @Override
    public void removeElement(DomElement element, int index) {

    }

    @Override
    public void render(RenderContext context) {

    }

    public float renderUi(RenderContext context) {
        return 0.0F;
    }
}
