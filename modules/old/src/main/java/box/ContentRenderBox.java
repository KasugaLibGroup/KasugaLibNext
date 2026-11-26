package lib.kasuga.widget.renderer.ui.box;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.renderer.ui.ICustomDomRenderer;
import lib.kasuga.widget.renderer.ui.UiElementRenderer;

import java.util.HashMap;
import java.util.TreeMap;
import java.util.TreeSet;

public class ContentRenderBox extends RenderBox {
    protected HashMap<UiElementRenderer, ICustomDomRenderer> customRenderers = new HashMap<>();
    protected TreeSet<UiElementRenderer> renderers = new TreeSet<>();
    public void addElement(UiElementRenderer uiRenderer) {
        if(uiRenderer instanceof ICustomDomRenderer renderer) {
            customRenderers.put(uiRenderer, renderer);
        }
        renderers.add(uiRenderer);
    }

    public void removeElement(UiElementRenderer uiRenderer) {
        if(uiRenderer instanceof ICustomDomRenderer) {
            customRenderers.remove(uiRenderer);
        }
        renderers.remove(uiRenderer);
    }

    @Override
    public double render(RenderContext context) {
        double zDepth = 0;
        for(UiElementRenderer renderer : renderers) {
            if(customRenderers.containsKey(renderer)) {
                zDepth += customRenderers.get(renderer).renderCustom(context);
            } else {
                zDepth += renderer.getMainRenderer().render(context);
            }
        }
        return zDepth;
    }
}
