package lib.kasuga.widget.renderer.model.style;


import com.mojang.math.Transformation;
import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.dom.style.StyleMap;
import lib.kasuga.widget.renderer.model.ModelElementRenderer;
import org.joml.Matrix4f;

public class TransformStyle extends Style3D<Transformation> {
    @Override
    protected void applyStyle(StyleMap<DomElement> styleTypes, DomElement instance, Transformation element, ModelElementRenderer renderer) {
        if(element == null) {
            renderer.setTransform(Transformation.identity());
            return;
        }
        renderer.setTransform(element);
    }
}
