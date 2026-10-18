package lib.kasuga.rendering.effect.debug;

import lib.kasuga.rendering.effect.ClientEffectRuntime;
import lib.kasuga.rendering.effect.WorldRenderPipelineRegistry;
import lib.kasuga.rendering.effect.shader.RenderShaderRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Runtime view of active effects, registered pipelines and custom shader preload state. */
public final class EffectDiagnosticsScreen extends Screen {
    private static final int PANEL_WIDTH = 620;
    private static final int HORIZONTAL_MARGIN = 8;
    private static final int CONTROL_GAP = 4;
    private static final int COMPACT_CONTROLS_WIDTH = 540;
    private static final int LINE_HEIGHT = 10;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private List<FormattedCharSequence> wrappedSummary = List.of();
    private Tab tab = Tab.EFFECTS;
    private int scroll;
    private int refreshDelay;
    private String summary = "";
    private Button effectsButton;
    private Button shadersButton;
    private Button pipelinesButton;

    public EffectDiagnosticsScreen(Screen parent) {
        super(Component.literal("Kasuga Effect Inspector"));
        this.parent = parent;
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new EffectDiagnosticsScreen(minecraft.screen));
    }

    @Override
    protected void init() {
        Layout layout = layout();
        int innerLeft = layout.left + 8;
        int innerWidth = Math.max(1, layout.width() - 16);
        int actionCount = 3;
        int actionWidth = layout.compact
                ? Math.max(1, (innerWidth - CONTROL_GAP * (actionCount - 1)) / actionCount)
                : 84;
        int actionsWidth = actionWidth * actionCount + CONTROL_GAP * (actionCount - 1);
        int availableTabsWidth = layout.compact
                ? innerWidth
                : Math.max(3, innerWidth - actionsWidth - CONTROL_GAP * 2);
        int tabWidth = Math.max(1, (availableTabsWidth - CONTROL_GAP * 2) / 3);
        int tabsLeft = innerLeft;
        effectsButton = addRenderableWidget(Button.builder(Component.literal("Effects"), button -> select(Tab.EFFECTS))
                .bounds(tabsLeft, 30, tabWidth, 20).build());
        shadersButton = addRenderableWidget(Button.builder(Component.literal("Shaders"), button -> select(Tab.SHADERS))
                .bounds(tabsLeft + tabWidth + CONTROL_GAP, 30, tabWidth, 20).build());
        pipelinesButton = addRenderableWidget(Button.builder(Component.literal("Pipelines"), button -> select(Tab.PIPELINES))
                .bounds(tabsLeft + (tabWidth + CONTROL_GAP) * 2, 30, tabWidth, 20).build());

        int actionsY = layout.compact ? 54 : 30;
        int actionsLeft = layout.compact ? innerLeft : layout.right - 8 - actionsWidth;
        addRenderableWidget(Button.builder(Component.literal("Parameters"), button ->
                        minecraft.setScreen(new ShaderParameterScreen(this)))
                .bounds(actionsLeft, actionsY, actionWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Preload pending"), button -> {
                    RenderShaderRegistry.preloadPending();
                    select(Tab.SHADERS);
                })
                .bounds(actionsLeft + actionWidth + CONTROL_GAP, actionsY, actionWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(actionsLeft + (actionWidth + CONTROL_GAP) * 2, actionsY, actionWidth, 20).build());
        refresh();
    }

    private void select(Tab value) {
        tab = value;
        scroll = 0;
        refresh();
    }

    @Override
    public void tick() {
        if (--refreshDelay <= 0) refresh();
    }

    private void refresh() {
        refreshDelay = 10;
        rows.clear();
        switch (tab) {
            case EFFECTS -> refreshEffects();
            case SHADERS -> refreshShaders();
            case PIPELINES -> refreshPipelines();
        }
        wrappedSummary = wrapSummary(layout());
        scroll = Math.clamp(scroll, 0, maximumScroll());
        if (effectsButton != null) effectsButton.active = tab != Tab.EFFECTS;
        if (shadersButton != null) shadersButton.active = tab != Tab.SHADERS;
        if (pipelinesButton != null) pipelinesButton.active = tab != Tab.PIPELINES;
    }

    private void refreshEffects() {
        List<ClientEffectRuntime.EffectPipelineSnapshot> pipelines = ClientEffectRuntime.snapshot();
        int active = pipelines.stream()
                .mapToInt(ClientEffectRuntime.EffectPipelineSnapshot::activeCount)
                .sum();
        summary = active + " applied effects in " + pipelines.size() + " managed pipelines";

        for (ClientEffectRuntime.EffectPipelineSnapshot pipeline : pipelines) {
            int color = pipeline.activeCount() > 0 ? 0xFF70E090 : 0xFF9097A4;
            String phase = pipeline.descriptor().phase().map(Enum::name)
                    .orElseGet(() -> pipeline.descriptor().stage().toString());
            rows.add(row(
                    pipeline.id().toString(),
                    List.of(
                            "type=" + pipeline.effectType() + "  active=" + pipeline.activeCount()
                                    + "  visible=" + pipeline.visibleCount(),
                            "owner=" + pipeline.owner() + "  stage=" + phase,
                            "render=" + millis(pipeline.lastRenderNanos())
                    ),
                    color
            ));
        }
        if (rows.isEmpty()) rows.add(row("No effect pipelines are registered", List.of(), 0xFF9097A4));
    }

    private void refreshShaders() {
        RenderShaderRegistry.PreloadStats stats = RenderShaderRegistry.preloadStats();
        summary = stats.ready() + "/" + stats.registered() + " shaders ready; queued=" + stats.queued()
                + ", failed=" + stats.failed()
                + "; compile=" + millis(stats.totalCompileNanos())
                + "; prepare=" + stats.preparation().activeJobs()
                + "/" + stats.preparation().queuedJobs()
                + " on " + stats.preparation().workers() + " workers"
                + (stats.preparation().requestedWorkers() == 0 ? " (auto)" : "")
                + "/" + stats.preparation().availableProcessors() + " CPUs"
                + "; scheduler=" + stats.scheduler().queuedJobs() + " @ "
                + millis(stats.schedulerSettings().frameBudgetNanos()) + "/frame"
                + ", owner-cap=" + stats.schedulerSettings().maxJobsPerOwnerPerFrame();

        for (RenderShaderRegistry.ShaderSnapshot shader : RenderShaderRegistry.snapshots()) {
            int color = switch (shader.state()) {
                case READY -> 0xFF70E090;
                case FAILED -> 0xFFFF6868;
                case PREPARING, QUEUED, COMPILING -> 0xFFFFD166;
                case REGISTERED, CLOSED -> 0xFF9097A4;
            };
            String detail = shader.sourceKind().name().toLowerCase(Locale.ROOT)
                    + "  " + shader.state().name().toLowerCase(Locale.ROOT)
                    + "  policy=" + shader.preloadPolicy().name().toLowerCase(Locale.ROOT)
                    + "  priority=" + shader.preloadPriority();
            List<String> details = new ArrayList<>();
            details.add(detail);
            details.add("owner=" + shader.owner()
                    + "  failure=" + shader.failurePolicy().name().toLowerCase(Locale.ROOT));
            details.add("via=" + shader.origin().name().toLowerCase(Locale.ROOT)
                    + "  generation=" + shader.generation()
                    + (shader.queuePosition() > 0 ? "  queue=#" + shader.queuePosition() : "")
                    + "  wait=" + millis(shader.queueWaitNanos()));
            details.add("translate=" + millis(shader.preparationNanos())
                    + (shader.translationCacheHit() ? " cached" : "")
                    + "  compile=" + millis(shader.compileNanos())
                    + "  generated-cache=" + stats.cachedGeneratedPrograms()
                    + "/" + stats.generatedCacheHits());
            RenderShaderRegistry.get(shader.id()).ifPresent(handle -> {
                if (!handle.parameters().schema().isEmpty()) {
                    details.add("parameters=" + handle.parameters().schema().size()
                            + "  /kasuga_effects parameter list " + shader.id());
                }
            });
            if (shader.error() != null) details.add(shader.error());
            rows.add(row(shader.id().toString(), details, color));
        }
        if (rows.isEmpty()) rows.add(row("No custom shaders are registered", List.of(), 0xFF9097A4));
    }

    private void refreshPipelines() {
        List<WorldRenderPipelineRegistry.RegisteredPipeline> pipelines = WorldRenderPipelineRegistry.pipelines();
        summary = pipelines.size() + " registered render pipelines; snapshots are cached per native stage";
        for (WorldRenderPipelineRegistry.RegisteredPipeline pipeline : pipelines) {
            String phase = pipeline.descriptor().phase().map(Enum::name)
                    .orElseGet(() -> "raw:" + pipeline.stage());
            rows.add(row(
                    pipeline.id().toString(),
                    List.of(
                            "owner=" + pipeline.owner() + "  stage=" + phase
                                    + "  priority=" + pipeline.priority(),
                            "mode=" + pipeline.descriptor().drawState().primitiveMode()
                                    + "  buffer=" + pipeline.descriptor().drawState().bufferSize()
                                    + "  variants=" + pipeline.compiledPipeline().cachedVariantCount()
                    ),
                    0xFF78B7FF
            ));
        }
        if (rows.isEmpty()) rows.add(row("No render pipelines are registered", List.of(), 0xFF9097A4));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render() owns the single background-blur pass and renders widgets. Calling
        // renderBackground() separately would blur the panel text a second time.
        super.render(graphics, mouseX, mouseY, partialTick);
        Layout layout = layout();
        graphics.fill(layout.left, layout.top, layout.right, layout.bottom, 0xD913161D);
        graphics.fill(layout.left, layout.top, layout.right, layout.top + 1, 0xFF3B4352);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        for (int line = 0; line < wrappedSummary.size(); line++) {
            graphics.drawString(font, wrappedSummary.get(line), layout.left + 8,
                    layout.top + 8 + line * LINE_HEIGHT, 0xFFC7CEDA, false);
        }

        int listTop = listTop(layout);
        graphics.enableScissor(layout.left + 1, listTop, layout.right - 1, layout.bottom - 1);
        int y = listTop;
        for (int index = scroll; index < rows.size() && y < layout.bottom; index++) {
            Row row = rows.get(index);
            if ((index & 1) == 0) {
                graphics.fill(layout.left + 4, y, layout.right - 4,
                        Math.min(layout.bottom, y + row.height()), 0x301F2530);
            }
            graphics.drawString(font, row.title, layout.left + 8,
                    y + 3, row.color, false);
            for (int line = 0; line < row.details.size(); line++) {
                graphics.drawString(font, row.details.get(line),
                        layout.left + 8, y + 13 + line * LINE_HEIGHT, 0xFFAAB2C0, false);
            }
            y += row.height();
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int previous = scroll;
        if (scrollY > 0) scroll--;
        if (scrollY < 0) scroll++;
        scroll = Math.clamp(scroll, 0, maximumScroll());
        return previous != scroll || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int maximumScroll() {
        if (rows.isEmpty()) return 0;
        Layout layout = layout();
        int availableHeight = Math.max(1, layout.bottom - listTop(layout));
        int usedHeight = 0;
        for (int index = rows.size() - 1; index >= 0; index--) {
            usedHeight += rows.get(index).height();
            if (usedHeight > availableHeight) return Math.min(rows.size() - 1, index + 1);
        }
        return 0;
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

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0);
    }

    private Layout layout() {
        int availableWidth = Math.max(1, width - HORIZONTAL_MARGIN * 2);
        int panelWidth = Math.min(PANEL_WIDTH, availableWidth);
        int left = Math.max(0, (width - panelWidth) / 2);
        int right = Math.min(width, left + panelWidth);
        boolean compact = panelWidth < COMPACT_CONTROLS_WIDTH;
        int top = compact ? 80 : 56;
        int bottom = Math.max(top + 1, height - 12);
        return new Layout(left, right, top, bottom, compact);
    }

    private List<FormattedCharSequence> wrapSummary(Layout layout) {
        List<FormattedCharSequence> wrapped = font.split(
                Component.literal(summary), Math.max(1, layout.width() - 16)
        );
        return wrapped.size() <= 2 ? wrapped : wrapped.subList(0, 2);
    }

    private int listTop(Layout layout) {
        return layout.top + 14 + wrappedSummary.size() * LINE_HEIGHT;
    }

    private Row row(String title, List<String> details, int color) {
        int maxWidth = Math.max(10, layout().width() - 20);
        return new Row(
                trim(title, maxWidth),
                details.stream().map(detail -> trim(detail, maxWidth)).toList(),
                color
        );
    }

    private enum Tab {
        EFFECTS,
        SHADERS,
        PIPELINES
    }

    private record Layout(int left, int right, int top, int bottom, boolean compact) {
        private int width() {
            return right - left;
        }
    }

    private record Row(String title, List<String> details, int color) {
        private Row {
            details = List.copyOf(details);
        }

        private int height() {
            return 12 + Math.max(1, details.size()) * LINE_HEIGHT;
        }

    }
}
