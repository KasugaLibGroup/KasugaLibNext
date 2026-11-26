package lib.kasuga.widget.dom;

import lib.kasuga.widget.dom.tree.AttributeType;
import lib.kasuga.widget.dom.tree.Document;
import lib.kasuga.widget.renderer.ElementRenderer;
import net.minecraft.resources.ResourceLocation;

public class DomSchema implements Document<DomElement, ResourceLocation> {
    @Override
    public DomElement createElement(ResourceLocation elementIdentifier) {
        return null;
    }

    @Override
    public AttributeType<?, DomElement> getAttributeType(ResourceLocation attributeIdentifier) {
        return null;
    }

    public ElementRenderer createRenderer(DomElement domElement) {
        return null;
    }
}
