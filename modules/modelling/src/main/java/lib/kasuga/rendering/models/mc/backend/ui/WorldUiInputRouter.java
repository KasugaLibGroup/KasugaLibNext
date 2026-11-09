package lib.kasuga.rendering.models.mc.backend.ui;

import lib.kasuga.rendering.models.mc.util.RayTracingHelper;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Client-thread input state. Geometry is replaced each frame; button ownership survives hover changes. */
public final class WorldUiInputRouter {
    private final Map<GuiEventListener, Surface> surfaces = new LinkedHashMap<>();
    private final Map<Integer, Capture> buttons = new LinkedHashMap<>();
    private final Map<PhysicalKey, KeyCapture> keys = new LinkedHashMap<>();
    private ScreenPointerProjection projection;
    private GuiEventListener hovered;
    private GuiEventListener keyboardFocus;
    private Vector2f hoverPosition;
    private final Vector3f rayOrigin = new Vector3f();
    private final Vector3f rayDirection = new Vector3f();
    private boolean validRay;
    private boolean pointerInside;
    private boolean resetting;
    private double viewportWidth;
    private double viewportHeight;
    private float maxDistance = 5;

    public void setMaxDistance(float distance) {
        if (!(distance > 0) || !Float.isFinite(distance)) throw new IllegalArgumentException("Invalid UI reach");
        maxDistance = distance;
    }

    public void beginFrame(Matrix4fc projection, double width, double height, double mouseX, double mouseY) {
        surfaces.clear();
        try {
            this.projection = new ScreenPointerProjection(projection, width, height);
        } catch (IllegalArgumentException exception) {
            reset();
            throw exception;
        }
        viewportWidth = width;
        viewportHeight = height;
        updateRay(mouseX, mouseY);
    }

    /** The matrix must include every transform used to draw the UI, including the host model-view. */
    public void addSurface(GuiEventListener listener, Matrix4fc localToView, int width, int height) {
        addSurface(listener, localToView, 0, 0, width, height);
    }

