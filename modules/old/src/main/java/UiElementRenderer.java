package lib.kasuga.widget.renderer.ui;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.renderer.ElementRenderer;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

public class UiElementRenderer implements ElementRenderer, Comparable<UiElementRenderer> {
    protected final UiElement element;

    @Getter
    protected BorderRenderBox borderRenderer = new BorderRenderBox();

    @Getter
    protected ContentRenderBox contentRenderer = new ContentRenderBox();

    @Getter
    protected BackgroundRenderBox backgroundRenderer = new BackgroundRenderBox();

    @Getter
    protected ScrollRenderBox scrollBox = new ScrollRenderBox();

    @Getter
    protected MainRenderBox mainRenderer = new MainRenderBox(this, borderRenderer, contentRenderer, backgroundRenderer, scrollBox);

    public UiElementRenderer(UiElement element) {
        this.element = element;
    }

    @Override
    public void addElement(DomElement element, int index) {
        ElementRenderer renderer = element.getRenderer();
        if(renderer instanceof UiElementRenderer uiRenderer) {
            this.contentRenderer.addElement(uiRenderer);
        }
    }

    @Override
    public void removeElement(DomElement element, int index) {
        ElementRenderer renderer = element.getRenderer();
        if(renderer instanceof UiElementRenderer uiRenderer) {
            this.contentRenderer.removeElement(uiRenderer);
        }
    }

    @Override
    public void render(RenderContext context) {
        context.pushPose();
        if(context.shouldViewEpsilon())
            context.pose().scale(1, 1, context.viewEpsilon());
        this.renderUi(context);
        context.popPose();
    }

    protected double renderUi(RenderContext context) {
        return this.mainRenderer.render(context);
    }

    @Override
    public int compareTo(@NotNull UiElementRenderer o) {
        return Integer.compare(this.mainRenderer.getZIndex(), o.mainRenderer.getZIndex());
    }

    public double renderSelf(RenderContext context) {}
}
