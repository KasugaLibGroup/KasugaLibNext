package lib.kasuga.widget.renderer.ui.box;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.rendering.StencilUtils;
import lib.kasuga.widget.renderer.ui.style.enumaration.OverflowState;
import net.minecraft.world.phys.Vec2;

public class ScrollRenderBox extends RenderBox {
    protected Vec2 scrollOffset;
    protected Vec2 scrollScale;

    protected static float SAFE_MAX_VALUE = Float.MAX_VALUE / 2;

    protected OverflowState overflowX = OverflowState.VISIBLE;
    protected OverflowState overflowY = OverflowState.VISIBLE;

    public boolean prepare(RenderContext context) {
        if(overflowX == OverflowState.VISIBLE && overflowY == OverflowState.VISIBLE)
            return false;
        Vec2 clipStart = this.origin;
        Vec2 clipEnd = this.origin.add(size);
        if(overflowX == OverflowState.VISIBLE){
            clipStart = new Vec2(-SAFE_MAX_VALUE, clipStart.y);
            clipEnd = new Vec2(SAFE_MAX_VALUE, clipEnd.y);
        }
        if(overflowY == OverflowState.VISIBLE){
            clipStart = new Vec2(clipStart.x, -SAFE_MAX_VALUE);
            clipEnd = new Vec2(clipEnd.x, SAFE_MAX_VALUE);
        }
        Vec2 finalClipStart = clipStart;
        Vec2 finalClipEnd = clipEnd;
        context.pushStencil((ctx)->{
            StencilUtils.stencilRect(ctx.buffer(), ctx.pose(), finalClipStart.x, finalClipStart.y, finalClipEnd.x, finalClipEnd.y, 0);
        });
        context.pose().pushPose();
        context.pose().translate(scrollOffset.x, scrollOffset.y, 0);
        context.pose().scale(scrollScale.x, scrollScale.y, 0);
        return true;
    }

    public void end(RenderContext context) {
        context.pose().popPose();
        context.popStencil();
    }
}
