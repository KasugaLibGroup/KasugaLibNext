package lib.kasuga.widget.renderer.model.style;

import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.dom.style.StyleMap;
import lib.kasuga.widget.renderer.model.ModelElementRenderer;
import net.minecraft.world.phys.Vec3;

public class RotationStyle extends Style3D<Vec3> {
    @Override
    protected void applyStyle(StyleMap<DomElement> styleTypes, DomElement instance, Vec3 element, ModelElementRenderer renderer) {
        if(element == null){
            renderer.setRotation(1,1,1);
            return;
        }
        renderer.setRotation(element.x, element.y, element.z);
    }
}