    public void addSurface(GuiEventListener listener, Matrix4fc localToView, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0 || !localToView.isFinite() || !localToView.isAffine()
                || localToView.determinant() == 0) return;
        surfaces.put(listener, new Surface(new Matrix4f(localToView), x, y, width, height));
    }

    /** Current local coordinates, also available outside the rectangle for rendering and dragging. */
    public @Nullable Vector2f positionOn(GuiEventListener listener) {
        Hit hit = project(surfaces.get(listener));
        return hit == null ? null : hit.position;
    }

    /** Call after all surfaces are registered, including when the cursor has not moved this frame. */
    public void endFrame() {
        for (var entry : Map.copyOf(buttons).entrySet()) {
            if (!surfaces.containsKey(entry.getValue().listener)) releaseCapture(entry.getKey());
        }
        if (keyboardFocus != null && !surfaces.containsKey(keyboardFocus)) setKeyboardFocus(null);
        dispatchMove();
    }

    public void move(double x, double y) {
        updateRay(x, y);
        dispatchMove();
    }

    private void updateRay(double x, double y) {
        validRay = projection != null && projection.ray(x, y, rayOrigin, rayDirection);
        pointerInside = x >= 0 && y >= 0 && x < viewportWidth && y < viewportHeight;
    }

    private void dispatchMove() {
        GuiEventListener next = null;
        Hit closest = null;
        for (var entry : surfaces.entrySet()) {
            Hit hit = project(entry.getValue());
            if (pointerInside && hit != null && hit.distance <= maxDistance && entry.getValue().contains(hit.position)
                    && (closest == null || hit.distance < closest.distance)) {
                next = entry.getKey();
                closest = hit;
            }
        }
        GuiEventListener previous = hovered;
        hovered = next;
        hoverPosition = closest == null ? null : closest.position;
        if (previous != null && previous != next) previous.mouseMoved(-1, -1);
        if (next != null && hovered == next && hoverPosition != null) next.mouseMoved(hoverPosition.x, hoverPosition.y);

        for (var entry : Map.copyOf(buttons).entrySet()) {
            Capture capture = entry.getValue();
            if (buttons.get(entry.getKey()) != capture) continue;
            Vector2f position = positionOn(capture.listener);
            if (position == null) continue;
            float dx = position.x - capture.position.x;
            float dy = position.y - capture.position.y;
            capture.position.set(position);
            if (dx != 0 || dy != 0) capture.listener.mouseDragged(position.x, position.y, entry.getKey(), dx, dy);
        }
    }

    public boolean press(double x, double y, int button) {
        move(x, y);
        if (hovered == null) return false;
        GuiEventListener target = hovered;
        Vector2f position = new Vector2f(hoverPosition);
        releaseCapture(button);
        setKeyboardFocus(target);
        if (keyboardFocus != target || !surfaces.containsKey(target)) return true;
        // Install ownership before invoking user code, so removal/closing from a callback can cancel it.
        Capture capture = new Capture(target, position);
        buttons.put(button, capture);
        boolean accepted = target.mouseClicked(position.x, position.y, button);
        if (!accepted) buttons.remove(button, capture);
        return true;
    }

    public boolean release(double x, double y, int button) {
        move(x, y);
        return releaseCapture(button);
    }

    private boolean releaseCapture(int button) {
        Capture capture = buttons.remove(button);
        if (capture == null) return false;
        capture.listener.mouseReleased(capture.position.x, capture.position.y, button);
        return true;
    }

    public boolean scroll(double x, double y, double deltaX, double deltaY) {
        move(x, y);
        if (hovered == null) return false;
        hovered.mouseScrolled(hoverPosition.x, hoverPosition.y, deltaX, deltaY);
        return true;
    }

    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (keyboardFocus == null) return false;
        GuiEventListener target = keyboardFocus;
        keys.putIfAbsent(new PhysicalKey(key, scanCode), new KeyCapture(target, scanCode, modifiers));
        return target.keyPressed(key, scanCode, modifiers);
    }

    public boolean keyReleased(int key, int scanCode, int modifiers) {
        KeyCapture capture = keys.remove(new PhysicalKey(key, scanCode));
        return capture != null && capture.listener.keyReleased(key, scanCode, modifiers);
    }

    public boolean charTyped(char character, int modifiers) {
        return keyboardFocus != null && keyboardFocus.charTyped(character, modifiers);
    }

    private void setKeyboardFocus(@Nullable GuiEventListener target) {
        if (keyboardFocus == target) return;
        GuiEventListener previous = keyboardFocus;
        keyboardFocus = target;
        for (var entry : Map.copyOf(keys).entrySet()) {
            KeyCapture capture = keys.remove(entry.getKey());
            if (capture != null) capture.listener.keyReleased(entry.getKey().key, capture.scanCode, capture.modifiers);
        }
        if (previous != null) clearFocusChain(previous);
        if (target != null && keyboardFocus == target) target.setFocused(true);
    }

    private static void clearFocusChain(GuiEventListener root) {
        Set<GuiEventListener> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        GuiEventListener current = root;
        while (current != null && visited.add(current)) {
            GuiEventListener next = null;
            if (current instanceof ContainerEventHandler container) {
                next = container.getFocused();
                container.setFocused((GuiEventListener) null);
            }
            current.setFocused(false);
            current = next;
        }
    }

    public @Nullable GuiEventListener hovered() { return hovered; }
    public @Nullable GuiEventListener keyboardFocus() { return keyboardFocus; }
    public void clearKeyboardFocus() { setKeyboardFocus(null); }
    public @Nullable Vector2f hoverPosition() { return hoverPosition == null ? null : new Vector2f(hoverPosition); }

    public float distanceOn(GuiEventListener listener) {
        Hit hit = project(surfaces.get(listener));
        Surface surface = surfaces.get(listener);
        return hit != null && hit.distance <= maxDistance && surface.contains(hit.position)
                ? hit.distance : Float.MAX_VALUE;
    }

    public void remove(GuiEventListener listener) {
        surfaces.remove(listener);
        for (var entry : Map.copyOf(buttons).entrySet()) {
            if (entry.getValue().listener == listener) releaseCapture(entry.getKey());
        }
        if (hovered == listener) {
            hovered = null;
            hoverPosition = null;
            listener.mouseMoved(-1, -1);
        }
        if (keyboardFocus == listener) setKeyboardFocus(null);
    }

    /** Balance releases on Escape, screen replacement, window focus loss, or disconnect. */
    public void reset() {
        if (resetting) return;
        resetting = true;
        GuiEventListener previous = hovered;
        hovered = null;
        hoverPosition = null;
        projection = null;
        validRay = false;
        pointerInside = false;
        surfaces.clear();
        try {
            for (int button : Map.copyOf(buttons).keySet()) releaseCapture(button);
            setKeyboardFocus(null);
            if (previous != null) previous.mouseMoved(-1, -1);
        } finally {
            resetting = false;
        }
    }

    private @Nullable Hit project(@Nullable Surface surface) {
        if (!validRay || surface == null) return null;
        Vector3f point = new Vector3f();
        float distance = RayTracingHelper.intersectRayWithPlane(rayOrigin, rayDirection,
                surface.transform, surface.normal, point, true);
        if (distance < 0 || !Float.isFinite(distance)) return null;
        surface.inverse.transformPosition(point);
        return point.isFinite() ? new Hit(new Vector2f(point.x, point.y), distance) : null;
    }

    private static final class Surface {
        final Matrix4f transform;
        final Matrix4f inverse;
        final Matrix3f normal;
        final int x;
        final int y;
        final int width;
        final int height;
        Surface(Matrix4f transform, int x, int y, int width, int height) {
            this.transform = transform;
            inverse = new Matrix4f(transform).invert();
            normal = transform.normal(new Matrix3f());
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
        boolean contains(Vector2f position) {
            return position.x >= x && position.y >= y && position.x < (double) x + width && position.y < (double) y + height;
        }
    }

    private record Hit(Vector2f position, float distance) {}
    private record Capture(GuiEventListener listener, Vector2f position) {}
    private record PhysicalKey(int key, int scanCode) {}
    private record KeyCapture(GuiEventListener listener, int scanCode, int modifiers) {}
}
