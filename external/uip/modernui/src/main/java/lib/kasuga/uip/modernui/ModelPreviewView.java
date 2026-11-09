package lib.kasuga.uip.modernui;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MinecraftSurfaceView;
import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** ModernUI view; its public Minecraft render callback owns every model/GPU operation. */
public final class ModelPreviewView extends MinecraftSurfaceView {
    private final ResourceLocation model;
    private final String modelName;
    private volatile float yaw = -.55f, pitch = .25f, zoom = 1;
    private volatile GuiModelPreview preview;
    private volatile long renderedFrames;
    private volatile long releasedPreviews;
    private volatile boolean attached;

    public ModelPreviewView(Context context, ResourceLocation model, String modelName) {
        super(context);
        this.model = java.util.Objects.requireNonNull(model);
        this.modelName = modelName;
        setRenderer(new Renderer() {
            private int width, height;
            @Override public void onSurfaceChanged(int width, int height) { this.width = width; this.height = height; }
            @Override public void onDraw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                         double guiScale, float alpha) {
                if (!attached) return;
                var current = preview;
                if (current == null) {
                    current = new GuiModelPreview(ModelPreviewView.this.model, ModelPreviewView.this.modelName);
                    preview = current;
                    if (!attached) { current.close(); preview = null; return; }
                }
                current.setView(yaw, pitch, zoom);
                if (alpha > 0 && current.render(graphics, 0, 0, Math.max(1, (int) Math.round(width / guiScale)),
                        Math.max(1, (int) Math.round(height / guiScale)), partialTick, alpha)) renderedFrames++;
            }
        });
    }

    public void setModelView(float yaw, float pitch, float zoom) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(zoom) || zoom <= 0)
            throw new IllegalArgumentException("Invalid preview view");
        this.yaw = yaw; this.pitch = pitch; this.zoom = zoom;
    }

    public long renderedFrames() { return renderedFrames; }
    public long releasedPreviews() { return releasedPreviews; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        super.onDetachedFromWindow();
        // This callback runs on ModernUI's UI thread; defer ownership cleanup to the MC render thread.
        var previous = preview;
        preview = null;
        if (previous != null) Minecraft.getInstance().execute(() -> { previous.close(); releasedPreviews++; });
    }
}
