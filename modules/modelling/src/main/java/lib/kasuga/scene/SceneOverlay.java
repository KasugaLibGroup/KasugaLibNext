package lib.kasuga.scene;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Matrix4f;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Scoped minimal scene UI: camera-facing world prompts and a small bottom-left track display. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class SceneOverlay implements AutoCloseable {
    private static final Set<SceneOverlay> OVERLAYS = new LinkedHashSet<>();
    /** Null means that this overlay is a global transition and survives a level change. */
    private Object level;
    private final Set<Prompt> prompts = new LinkedHashSet<>();
    private Track track;
    private Fade fade;
    private boolean closed;
    private SceneOverlay(Object level) { this.level = level; OVERLAYS.add(this); }
    public static SceneOverlay open() { return new SceneOverlay(Minecraft.getInstance().level); }
    /** Opens a transition overlay before a scene changes the client level. */
    public static SceneOverlay openGlobal() { return new SceneOverlay(null); }
    public static int activeCount() { return OVERLAYS.size(); }

    /** Attaches a global transition to the current level so world prompts can render. */
    public SceneOverlay attachToCurrentLevel() {
        if (closed) throw new IllegalStateException("Overlay is closed");
        level = Minecraft.getInstance().level;
        return this;
    }
    public Prompt prompt(Vec3 position, String key, String label) {
        if (closed) throw new IllegalStateException("Overlay is closed");
        var prompt = new Prompt(position, key, label);
        prompt.init(Minecraft.getInstance(), 150, 32); prompts.add(prompt); return prompt;
    }
    public Track track(String title, String artist, String album, float durationSeconds) {
        if (closed) throw new IllegalStateException("Overlay is closed");
        return track = new Track(title, artist, album, durationSeconds);
    }
    /** Starts a black-to-transparent scene transition owned by this overlay. */
    public Fade fadeFromBlack(float seconds) {
        if (closed) throw new IllegalStateException("Overlay is closed");
        if (!Float.isFinite(seconds) || seconds <= 0f) throw new IllegalArgumentException("Fade duration must be positive");
        return fade = new Fade(seconds);
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (Constants.UI_BACKEND == null) return;
        for (var overlay : OVERLAYS) for (var prompt : overlay.prompts) {
            if (!prompt.visible || overlay.level != null && mc.level != overlay.level) {
                Constants.UI_BACKEND.removeRenderable(prompt); continue;
            }
            var transform = new Matrix4f().translation((float) prompt.position.x, (float) prompt.position.y,
                    (float) prompt.position.z).rotate(mc.gameRenderer.getMainCamera().rotation())
                    .scale(.006f, -.006f, 1).translate(-75, -16, 0);
            if (Constants.UI_BACKEND.containsRenderable(prompt)) Constants.UI_BACKEND.setTransform(prompt, transform);
            else Constants.UI_BACKEND.addRenderable(prompt, transform);
        }
    }
    @SubscribeEvent public static void gui(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        for (var overlay : OVERLAYS) if (overlay.level == null || overlay.level == mc.level) {
            if (overlay.track != null && overlay.track.visible) overlay.track.render(event.getGuiGraphics());
            if (overlay.fade != null && overlay.fade.visible()) overlay.fade.render(event.getGuiGraphics());
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        for (var overlay : List.copyOf(OVERLAYS)) {
            if (overlay.level != null && Minecraft.getInstance().level != overlay.level) overlay.close();
            else if (overlay.fade != null) overlay.fade.tick(.05f);
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (Constants.UI_BACKEND != null) for (var prompt : prompts) Constants.UI_BACKEND.removeRenderable(prompt);
        if (fade != null) fade.close();
        prompts.clear(); OVERLAYS.remove(this);
    }

    public static final class Prompt extends Screen {
        private final Vec3 position;
        private final String key, label;
        private boolean visible;
        private Prompt(Vec3 position, String key, String label) {
            super(Component.literal(label)); this.position = position; this.key = key; this.label = label;
        }
        public Prompt visible(boolean value) { visible = value; return this; }
        public boolean visible() { return visible; }
        @Override public void render(GuiGraphics gui, int x, int y, float partial) {
            ring(gui, 24, 16, 11, 0xFFF6FAFF);
            gui.drawString(font, key, 24 - font.width(key) / 2, 12, 0xFFF6FAFF, false);
            gui.drawString(font, label, 45, 12, 0xFFF6FAFF, false);
        }
        @Override public void renderBackground(GuiGraphics gui, int x, int y, float partial) {}
    }

    public static final class Track {
        private final String title, artist, album;
        private final float duration;
        private boolean visible;
        private float seconds;
        private Track(String title, String artist, String album, float duration) {
            this.title = title; this.artist = artist; this.album = album; this.duration = duration;
        }
        public Track visible(boolean value) { visible = value; return this; }
        public Track seconds(float value) { seconds = Math.max(0, value); return this; }
        public boolean visible() { return visible; }
        private void render(GuiGraphics gui) {
            var minecraft = Minecraft.getInstance();
            var font = minecraft.font;
            float scale = Math.min(1f, 2f / (float) minecraft.getWindow().getGuiScale());
            int widthAvailable = Math.round(gui.guiWidth() / scale);
            int heightAvailable = Math.round(gui.guiHeight() / scale);
            gui.pose().pushPose();
            gui.pose().scale(scale, scale, 1);
            int left = 22, top = heightAvailable - 65;
            gui.fill(left, top + 3, left + 2, top + 12, 0xEAF4F8FF);
            gui.fill(left + 5, top + 3, left + 7, top + 12, 0xEAF4F8FF);
            gui.drawString(font, title, left + 18, top, 0xFFF4F8FF, false);
            gui.drawString(font, artist + "  /  " + album, left + 18, top + 14, 0xD6DBE8F7, false);
            int width = Math.min(216, widthAvailable - left * 2);
            gui.fill(left, top + 33, left + width, top + 34, 0x507A9ABF);
            gui.fill(left, top + 33, left + Math.round(width * Math.clamp(seconds / Math.max(duration, 1), 0, 1)), top + 34, 0xEAF4F8FF);
            gui.pose().popPose();
        }
    }

    public static final class Fade implements AutoCloseable {
        private final float duration;
        private float elapsed;
        private boolean closed, paused;

        private Fade(float duration) { this.duration = duration; }

        private void tick(float seconds) {
            if (closed || paused) return;
            elapsed = Math.min(duration, elapsed + Math.max(0f, seconds));
        }

        /** Holds the black frame while a stage is being prepared or teleported. */
        public Fade pause() { paused = true; return this; }

        /** Starts or resumes the fade into the scene. */
        public Fade resume() { paused = false; return this; }

        public boolean paused() { return paused; }

        public float alpha() {
            if (closed) return 0f;
            float t = Math.clamp(elapsed / duration, 0f, 1f);
            float eased = t * t * t * (t * (t * 6f - 15f) + 10f);
            return 1f - eased;
        }

        public boolean visible() { return !closed && alpha() > 0f; }

        private void render(GuiGraphics gui) {
            int alpha = Math.clamp(Math.round(alpha() * 255f), 0, 255);
            if (alpha > 0) gui.fill(0, 0, gui.guiWidth(), gui.guiHeight(), alpha << 24);
        }

        @Override public void close() { closed = true; elapsed = duration; }
    }
    private static void ring(GuiGraphics gui, int cx, int cy, int radius, int color) {
        for (int y = -radius - 1; y <= radius + 1; y++) for (int x = -radius - 1; x <= radius + 1; x++) {
            float coverage = Math.clamp(1.2f - Math.abs((float) Math.sqrt(x * x + y * y) - radius), 0, 1);
            if (coverage > 0) gui.fill(cx + x, cy + y, cx + x + 1, cy + y + 1,
                    Math.round(coverage * (color >>> 24)) << 24 | color & 0xFFFFFF);
        }
    }
}
