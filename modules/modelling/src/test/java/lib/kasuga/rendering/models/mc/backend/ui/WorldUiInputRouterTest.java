package lib.kasuga.rendering.models.mc.backend.ui;

import net.minecraft.client.gui.components.events.GuiEventListener;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldUiInputRouterTest {
    private static Matrix4f panel(float z) {
        return new Matrix4f().translation(-1, 1, z).scale(.01f, -.01f, 1);
    }

    private static WorldUiInputRouter router() {
        var router = new WorldUiInputRouter();
        router.beginFrame(new Matrix4f().perspective((float) Math.PI / 2, 1, .05f, 100), 400, 400, 200, 200);
        return router;
    }

    @Test void picksNearestUiAndRetainsItsCoordinatesRegardlessOfRegistrationOrder() {
        var router = router();
        var near = new Listener();
        var far = new Listener();
        router.addSurface(near, panel(-2), 200, 200);
        router.addSurface(far, new Matrix4f().translation(-2, 2, -4).scale(.02f, -.02f, 1), 200, 200);
        router.endFrame();
        assertSame(near, router.hovered());
        assertTrue(router.press(240, 200, 0));
        assertEquals(140, near.position.x, .001);
        assertEquals(100, near.position.y, .001);
        assertEquals(1, near.presses);
        assertEquals(0, far.presses);
    }

    @Test void dragAndReleaseStayWithPressTargetAfterLeavingItsBoundsAndHoveringAnotherUi() {
        var router = router();
        var first = new Listener();
        var second = new Listener();
        router.addSurface(first, panel(-2), 200, 200);
        router.addSurface(second, new Matrix4f().translation(1, 1, -2).scale(.01f, -.01f, 1), 200, 200);
        router.endFrame();
        router.press(200, 200, 0);
        router.move(350, 200);
        assertSame(second, router.hovered());
        assertEquals(1, first.drags);
        assertEquals(150, first.delta.x, .001);
        assertEquals(250, first.position.x, .001);
        router.release(350, 200, 0);
        assertEquals(1, first.releases);
        assertEquals(0, second.releases);
        assertFalse(router.release(350, 200, 0));
    }

    @Test void eachMoveUsesPreviousLocalPositionAndDuplicateDragNotificationDoesNotRepeat() {
        var router = router();
        var listener = new Listener();
        router.addSurface(listener, panel(-2), 200, 200);
        router.endFrame();
        router.press(200, 200, 0);
        router.move(210, 200);
        assertEquals(10, listener.delta.x, .001);
        router.move(220, 200);
        assertEquals(10, listener.delta.x, .001);
        router.move(220, 200);
        assertEquals(2, listener.drags);
    }

    @Test void behindCameraParallelOutOfReachAndSingularTargetsCannotReceiveInput() {
        for (Matrix4f transform : new Matrix4f[]{panel(2), panel(-8), new Matrix4f().zero(),
                new Matrix4f().translation(0, 0, -2).rotateY((float) Math.PI / 2)}) {
            var router = router();
            var listener = new Listener();
            router.addSurface(listener, transform, 200, 200);
            router.endFrame();
            assertNull(router.hovered());
            assertFalse(router.press(200, 200, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> router().setMaxDistance(Float.NaN));
    }

    @Test void scrollKeyboardAndCharactersReachTheSelectedUiAndResetBalancesReleases() {
        var router = router();
        var listener = new Listener();
        router.addSurface(listener, panel(-2), 200, 200);
        router.endFrame();
        router.press(200, 200, 0);
        router.press(200, 200, 1);
        router.scroll(200, 200, .5, -2);
        assertEquals(-2, listener.scrollY);
        router.keyPressed(65, 0, 0);
        router.charTyped('a', 0);
        assertEquals('a', listener.character);
        router.reset();
        router.reset();
        assertEquals(2, listener.releases);
        assertEquals(1, listener.keyReleases);
        assertFalse(listener.focused);
        assertNull(router.hovered());
    }

    @Test void removingTargetOrOmittingItNextFrameCancelsItsCapture() {
        for (boolean removeExplicitly : new boolean[]{true, false}) {
            var router = router();
            var listener = new Listener();
            router.addSurface(listener, panel(-2), 200, 200);
            router.endFrame();
            router.press(200, 200, 0);
            if (removeExplicitly) router.remove(listener);
            else {
                router.beginFrame(new Matrix4f().perspective(1, 1, .05f, 100), 400, 400, 200, 200);
                router.endFrame();
            }
            assertEquals(1, listener.releases);
            assertFalse(listener.focused);
            assertFalse(router.release(200, 200, 0));
        }
    }

    @Test void closingFromClickCallbackDoesNotResurrectCapture() {
        var router = router();
        var listener = new Listener() {
            @Override public boolean mouseClicked(double x, double y, int button) {
                super.mouseClicked(x, y, button);
                router.reset();
                return true;
            }
        };
        router.addSurface(listener, panel(-2), 200, 200);
        router.endFrame();
        router.press(200, 200, 0);
        assertEquals(1, listener.releases);
        assertFalse(router.release(200, 200, 0));
    }

    @Test void widgetBoundsAndRotatedPanelUseTheActualDrawTransform() {
        var router = router();
        var listener = new Listener();
        var transform = new Matrix4f().translation(-1, 1, -2).rotateY(.2f).scale(.01f, -.01f, 1);
        router.addSurface(listener, transform, 80, 80, 60, 60);
        var position = router.positionOn(listener);
        assertNotNull(position);
        router.endFrame();
        assertSame(listener, router.hovered());
        assertEquals(100 / Math.cos(.2), position.x, .001);
        router.move(110, 200);
        assertNull(router.hovered());
    }

    @Test void leavingViewportClearsHoverButPreservesDragAndRelease() {
        var router = router();
        var listener = new Listener();
        router.addSurface(listener, new Matrix4f().translation(-5, 5, -2).scale(.01f, -.01f, 1), 1000, 1000);
        router.endFrame();
        router.press(200, 200, 0);
        router.move(410, 200);
        assertNull(router.hovered());
        assertEquals(1, listener.drags);
        assertTrue(router.release(410, 200, 0));
        assertEquals(1, listener.releases);
        assertFalse(router.press(410, 200, 0));
    }

    @Test void invalidFrameAndReentrantResetCannotLeavePressedButtonsBehind() {
        var router = router();
        var listener = new Listener() {
            @Override public boolean mouseReleased(double x, double y, int button) {
                super.mouseReleased(x, y, button);
                router.reset();
                router.press(200, 200, button);
                return true;
            }
        };
        router.addSurface(listener, panel(-2), 200, 200);
        router.endFrame();
        router.press(200, 200, 0);
        assertThrows(IllegalArgumentException.class,
                () -> router.beginFrame(new Matrix4f().zero(), 400, 400, 200, 200));
        assertEquals(1, listener.releases);
        assertEquals(1, listener.presses);
        assertNull(router.hovered());
        assertFalse(router.release(200, 200, 0));
    }

    @Test void unknownKeysRetainSeparateScanCodeOwnershipAndBalancedReleases() {
        var router = router();
        var released = new java.util.ArrayList<Integer>();
        var listener = new Listener() {
            @Override public boolean keyReleased(int key, int scanCode, int modifiers) {
                released.add(scanCode);
                return true;
            }
        };
        router.addSurface(listener, panel(-2), 200, 200);
        router.endFrame();
        router.press(200, 200, 0);
        router.keyPressed(-1, 100, 0);
        router.keyPressed(-1, 101, 0);
        assertTrue(router.keyReleased(-1, 101, 0));
        router.clearKeyboardFocus();
        assertEquals(java.util.List.of(101, 100), released);
        assertFalse(router.keyReleased(-1, 100, 0));
    }

    static class Listener implements GuiEventListener {
        int presses, releases, drags, keyReleases;
        boolean focused;
        double scrollY;
        char character;
        Vector2f position = new Vector2f();
        Vector2f delta = new Vector2f();
        @Override public boolean mouseClicked(double x, double y, int button) {
            presses++; position.set((float) x, (float) y); return true;
        }
        @Override public boolean mouseReleased(double x, double y, int button) {
            releases++; position.set((float) x, (float) y); return true;
        }
        @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
            drags++; position.set((float) x, (float) y); delta.set((float) dx, (float) dy); return true;
        }
        @Override public boolean mouseScrolled(double x, double y, double dx, double dy) { scrollY = dy; return true; }
        @Override public boolean keyPressed(int key, int scanCode, int modifiers) { return true; }
        @Override public boolean keyReleased(int key, int scanCode, int modifiers) { keyReleases++; return true; }
        @Override public boolean charTyped(char c, int modifiers) { character = c; return true; }
        @Override public void setFocused(boolean focused) { this.focused = focused; }
        @Override public boolean isFocused() { return focused; }
    }
}
