package lib.kasuga.uip.ldlib;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;

/** LDLib2 control backed by the shared model loader preview, in 2D or a world panel. */
public final class ModelPreviewElement extends UIElement implements AutoCloseable {
    private final GuiModelPreview preview;

    public ModelPreviewElement(GuiModelPreview preview) {
        this.preview = java.util.Objects.requireNonNull(preview);
        layout(layout -> layout.width(160).height(160));
    }

    @Override public void drawBackgroundAdditional(GUIContext context) {
        preview.render(context.graphics, getContentX(), getContentY(),
                Math.round(getContentWidth()), Math.round(getContentHeight()), context.partialTick);
    }

    @Override protected void onRemoved() {
        try { close(); } finally { super.onRemoved(); }
    }

    public GuiModelPreview preview() { return preview; }
    @Override public void close() { preview.close(); }
}
