package lib.kasuga.rendering.models.mc.backend.ui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorldUiKeyboardCaptureTest {
    @Test void textFocusCapturesUnconsumedHotkeysAndUnicodeWithoutOpeningAScreen() {
        var fixture = new Fixture();
        assertTrue(fixture.capture.refresh(true));
        assertTrue(fixture.capture.active());
        assertEquals(1, fixture.releaseGameplay.get());
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_PRESS, 0, true));
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_REPEAT, 0, true));
        assertEquals(2, fixture.root.keyPresses); // The listener deliberately returns false.
        assertTrue(fixture.capture.character('w', 0, true));
        assertTrue(fixture.capture.character('中', 0, true));
        assertEquals("w中", fixture.text.getValue());
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_RELEASE, 0, true));
        assertEquals(1, fixture.root.keyReleases);
        assertEquals(1, fixture.releaseGameplay.get());
    }

    @Test void escapeClearsNestedTextFocusAndDoesNotReenterUntilClickedAgain() {
        var fixture = new Fixture();
        fixture.capture.refresh(true);
        fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_PRESS, 0, true);
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_ESCAPE, 1, GLFW.GLFW_PRESS, 0, true));
        assertFalse(fixture.capture.active());
        assertNull(fixture.router.keyboardFocus());
        assertFalse(fixture.text.isFocused());
        assertEquals(1, fixture.root.keyReleases);
        assertEquals(2, fixture.releaseGameplay.get());
        assertFalse(fixture.capture.refresh(true));
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_REPEAT, 0, true));
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_RELEASE, 0, true));
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_ESCAPE, 1, GLFW.GLFW_RELEASE, 0, true));
        assertFalse(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_PRESS, 0, true));
        fixture.selectText();
        assertTrue(fixture.capture.refresh(true));
    }

    @Test void normalButtonsHiddenReadOnlyAndDisabledTextDoNotCapture() {
        for (String mode : List.of("button", "hidden", "readonly", "disabled")) {
            var fixture = new Fixture();
            switch (mode) {
                case "button" -> fixture.nested.setFocused(fixture.button);
                case "hidden" -> fixture.text.visible = false;
                case "readonly" -> fixture.text.setEditable(false);
                case "disabled" -> fixture.text.active = false;
            }
            assertFalse(fixture.capture.refresh(true), mode);
            assertFalse(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_PRESS, 0, true), mode);
            assertFalse(fixture.capture.character('a', 0, true), mode);
        }
    }

    @Test void focusLossAndRemovalBalanceKeysAndLeaveFreshPressesAvailable() {
        for (boolean remove : new boolean[]{false, true}) {
            var fixture = new Fixture();
            fixture.capture.refresh(true);
            fixture.capture.key(GLFW.GLFW_KEY_T, 11, GLFW.GLFW_PRESS, 0, true);
            if (remove) fixture.router.remove(fixture.root);
            assertFalse(fixture.capture.refresh(remove));
            assertFalse(fixture.capture.active());
            assertEquals(1, fixture.root.keyReleases);
            assertTrue(fixture.capture.key(GLFW.GLFW_KEY_T, 11, GLFW.GLFW_REPEAT, 0, false));
            assertFalse(fixture.capture.key(GLFW.GLFW_KEY_T, 11, GLFW.GLFW_PRESS, 0, false));
        }
    }

    @Test void pointerScreenAndNoTextFocusKeepTheirOwnKeyboardPath() {
        var fixture = new Fixture();
        assertFalse(fixture.capture.refresh(false));
        assertSame(fixture.root, fixture.router.keyboardFocus());
        assertTrue(fixture.text.isFocused());
        assertFalse(fixture.capture.character('a', 0, false));
        assertEquals(0, fixture.releaseGameplay.get());
    }

    @Test void invalidCodePointsAreConsumedAndUnmappedKeysUseScanCodes() {
        var fixture = new Fixture();
        assertTrue(fixture.capture.character(-1, 0, true));
        fixture.capture.key(GLFW.GLFW_KEY_UNKNOWN, 100, GLFW.GLFW_PRESS, 0, true);
        fixture.capture.key(GLFW.GLFW_KEY_ESCAPE, 1, GLFW.GLFW_PRESS, 0, true);
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_UNKNOWN, 100, GLFW.GLFW_RELEASE, 0, true));
        assertFalse(fixture.capture.key(GLFW.GLFW_KEY_UNKNOWN, 101, GLFW.GLFW_RELEASE, 0, true));
    }

    @Test void disablingOrHidingAnActiveEditorEndsCaptureAndBalancesItsKeys() {
        for (String mode : List.of("hidden", "readonly", "disabled")) {
            var fixture = new Fixture();
            fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_PRESS, 0, true);
            switch (mode) {
                case "hidden" -> fixture.text.visible = false;
                case "readonly" -> fixture.text.setEditable(false);
                case "disabled" -> fixture.text.active = false;
            }
            assertFalse(fixture.capture.refresh(true), mode);
            assertEquals(1, fixture.root.keyReleases, mode);
            assertNull(fixture.router.keyboardFocus(), mode);
            assertTrue(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_RELEASE, 0, true), mode);
        }
    }

    @Test void tabAwayFromTextExitsCaptureInTheSameKeyboardEvent() {
        var fixture = new Fixture();
        fixture.root.onKey = () -> fixture.nested.setFocused(fixture.button);
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_TAB, 15, GLFW.GLFW_PRESS, 0, true));
        assertFalse(fixture.capture.active());
        assertNull(fixture.router.keyboardFocus());
        assertEquals(1, fixture.root.keyReleases);
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_TAB, 15, GLFW.GLFW_RELEASE, 0, true));
    }

    @Test void supplementaryUnicodeAndEscapeRepeatsFollowTheNativeCharacterContract() {
        var fixture = new Fixture();
        assertTrue(fixture.capture.character(0x1F600, 0, true));
        assertEquals("\uD83D\uDE00", fixture.text.getValue());
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_ESCAPE, 1, GLFW.GLFW_PRESS, 0, true));
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_ESCAPE, 1, GLFW.GLFW_REPEAT, 0, true));
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_ESCAPE, 1, GLFW.GLFW_RELEASE, 0, true));
        assertFalse(fixture.capture.key(GLFW.GLFW_KEY_ESCAPE, 1, GLFW.GLFW_PRESS, 0, true));
        assertEquals(2, fixture.releaseGameplay.get());
    }

    @Test void emptyOrCyclicFocusChainsCannotCaptureOrLoop() {
        var fixture = new Fixture();
        fixture.router.clearKeyboardFocus();
        assertFalse(fixture.capture.refresh(true));
        fixture.root.setFocused(fixture.root);
        fixture.router.press(200, 200, 0);
        assertFalse(fixture.capture.refresh(true));
        fixture.router.clearKeyboardFocus();
        assertNull(fixture.root.getFocused());
    }

    @Test void keysHeldBeforeTextFocusCannotResumeThroughRepeatAfterEscape() {
        var fixture = new Fixture();
        assertFalse(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_PRESS, 0, false));
        assertTrue(fixture.capture.refresh(true));
        fixture.capture.key(GLFW.GLFW_KEY_ESCAPE, 1, GLFW.GLFW_PRESS, 0, true);
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_REPEAT, 0, true));
        assertTrue(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_RELEASE, 0, true));
        assertFalse(fixture.capture.key(GLFW.GLFW_KEY_W, 10, GLFW.GLFW_PRESS, 0, true));
    }

    private static final class Fixture {
        final WorldUiInputRouter router = new WorldUiInputRouter();
        final EditBox text = new EditBox(null, 0, 0, 200, 20, Component.empty());
        final WorldUiInputRouterTest.Listener button = new WorldUiInputRouterTest.Listener();
        final Container nested = new Container(List.of(text, button));
        final Container root = new Container(List.of(nested));
        final AtomicInteger releaseGameplay = new AtomicInteger();
        final WorldUiKeyboardCapture capture = new WorldUiKeyboardCapture(router, releaseGameplay::incrementAndGet);
        Fixture() {
            router.beginFrame(new Matrix4f().perspective(1, 1, .05f, 100), 400, 400, 200, 200);
            router.addSurface(root, new Matrix4f().translation(-1, 1, -2).scale(.01f, -.01f, 1), 200, 200);
            router.endFrame();
            selectText();
        }
        void selectText() {
            root.setFocused(nested);
            nested.setFocused(text);
            router.press(200, 200, 0);
            router.release(200, 200, 0);
        }
    }

    private static final class Container extends AbstractContainerEventHandler {
        final List<? extends GuiEventListener> children;
        int keyPresses, keyReleases;
        Runnable onKey = () -> {};
        Container(List<? extends GuiEventListener> children) { this.children = children; }
        @Override public List<? extends GuiEventListener> children() { return children; }
        @Override public boolean mouseClicked(double x, double y, int button) { return true; }
        @Override public boolean keyPressed(int key, int scanCode, int modifiers) { keyPresses++; onKey.run(); return false; }
        @Override public boolean keyReleased(int key, int scanCode, int modifiers) { keyReleases++; return false; }
    }
}
