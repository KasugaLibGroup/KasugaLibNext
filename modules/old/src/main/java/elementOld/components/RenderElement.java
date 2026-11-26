package lib.kasuga.elementOld.components;

import lib.kasuga.elementOld.attribute.AttributeManager;
import lib.kasuga.rendering.RenderContext;

import java.util.*;

public class RenderElement implements AutoCloseable {
    protected AttributeManager attributes;
    protected Collection<RenderElement> children = new ArrayList<>();
    public void render(RenderContext context) {
        this.renderElement(context);
        for (RenderElement child : children) {
            child.render(context);
        }
    }

    protected void renderElement(RenderContext context) {

    }

    public void addChild(RenderElement child) {
        this.children.add(child);
    }

    public boolean removeChild(RenderElement child) {
        return this.children.remove(child);
    }

    public void close() {}
}
