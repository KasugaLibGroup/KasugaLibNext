package lib.kasuga.widget.renderer.model;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.dom.DomSchema;
import lib.kasuga.widget.renderer.ElementRenderer;

public class Element3D extends DomElement {
    public Element3D(DomSchema schema) {
        super(schema);
    }

    @Override
    protected ElementRenderer createRenderer(DomElement domElement) {
        return new ModelElementRenderer(this);
    }
}
