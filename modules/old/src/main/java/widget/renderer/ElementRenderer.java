package lib.kasuga.widget.renderer;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.dom.DomElement;

public interface ElementRenderer {

    void addElement(DomElement element, int index);

    void removeElement(DomElement element, int index);

    void render(RenderContext context);
}
