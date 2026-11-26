package lib.kasuga.elementOld.components;

import lib.kasuga.rendering.RenderContext;

public class RenderElement2D extends RenderElement {
    RenderElement2D background;

    @Override
    public void render(RenderContext context) {
        this.renderGui(context);
    }

    public double renderGui(RenderContext context) {
        double zSize = 0;

        context.pushPose();
        // Transformation, Stencil

        if(background != null) {
            double elementZSize = background.renderGui(context);
            context.pose().translate(0,0, elementZSize);
            zSize += elementZSize;
        }

        zSize += this.renderUiElement(context);

        for (RenderElement child : children) {
            if(!(child instanceof RenderElement2D renderElement2D)) {
                throw new IllegalStateException("Cannot render non-2D element in children of 2D element");
            }

            double elementZSize = renderElement2D.renderGui(context);

            context.pose().translate(0,0, elementZSize);

            zSize += elementZSize;
        }

        context.popPose();

        return zSize;
    }

    @Override
    protected final void renderElement(RenderContext context) {
        renderUiElement(context);
    }

    protected double renderUiElement(RenderContext context) {
        return 0.0D;
    }
}
