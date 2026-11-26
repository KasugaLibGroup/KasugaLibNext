package lib.kasuga.widget.renderer.ui.box;

import lib.kasuga.rendering.RenderContext;
import lombok.Setter;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

@Setter
public abstract class RenderBox {
    protected Vec2 origin;
    protected Vec2 size;

    public double render(RenderContext context) {
        return 0.0D;
    }
}
