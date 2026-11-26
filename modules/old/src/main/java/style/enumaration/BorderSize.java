package lib.kasuga.widget.renderer.ui.style.enumaration;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

@Data
public class BorderSize {
    protected float top;
    protected float left;
    protected float right;
    protected float bottom;

    public Vec2 addOrigin(Vec2 origin) {
        return origin.add(new Vec2(-this.left, -this.top));
    }

    public Vec2 addSize(Vec2 size){
        return size.add(new Vec2(this.left + this.right, this.top + this.bottom));
    }
}
