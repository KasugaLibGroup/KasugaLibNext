package lib.kasuga.widget.renderer.model.style;

import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.dom.style.StyleMap;
import lib.kasuga.widget.renderer.model.ModelElementRenderer;
import lib.kasuga.widget.renderer.model.RotationMode;
import net.minecraft.world.phys.Vec3;

public class RotationModeStyle extends Style3D<RotationMode> {
    @Override
    protected void applyStyle(StyleMap<DomElement> styleTypes, DomElement instance, RotationMode element, ModelElementRenderer renderer) {
        if(element == null){
            renderer.setRotationMode(RotationMode.XYZ);
            return;
        }
        renderer.setRotationMode(element);
    }
}
