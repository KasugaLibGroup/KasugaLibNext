package lib.kasuga.rendering.models.mc.backend.ui;

import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldMouseDataTest {
    @Test void firstFocusIsImmediatelyUsableAndFollowingFocusUpdatesPosition() {
        var listener = new WorldUiInputRouterTest.Listener();
        var data = new UIInstanceData(listener);
        data.setFocused(null, new Vector2f(10, 20));
        assertTrue(data.isFocused(null));
        data.operate(null, (entity, mouse) -> mouse.mouseClicked(listener, 0));
        assertEquals(1, listener.presses);
        data.setFocused(null, new Vector2f(20, 25));
        assertEquals(new Vector2f(20, 25), data.getOperators().get(null).getPosition());
        data.removeFocused(null);
        assertFalse(data.isFocused(null));
    }

    @Test void localDeltasAreIncrementalAndOnlyConsumedOnce() {
        var data = new WorldMouseData(new Vector2f(10, 20));
        var listener = new WorldUiInputRouterTest.Listener();
        data.focused = true;
        data.updatePosition(new Vector2f(15, 23));
        data.mouseDragged(listener, 0);
        data.mouseDragged(listener, 0);
        assertEquals(new Vector2f(5, 3), listener.delta);
        assertEquals(1, listener.drags);
        data.updatePosition(new Vector2f(17, 24));
        data.mouseDragged(listener, 0);
        assertEquals(new Vector2f(2, 1), listener.delta);
        assertEquals(2, listener.drags);
    }
}
