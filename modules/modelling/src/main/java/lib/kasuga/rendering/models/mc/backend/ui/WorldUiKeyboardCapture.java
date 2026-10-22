package lib.kasuga.rendering.models.mc.backend.ui;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/** Temporary keyboard ownership. Mouse capture and the active Minecraft Screen remain unchanged. */
final class WorldUiKeyboardCapture {
    private final WorldUiInputRouter router;
    private final Runnable releaseGameplayKeys;
    private final Set<PhysicalKey> held = new HashSet<>();
    private final Set<PhysicalKey> swallowed = new HashSet<>();
    private boolean active;

    WorldUiKeyboardCapture(WorldUiInputRouter router, Runnable releaseGameplayKeys) {
        this.router = router;
        this.releaseGameplayKeys = releaseGameplayKeys;
    }

    boolean refresh(boolean allowed) {
        boolean requested = allowed && hasTextFocus(router.keyboardFocus());
        if (requested && !active) {
            active = true;
            swallowed.addAll(held);
            releaseGameplayKeys.run();
        } else if (!requested) cancel();
        return active;
    }

    boolean key(int key, int scanCode, int action, int modifiers, boolean allowed) {
        PhysicalKey physical = new PhysicalKey(key, scanCode);
        if (action == GLFW.GLFW_RELEASE) held.remove(physical);
        else if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) held.add(physical);
        refresh(allowed);
        if (action == GLFW.GLFW_RELEASE) {
            boolean owned = swallowed.remove(physical);
            if (active) router.keyReleased(key, scanCode, modifiers);
            return owned || active;
        }
        if (!active) {
            // Old held keys must not resume gameplay through GLFW_REPEAT after Escape.
            if (action == GLFW.GLFW_REPEAT && swallowed.contains(physical)) return true;
            if (action == GLFW.GLFW_PRESS) swallowed.remove(physical); // Fresh down after focus loss.
            return false;
        }
        if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) {
            swallowed.add(physical);
            if (key == GLFW.GLFW_KEY_ESCAPE) cancel();
            else {
                router.keyPressed(key, scanCode, modifiers);
                refresh(allowed); // Tab or custom callbacks can move focus away from text.
            }
        }
        // Consume even keys that the widget declines, including gameplay and debug shortcuts.
        return true;
    }

    boolean character(int codePoint, int modifiers, boolean allowed) {
        if (!refresh(allowed)) return false;
        if (Character.isValidCodePoint(codePoint)) {
            for (char character : Character.toChars(codePoint)) router.charTyped(character, modifiers);
        }
        return true;
    }

    boolean active() { return active; }

    void cancel() {
        if (!active) return;
        active = false;
        router.clearKeyboardFocus();
        releaseGameplayKeys.run();
    }

    private static boolean hasTextFocus(GuiEventListener root) {
        Set<GuiEventListener> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        GuiEventListener current = root;
        while (current != null && visited.add(current)) {
            if (current instanceof AbstractWidget widget && (!widget.active || !widget.visible)) return false;
            if (current instanceof EditBox text) return text.canConsumeInput();
            if (current instanceof MultiLineEditBox text) return text.isFocused();
            current = current instanceof ContainerEventHandler container ? container.getFocused() : null;
        }
        return false;
    }

    private record PhysicalKey(int key, int scanCode) {}
}
