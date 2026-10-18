package lib.kasuga.rendering.effect.debug;

import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import lib.kasuga.rendering.effect.shader.RenderShaderRegistry;
import lib.kasuga.rendering.effect.shader.ShaderParameterBlock;
import lib.kasuga.shader.ShaderParameter;
import lib.kasuga.shader.ShaderParameterType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Schema-driven editor for the default runtime values exposed by registered shaders. */
public final class ShaderParameterScreen extends Screen {
    private static final int PANEL_WIDTH = 620;
    private static final int HORIZONTAL_MARGIN = 8;
    private static final int CONTROL_GAP = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int CONTROL_HEIGHT = 20;
    private static final int SCROLL_STEP = 24;

    private final Screen parent;
    private final List<RenderShaderHandle> shaders = new ArrayList<>();
    private final List<ParameterView> views = new ArrayList<>();
    private ResourceLocation selectedShaderId;
    private int selectedIndex;
    private int scrollPixels;
    private int contentHeight;

    public ShaderParameterScreen(Screen parent) {
        super(Component.literal("Shader Parameters"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        refreshShaders();
        Layout layout = layout();
        addNavigationWidgets(layout);
        rebuildParameterViews(layout);
        scrollPixels = Math.clamp(scrollPixels, 0, maximumScroll(layout));
        addParameterWidgets(layout);
    }

    private void refreshShaders() {
        ResourceLocation previous = selectedShaderId;
        shaders.clear();
        for (ResourceLocation id : RenderShaderRegistry.registeredIds()) {
            RenderShaderRegistry.get(id)
                    .filter(handle -> !handle.parameters().schema().isEmpty())
                    .ifPresent(shaders::add);
        }
        if (shaders.isEmpty()) {
            selectedShaderId = null;
            selectedIndex = 0;
            return;
        }
        selectedIndex = 0;
        if (previous != null) {
            for (int index = 0; index < shaders.size(); index++) {
                if (shaders.get(index).id().equals(previous)) {
                    selectedIndex = index;
                    break;
                }
            }
        }
        selectedShaderId = shaders.get(selectedIndex).id();
    }

    private void addNavigationWidgets(Layout layout) {
        int innerLeft = layout.left + 8;
        int innerWidth = Math.max(1, layout.width() - 16);
        int navigationWidth = Math.min(64, Math.max(36, (innerWidth - CONTROL_GAP * 3) / 5));
        int resetWidth = Math.min(92, Math.max(62, (innerWidth - CONTROL_GAP * 3) / 3));
        int doneWidth = Math.min(64, Math.max(44, (innerWidth - CONTROL_GAP * 3) / 5));
        int rightControlsWidth = resetWidth + CONTROL_GAP + doneWidth;
        int rightControlsLeft = innerLeft + innerWidth - rightControlsWidth;

        Button previous = addRenderableWidget(Button.builder(Component.literal("< Prev"), button -> selectShader(-1))
                .bounds(innerLeft, 30, navigationWidth, CONTROL_HEIGHT).build());
        Button next = addRenderableWidget(Button.builder(Component.literal("Next >"), button -> selectShader(1))
                .bounds(innerLeft + navigationWidth + CONTROL_GAP, 30, navigationWidth, CONTROL_HEIGHT).build());
        Button reset = addRenderableWidget(Button.builder(Component.literal("Reset all"), button -> {
                    selectedShader().ifPresent(handle -> handle.parameters().resetAll());
                    rebuildWidgets();
                })
                .bounds(rightControlsLeft, 30, resetWidth, CONTROL_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(rightControlsLeft + resetWidth + CONTROL_GAP, 30, doneWidth, CONTROL_HEIGHT).build());
        boolean multiple = shaders.size() > 1;
        previous.active = multiple;
        next.active = multiple;
        reset.active = selectedShader()
                .map(handle -> handle.parameters().hasOverrides())
                .orElse(false);
    }

    private void selectShader(int direction) {
        if (shaders.isEmpty()) return;
        selectedIndex = Math.floorMod(selectedIndex + direction, shaders.size());
        selectedShaderId = shaders.get(selectedIndex).id();
        scrollPixels = 0;
        rebuildWidgets();
    }

    private void rebuildParameterViews(Layout layout) {
        views.clear();
        contentHeight = 0;
        Optional<RenderShaderHandle> selected = selectedShader();
        if (selected.isEmpty()) return;
        int textWidth = Math.max(1, layout.width() - 28);
        for (ShaderParameter parameter : selected.get().parameters().schema().parameters()) {
            List<FormattedCharSequence> description = font.split(
                    Component.literal(parameter.description()), textWidth
            );
            int descriptionHeight = Math.max(1, description.size()) * LINE_HEIGHT;
            int metadataTop = 20 + descriptionHeight;
            int controlsTop = metadataTop + 14;
            int controls = parameter.type() == ShaderParameterType.BOOLEAN
                    ? 1
                    : parameter.type().componentCount();
            int height = controlsTop + controls * (CONTROL_HEIGHT + CONTROL_GAP) + 5;
            views.add(new ParameterView(contentHeight, height, metadataTop, controlsTop,
                    parameter, description));
            contentHeight += height;
        }
    }

    private void addParameterWidgets(Layout layout) {
        Optional<RenderShaderHandle> selected = selectedShader();
        if (selected.isEmpty()) return;
        ShaderParameterBlock block = selected.get().parameters();
        int left = layout.left + 12;
        int width = Math.max(1, layout.width() - 24);
        int viewportTop = listTop(layout);
        int viewportBottom = layout.bottom - 5;
        for (ParameterView view : views) {
            int baseY = viewportTop + view.top - scrollPixels;
            int resetY = baseY;
            if (fullyVisible(resetY, 16, viewportTop, viewportBottom)) {
                Button reset = addRenderableWidget(Button.builder(Component.literal("Reset"), button -> {
                            block.reset(view.parameter);
                            rebuildWidgets();
                        })
                        .bounds(left + width - 48, resetY, 48, 16).build());
                reset.active = !block.isDefault(view.parameter);
            }
            if (view.parameter.type() == ShaderParameterType.BOOLEAN) {
                int y = baseY + view.controlsTop;
                if (fullyVisible(y, CONTROL_HEIGHT, viewportTop, viewportBottom)) {
                    boolean enabled = block.booleanValue(view.parameter.name());
                    addRenderableWidget(Button.builder(booleanMessage(enabled), button -> {
                                block.setBoolean(view.parameter, !block.booleanValue(view.parameter.name()));
                                rebuildWidgets();
                            })
                            .bounds(left, y, width, CONTROL_HEIGHT).build());
                }
                continue;
            }
            for (int component = 0; component < view.parameter.type().componentCount(); component++) {
                int y = baseY + view.controlsTop + component * (CONTROL_HEIGHT + CONTROL_GAP);
                if (fullyVisible(y, CONTROL_HEIGHT, viewportTop, viewportBottom)) {
                    ParameterSlider slider = new ParameterSlider(
                            left, y, width, view.parameter, block, component
                    );
                    slider.active = view.parameter.range().minimum() != view.parameter.range().maximum();
                    addRenderableWidget(slider);
                }
            }
        }
    }

    private static boolean fullyVisible(int y, int height, int top, int bottom) {
        return y >= top && y + height <= bottom;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        Layout layout = layout();
        graphics.fill(layout.left, layout.top, layout.right, layout.bottom, 0xE113161D);
        graphics.fill(layout.left, layout.top, layout.right, layout.top + 1, 0xFF3B4352);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);

        Optional<RenderShaderHandle> selected = selectedShader();
        if (selected.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal("No shaders expose runtime parameters"),
                    width / 2, layout.top + 16, 0xFF9097A4);
            return;
        }
        RenderShaderHandle handle = selected.get();
        String shaderTitle = handle.id() + "  (" + (selectedIndex + 1) + "/" + shaders.size() + ")";
        graphics.drawString(font, trim(shaderTitle, layout.width() - 16),
                layout.left + 8, layout.top + 7, 0xFF78B7FF, false);
        String state = "state=" + handle.status().state().name().toLowerCase(Locale.ROOT)
                + "  parameters=" + handle.parameters().schema().size()
                + "  changes apply on the next draw";
        graphics.drawString(font, trim(state, layout.width() - 16),
                layout.left + 8, layout.top + 18, 0xFFAAB2C0, false);

        int viewportTop = listTop(layout);
        int viewportBottom = layout.bottom - 4;
        graphics.enableScissor(layout.left + 1, viewportTop, layout.right - 1, viewportBottom);
        for (ParameterView view : views) renderParameter(graphics, layout, view, handle.parameters());
        graphics.disableScissor();
        renderScrollbar(graphics, layout, viewportTop, viewportBottom);
    }

    private void renderParameter(
            GuiGraphics graphics,
            Layout layout,
            ParameterView view,
            ShaderParameterBlock block
    ) {
        int left = layout.left + 12;
        int availableWidth = Math.max(1, layout.width() - 78);
        int y = listTop(layout) + view.top - scrollPixels;
        if (y + view.height < listTop(layout) || y >= layout.bottom) return;
        if (view.top > 0) graphics.fill(layout.left + 8, y - 4, layout.right - 8, y - 3, 0x403B4352);
        String heading = (block.isDefault(view.parameter) ? "" : "* ")
                + view.parameter.name() + " : " + view.parameter.type().name();
        graphics.drawString(font, trim(heading, availableWidth), left, y + 4, 0xFFE5E9F0, false);
        int descriptionY = y + 20;
        for (int line = 0; line < view.description.size(); line++) {
            graphics.drawString(font, view.description.get(line), left,
                    descriptionY + line * LINE_HEIGHT, 0xFFAAB2C0, false);
        }
        String metadata = "range=" + formatRange(view.parameter)
                + "  default=" + formatValues(view.parameter.defaultValues().stream()
                .mapToDouble(Number::doubleValue).toArray(), view.parameter.type());
        graphics.drawString(font, trim(metadata, layout.width() - 24), left,
                y + view.metadataTop, 0xFF7F8999, false);
    }

    private void renderScrollbar(GuiGraphics graphics, Layout layout, int top, int bottom) {
        int maximum = maximumScroll(layout);
        if (maximum <= 0 || contentHeight <= 0) return;
        int viewportHeight = Math.max(1, bottom - top);
        int thumbHeight = Math.max(16, viewportHeight * viewportHeight / contentHeight);
        thumbHeight = Math.min(viewportHeight, thumbHeight);
        int travel = viewportHeight - thumbHeight;
        int thumbTop = top + (int) Math.round((double) scrollPixels / maximum * travel);
        graphics.fill(layout.right - 4, top, layout.right - 2, bottom, 0x503B4352);
        graphics.fill(layout.right - 5, thumbTop, layout.right - 1, thumbTop + thumbHeight, 0xFF78B7FF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        Layout layout = layout();
        int previous = scrollPixels;
        scrollPixels = Math.clamp(
                scrollPixels + (scrollY > 0 ? -SCROLL_STEP : SCROLL_STEP),
                0,
                maximumScroll(layout)
        );
        if (previous != scrollPixels) {
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private Optional<RenderShaderHandle> selectedShader() {
        if (selectedShaderId == null) return Optional.empty();
        return RenderShaderRegistry.get(selectedShaderId)
                .filter(handle -> !handle.parameters().schema().isEmpty());
    }

    private int maximumScroll(Layout layout) {
        return Math.max(0, contentHeight - Math.max(1, layout.bottom - 5 - listTop(layout)));
    }

    private Layout layout() {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(1, width - HORIZONTAL_MARGIN * 2));
        int left = Math.max(0, (width - panelWidth) / 2);
        return new Layout(left, Math.min(width, left + panelWidth), 56, Math.max(57, height - 12));
    }

    private static int listTop(Layout layout) {
        return layout.top + 34;
    }

    private String trim(String value, int maxWidth) {
        return font.plainSubstrByWidth(value, Math.max(10, maxWidth));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Component booleanMessage(boolean enabled) {
        return Component.literal("Enabled: " + (enabled ? "On" : "Off"));
    }

    private static String formatRange(ShaderParameter parameter) {
        return "[" + formatNumber(parameter.range().minimum(), parameter.type())
                + ", " + formatNumber(parameter.range().maximum(), parameter.type()) + "]";
    }

    private static String formatValues(double[] values, ShaderParameterType type) {
        StringBuilder result = new StringBuilder();
        if (values.length > 1) result.append('(');
        for (int index = 0; index < values.length; index++) {
            if (index > 0) result.append(", ");
            result.append(formatNumber(values[index], type));
        }
        if (values.length > 1) result.append(')');
        return result.toString();
    }

    private static String formatNumber(double value, ShaderParameterType type) {
        if (type.integral()) return Long.toString(Math.round(value));
        double absolute = Math.abs(value);
        if ((absolute != 0.0 && absolute < 0.001) || absolute >= 10000.0) {
            return String.format(Locale.ROOT, "%.3e", value);
        }
        String result = String.format(Locale.ROOT, "%.4f", value);
        while (result.indexOf('.') >= 0 && result.endsWith("0")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.endsWith(".") ? result.substring(0, result.length() - 1) : result;
    }

    private static String componentName(ShaderParameterType type, int component) {
        return switch (type) {
            case FLOAT, INTEGER -> "Value";
            case VEC2, VEC3, VEC4 -> "XYZW".substring(component, component + 1);
            case COLOR_RGB, COLOR_RGBA -> "RGBA".substring(component, component + 1);
            case MAT2 -> "M" + component / 2 + component % 2;
            case MAT3 -> "M" + component / 3 + component % 3;
            case MAT4 -> "M" + component / 4 + component % 4;
            case BOOLEAN -> "Enabled";
        };
    }

    private static final class ParameterSlider extends AbstractSliderButton {
        private final ShaderParameter parameter;
        private final ShaderParameterBlock block;
        private final int component;

        private ParameterSlider(
                int x,
                int y,
                int width,
                ShaderParameter parameter,
                ShaderParameterBlock block,
                int component
        ) {
            super(x, y, width, CONTROL_HEIGHT, Component.empty(), normalized(parameter, block, component));
            this.parameter = parameter;
            this.block = block;
            this.component = component;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double current = block.values(parameter.name())[component];
            setMessage(Component.literal(componentName(parameter.type(), component)
                    + ": " + formatNumber(current, parameter.type())));
        }

        @Override
        protected void applyValue() {
            double minimum = parameter.range().minimum();
            double maximum = parameter.range().maximum();
            double next = minimum + (maximum - minimum) * value;
            if (parameter.type().integral()) next = Math.rint(next);
            double[] values = block.values(parameter.name());
            values[component] = next;
            block.set(parameter, values);
        }

        private static double normalized(
                ShaderParameter parameter,
                ShaderParameterBlock block,
                int component
        ) {
            double minimum = parameter.range().minimum();
            double maximum = parameter.range().maximum();
            if (minimum == maximum) return 0.0;
            double current = block.values(parameter.name())[component];
            return Math.clamp((current - minimum) / (maximum - minimum), 0.0, 1.0);
        }
    }

    private record Layout(int left, int right, int top, int bottom) {
        private int width() {
            return right - left;
        }
    }

    private record ParameterView(
            int top,
            int height,
            int metadataTop,
            int controlsTop,
            ShaderParameter parameter,
            List<FormattedCharSequence> description
    ) {
        private ParameterView {
            description = List.copyOf(description);
        }
    }
}
