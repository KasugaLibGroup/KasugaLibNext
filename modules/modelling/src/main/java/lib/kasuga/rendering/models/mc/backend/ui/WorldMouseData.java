package lib.kasuga.rendering.models.mc.backend.ui;

import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;

public class WorldMouseData {

    @Getter
    protected Vector2f lastPosition;

    @Getter
    protected Vector2f lastMotion;

    @Getter
    protected boolean motionHasBeenConsumed;

    @Getter
    protected Vector2f position;

    public boolean focused;

    public WorldMouseData(@Nullable Vector2f originalPos) {
        lastPosition = originalPos != null ?
                new Vector2f(originalPos) :
                new Vector2f();
        position = new Vector2f(lastPosition);
        lastMotion = new Vector2f();
        motionHasBeenConsumed = false;
        focused = false;
    }

    public void updatePosition(Vector2f position) {
        this.lastMotion = position;
        this.position = new Vector2f(position);
        this.lastMotion =  new Vector2f();
        lastMotion.add(this.position).sub(lastPosition);
        motionHasBeenConsumed = false;
    }

    public void mouseMoved(GuiEventListener listener) {
        if (focused) listener.mouseMoved(position.x,  position.y);
    }

    public void mouseClicked(GuiEventListener listener, int button) {
        if (focused) listener.mouseClicked(position.x, position.y, button);
    }

    public void mouseReleased(GuiEventListener listener, int button) {
        if (focused) listener.mouseReleased(position.x, position.y, button);
    }

    public void mouseDragged(GuiEventListener listener, int button) {
        if (focused && !motionHasBeenConsumed) {
            listener.mouseDragged(position.x, position.y, button, lastMotion.x, lastMotion.y);
            motionHasBeenConsumed = true;
        }
    }

    public void mouseScrolled(GuiEventListener listener, double deltaX, double deltaY) {
        if (focused) listener.mouseScrolled(position.x, position.y, deltaX, deltaY);
    }

    public boolean isMouseOver(GuiEventListener listener) {
        return focused && listener.isMouseOver(position.x, position.y);
    }
}
