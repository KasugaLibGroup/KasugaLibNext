package lib.kasuga.rendering.models.mc.backend.ui;

import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Small usable viewer shared by structure, Create and projection model sources. */
public final class MinecraftModelPreviewScreen extends Screen {
    private final GuiModelPreview preview;
    private final Runnable returnTo;
    private final int minimumLayer, maximumLayer;
    private int layer;
    private boolean cumulative = true, all = true;
    private float yaw = -.55f, pitch = .25f, zoom = 1, panX, panY;
    private int dragButton = -1;
    private double dragDistance;
    private lib.kasuga.rendering.models.mc.scene.MinecraftModelSource.BlockHit selectedBlock;
    public lib.kasuga.rendering.models.mc.scene.MinecraftModelSource.BlockHit selectedBlock() { return selectedBlock; }

    public MinecraftModelPreviewScreen(Component title, GuiModelPreview preview, int minimumLayer, int maximumLayer) {
        this(title, preview, minimumLayer, maximumLayer, null);
    }

    /** Optional return action lets a host show a source selector when the viewer is closed. */
    public MinecraftModelPreviewScreen(Component title, GuiModelPreview preview, int minimumLayer, int maximumLayer, Runnable returnTo) {
        super(title);
        if (minimumLayer > maximumLayer) throw new IllegalArgumentException("Invalid layer range");
        this.preview = java.util.Objects.requireNonNull(preview);
        this.returnTo = returnTo;
        this.minimumLayer = minimumLayer; this.maximumLayer = maximumLayer; layer = maximumLayer;
    }

    @Override protected void init() {
        dragButton = -1; setDragging(false);
        int bottom = height - 30;
        addRenderableWidget(Button.builder(Component.literal("◀"), button -> { all = false; layer = Math.max(minimumLayer, layer - 1); updateLayers(); })
                .bounds(12, bottom, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), button -> { all = false; layer = Math.min(maximumLayer, layer + 1); updateLayers(); })
                .bounds(40, bottom, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("All"), button -> { all = true; updateLayers(); })
                .bounds(68, bottom, 34, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stack / slice"), button -> { all = false; cumulative = !cumulative; updateLayers(); })
                .bounds(106, bottom, 86, 20).build());
        addRenderableWidget(new AbstractSliderButton(200, bottom, Math.max(72, width - 212), 20,
                Component.literal("Explode"), Math.min(1, preview.layers().separation() / 3)) {
            { updateMessage(); }
            @Override protected void updateMessage() { setMessage(Component.literal("Explode " + Math.round(value * 100) + "%")); }
            @Override protected void applyValue() { preview.setLayers(preview.layers().separation(value * 3)); }
        });
    }

    private void updateLayers() {
        preview.setLayers(preview.layers().range(all ? Integer.MIN_VALUE : cumulative ? minimumLayer : layer,
                all ? Integer.MAX_VALUE : layer));
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xEE121B27);
        preview.render(graphics, 8, 30, width - 16, Math.max(1, height - 72), partialTick);
        graphics.drawString(font, title, 12, 12, 0xFFE8F5FF);
        String controls = "Click inspect | LMB rotate | MMB pan | Wheel zoom | R reset";
        graphics.drawString(font, controls, Math.max(12, width - 12 - font.width(controls)), 22, 0xFFB8CBE0);
        graphics.drawString(font, all ? "All layers" : "Layer " + layer + (cumulative ? " / stack" : " / slice"), 12, height - 44, 0xFFB8CBE0);
        if (preview.failure() != null) graphics.drawString(font, preview.failure(), 12, 32, 0xFFFFA090);
        if (selectedBlock != null) ModelBlockInfo.draw(graphics, font, selectedBlock, 12, 38);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    private boolean inPreview(double x, double y) {
        return x >= 8 && x < width - 8 && y >= 30 && y < height - 42;
    }
    private void updateView() { preview.setView(yaw, pitch, zoom, panX, panY); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (inPreview(x, y) && (button == 0 || button == 2)) {
            dragButton = button; dragDistance = 0; setFocused(null); setDragging(true); return true;
        }
        return false;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragButton == button && button == 0) {
            dragDistance += Math.hypot(dx, dy);
            yaw += (float) dx * .01f; pitch = Math.clamp(pitch + (float) dy * .01f, -1.5f, 1.5f);
            updateView(); return true;
        }
        if (dragButton == button && button == 2) {
            panX += (float) (dx / Math.max(1, width - 16));
            panY += (float) (dy / Math.max(1, height - 72));
            updateView(); return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == dragButton) {
            if (button == 0 && dragDistance <= 3 && inPreview(x, y))
                selectedBlock = preview.pick((x - 8) / Math.max(1, width - 16), (y - 30) / Math.max(1, height - 72)).orElse(null);
            dragButton = -1; setDragging(false); return true;
        }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!inPreview(x, y)) return super.mouseScrolled(x, y, horizontal, vertical);
        zoom = Math.clamp(zoom * (float) Math.exp(vertical * .1), .1f, 10);
        updateView(); return true;
    }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_R) {
            yaw = -.55f; pitch = .25f; zoom = 1; panX = panY = 0;
            dragButton = -1; setDragging(false); updateView(); return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }
    @Override public void onClose() { if (returnTo == null) super.onClose(); else returnTo.run(); }
    @Override public boolean isPauseScreen() { return false; }
    // Screen.render() invokes renderBackground() before its child controls. This viewer has
    // already drawn its own canvas, so the default menu blur would blur the model and title.
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public void removed() { preview.close(); }
}
