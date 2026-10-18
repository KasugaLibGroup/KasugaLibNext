package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.PipelineBlendMode;
import lib.kasuga.rendering.effect.pipeline.PipelineCullMode;
import lib.kasuga.rendering.effect.pipeline.PipelineDepthTest;
import lib.kasuga.rendering.effect.pipeline.PipelineTarget;
import lib.kasuga.rendering.effect.pipeline.PipelineWriteMask;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Deterministic, camera-relative acceptance scene for the weighted OIT path.
 *
 * <p>This class deliberately lives in the contentTesting source set. The scene is not a production
 * renderer: it is a repeatable diagnostic fixture which makes ordinary object-order sorting visibly
 * disagree with itself while the WBOIT path should remain stable.</p>
 */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class OITVisualTestScene {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final ResourceLocation PIPELINE_ID = id("oit_visual_test");
    static final ResourceLocation OPAQUE_PIPELINE_ID = id("oit_visual_test_opaque");
    static final ResourceLocation MASK_PIPELINE_ID = id("oit_visual_test_mask");
    static final ResourceLocation TRANSLUCENT_PIPELINE_ID = id("oit_visual_test_translucent");

    static final KeyMapping TOGGLE_MODE = new KeyMapping(
            "key.kasuga_lib.oit_visual_mode",
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_F6,
            "key.categories.kasuga_lib"
    );
    static final KeyMapping TOGGLE_ORDER = new KeyMapping(
            "key.kasuga_lib.oit_visual_order",
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_F7,
            "key.categories.kasuga_lib"
    );
    static final KeyMapping CYCLE_BUFFER = new KeyMapping(
            "key.kasuga_lib.oit_visual_buffer",
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_F8,
            "key.categories.kasuga_lib"
    );

    private static final long TEST_SEED = 0x4B41535547414CL;
    private static final long ORDER_PERIOD_TICKS = 40L;

    private static volatile boolean enabled;
    private static volatile RenderMode renderMode = RenderMode.WEIGHTED_OIT;
    private static volatile boolean automaticOrder = true;
    private static volatile SubmissionOrder manualOrder = SubmissionOrder.ORIGINAL;
    private static volatile int automaticOrderPhase;
    private static volatile long activationGameTime;
    private static volatile BufferView bufferView = BufferView.FINAL;
    private static volatile ClientLevel activeLevel;
    private static volatile RenderReport report = RenderReport.inactive();
    private static final ScreenshotRun SCREENSHOT_RUN = new ScreenshotRun();

    private OITVisualTestScene() {
    }

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        RenderPipelineRegistrar pipelines = event.registrar(PIPELINE_ID);
        pipelines.world(descriptor(OPAQUE_PIPELINE_ID, RenderPhase.AFTER_ENTITIES, false),
                OITVisualTestRenderer::renderOpaque);
        pipelines.world(descriptor(MASK_PIPELINE_ID, RenderPhase.AFTER_ENTITIES, true),
                OITVisualTestRenderer::renderMask);
        pipelines.world(descriptor(TRANSLUCENT_PIPELINE_ID, RenderPhase.AFTER_TRANSLUCENT_BLOCKS, false),
                OITVisualTestRenderer::renderTranslucent);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ksglib")
                .then(Commands.literal("debug")
                        .then(Commands.literal("oit")
                                .executes(context -> {
                                    enable();
                                    success(context, "Kasuga OIT visual test enabled");
                                    return 1;
                                })
                                .then(Commands.literal("start").executes(context -> {
                                    enable();
                                    success(context, "Kasuga OIT visual test enabled");
                                    return 1;
                                }))
                                .then(Commands.literal("stop").executes(context -> {
                                    disable();
                                    success(context, "Kasuga OIT visual test disabled");
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(context -> {
                                    context.getSource().sendSuccess(() -> Component.literal(statusLine()), false);
                                    return enabled ? 1 : 0;
                                }))
                                .then(Commands.literal("capture").executes(context -> {
                                    if (!SCREENSHOT_RUN.start()) {
                                        context.getSource().sendFailure(Component.literal(
                                                "OIT visual screenshot capture is already running"));
                                        return 0;
                                    }
                                    success(context, "OIT visual screenshot capture started; keep the camera still");
                                    return 1;
                                }))
                                .then(Commands.literal("mode")
                                        .then(Commands.literal("oit").executes(context -> {
                                            renderMode = RenderMode.WEIGHTED_OIT;
                                            success(context, "OIT visual mode selected");
                                            return 1;
                                        }))
                                        .then(Commands.literal("sorted").executes(context -> {
                                            renderMode = RenderMode.SORTED_FALLBACK;
                                            success(context, "Sorted translucent visual mode selected");
                                            return 1;
                                        })))
                                .then(Commands.literal("order")
                                        .then(Commands.literal("auto").executes(context -> {
                                            automaticOrder = true;
                                            activationGameTime = currentGameTime();
                                            success(context, "Automatic deterministic submission permutation enabled");
                                            return 1;
                                        }))
                                        .then(Commands.literal("normal").executes(context -> setManualOrder(context,
                                                SubmissionOrder.ORIGINAL)))
                                        .then(Commands.literal("reverse").executes(context -> setManualOrder(context,
                                                SubmissionOrder.REVERSE)))
                                        .then(Commands.literal("rotate").executes(context -> setManualOrder(context,
                                                SubmissionOrder.ROTATE)))
                                        .then(Commands.literal("shuffle").executes(context -> setManualOrder(context,
                                                SubmissionOrder.SHUFFLE))))
                                .then(Commands.literal("buffer")
                                        .then(Commands.literal("final").executes(context -> setBuffer(context,
                                                BufferView.FINAL)))
                                        .then(Commands.literal("accum_rgb").executes(context -> setBuffer(context,
                                                BufferView.ACCUM_RGB)))
                                        .then(Commands.literal("accum_weight").executes(context -> setBuffer(context,
                                                BufferView.ACCUM_WEIGHT)))
                                        .then(Commands.literal("revealage").executes(context -> setBuffer(context,
                                                BufferView.REVEALAGE)))
                                        .then(Commands.literal("depth").executes(context -> setBuffer(context,
                                                BufferView.COPIED_DEPTH)))))));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != activeLevel) {
            activeLevel = level;
            if (level == null) {
                enabled = false;
                report = RenderReport.inactive();
            }
            if (enabled && level != null) activationGameTime = level.getGameTime();
        }

        if (level == null || minecraft.isPaused()) return;
        while (TOGGLE_MODE.consumeClick()) toggleMode();
        while (TOGGLE_ORDER.consumeClick()) toggleOrder();
        while (CYCLE_BUFFER.consumeClick()) cycleBuffer();

        if (enabled && automaticOrder) {
            long elapsed = Math.max(0L, level.getGameTime() - activationGameTime);
            automaticOrderPhase = (int) ((elapsed / ORDER_PERIOD_TICKS) % SubmissionOrder.values().length);
        }
        SCREENSHOT_RUN.tick();
    }

    @SubscribeEvent
    public static void renderHud(RenderGuiEvent.Post event) {
        if (!enabled) return;
        OITVisualTestRenderer.renderHud(event.getGuiGraphics(), report, currentOrder());
        SCREENSHOT_RUN.afterWorldRender();
    }

    static boolean enabled() {
        return enabled;
    }

    static RenderMode renderMode() {
        return renderMode;
    }

    static BufferView bufferView() {
        return bufferView;
    }

    static boolean automaticOrder() {
        return automaticOrder;
    }

    static SubmissionOrder currentOrder() {
        return automaticOrder
                ? SubmissionOrder.values()[Math.clamp(automaticOrderPhase, 0,
                SubmissionOrder.values().length - 1)]
                : manualOrder;
    }

    static int[] orderIndices(int size) {
        return currentOrder().indices(size, TEST_SEED);
    }

    static void updateReport(RenderReport next) {
        report = next;
    }

    static void disable() {
        enabled = false;
        SCREENSHOT_RUN.cancel();
        report = RenderReport.inactive();
    }

    private static void enable() {
        SCREENSHOT_RUN.cancel();
        enabled = true;
        OITVisualTestRenderer.reset();
        renderMode = RenderMode.WEIGHTED_OIT;
        automaticOrder = true;
        automaticOrderPhase = 0;
        activationGameTime = currentGameTime();
        bufferView = BufferView.FINAL;
        report = RenderReport.pending();
    }

    private static void toggleMode() {
        if (!enabled) enable();
        else renderMode = renderMode == RenderMode.WEIGHTED_OIT
                ? RenderMode.SORTED_FALLBACK : RenderMode.WEIGHTED_OIT;
        if (renderMode == RenderMode.SORTED_FALLBACK) bufferView = BufferView.FINAL;
    }

    private static void toggleOrder() {
        if (!enabled) enable();
        automaticOrder = false;
        manualOrder = manualOrder == SubmissionOrder.ORIGINAL
                ? SubmissionOrder.REVERSE : SubmissionOrder.ORIGINAL;
    }

    private static void cycleBuffer() {
        if (!enabled) enable();
        BufferView[] values = BufferView.values();
        bufferView = values[(bufferView.ordinal() + 1) % values.length];
    }

    private static int setManualOrder(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                      SubmissionOrder order) {
        automaticOrder = false;
        manualOrder = order;
        success(context, "Submission order: " + order.displayName);
        return 1;
    }

    private static int setBuffer(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, BufferView view) {
        bufferView = view;
        success(context, "OIT buffer view: " + view.displayName);
        return 1;
    }

    private static void success(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }

    private static long currentGameTime() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    private static String statusLine() {
        RenderReport current = report;
        return "Kasuga OIT visual: enabled=" + enabled
                + ", mode=" + renderMode.displayName
                + ", order=" + currentOrder().displayName
                + ", path=" + current.path
                + ", framebuffer=" + current.framebuffer
                + (SCREENSHOT_RUN.active() ? ", capture=running" : "")
                + (current.failure == null ? "" : ", failure=" + current.failure);
    }

    private static RenderPipelineDescriptor descriptor(ResourceLocation id, RenderPhase phase,
                                                        boolean maskPriority) {
        return RenderPipelineDescriptor.builder(id, phase)
                .priority(maskPriority ? 201 : 200)
                .draw(draw -> draw
                        .vertexFormat(DefaultVertexFormat.POSITION_COLOR)
                        .primitiveMode(VertexFormat.Mode.QUADS)
                        .bufferSize(64 * 1024)
                        .shaderState(net.minecraft.client.renderer.RenderStateShard.POSITION_COLOR_SHADER)
                        .blend(PipelineBlendMode.NONE)
                        .depthTest(PipelineDepthTest.LEQUAL)
                        .cull(PipelineCullMode.DISABLED)
                        .lightmap(false)
                        .overlay(false)
                        .writeMask(PipelineWriteMask.COLOR_AND_DEPTH)
                        .target(PipelineTarget.MAIN))
                .build();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, path);
    }

    enum RenderMode {
        WEIGHTED_OIT("WEIGHTED_OIT"),
        SORTED_FALLBACK("SORTED_FALLBACK");

        final String displayName;

        RenderMode(String displayName) {
            this.displayName = displayName;
        }
    }

    enum SubmissionOrder {
        ORIGINAL("ORIGINAL"),
        REVERSE("REVERSE"),
        ROTATE("ROTATE"),
        SHUFFLE("SHUFFLE");

        final String displayName;

        SubmissionOrder(String displayName) {
            this.displayName = displayName;
        }

        int[] indices(int size, long seed) {
            int[] result = new int[size];
            switch (this) {
                case ORIGINAL -> {
                    for (int index = 0; index < size; index++) result[index] = index;
                }
                case REVERSE -> {
                    for (int index = 0; index < size; index++) result[index] = size - index - 1;
                }
                case ROTATE -> {
                    int offset = size == 0 ? 0 : size / 3;
                    for (int index = 0; index < size; index++) result[index] = (index + offset) % size;
                }
                case SHUFFLE -> {
                    for (int index = 0; index < size; index++) result[index] = index;
                    long state = seed;
                    for (int index = size - 1; index > 0; index--) {
                        state = state * 6364136223846793005L + 1442695040888963407L;
                        int other = (int) Math.floorMod(state, index + 1L);
                        int swap = result[index];
                        result[index] = result[other];
                        result[other] = swap;
                    }
                }
            }
            return result;
        }
    }

    enum BufferView {
        FINAL("FINAL"),
        ACCUM_RGB("ACCUM_RGB"),
        ACCUM_WEIGHT("ACCUM_WEIGHT"),
        REVEALAGE("REVEALAGE"),
        COPIED_DEPTH("COPIED_DEPTH");

        final String displayName;

        BufferView(String displayName) {
            this.displayName = displayName;
        }
    }

    record RenderReport(String path, String framebuffer, String accumulation, String revealage,
                        String depth, int width, int height, boolean depthCopied, String failure) {
        static RenderReport inactive() {
            return new RenderReport("INACTIVE", "not used", "-", "-", "-", 0, 0, false, null);
        }

        static RenderReport pending() {
            return new RenderReport("PENDING", "not initialized", "-", "-", "-", 0, 0, false, null);
        }
    }

    /** Four-frame screenshot acceptance run; captures the world target before GUI overlays are drawn. */
    private static final class ScreenshotRun {
        private static final String[] NAMES = {
                "oit_normal", "oit_reverse", "sorted_normal", "sorted_reverse"
        };
        private static final double OIT_THRESHOLD = 1.0 / 255.0;
        private static final double SORTED_MINIMUM = 2.0 / 255.0;

        private int stage = -1;
        private int waitFrames;
        private final CapturedFrame[] frames = new CapturedFrame[NAMES.length];

        boolean active() {
            return stage >= 0;
        }

        boolean start() {
            if (active()) return false;
            enable();
            automaticOrder = false;
            bufferView = BufferView.FINAL;
            stage = 0;
            waitFrames = 10;
            applyStage();
            return true;
        }

        void tick() {
            if (stage >= 0 && waitFrames > 0) waitFrames--;
        }

        void afterWorldRender() {
            if (stage < 0 || waitFrames > 0 || !enabled) return;
            if (!expectedPath()) {
                finishFailure("expected " + expectedPathName() + " but got " + report.path());
                return;
            }
            try {
                frames[stage] = capture(NAMES[stage]);
            } catch (IOException | RuntimeException exception) {
                finishFailure("capture failed: " + exception.getMessage());
                return;
            }
            if (stage + 1 < NAMES.length) {
                stage++;
                waitFrames = 10;
                applyStage();
            } else {
                finish();
            }
        }

        void cancel() {
            stage = -1;
            waitFrames = 0;
            for (int index = 0; index < frames.length; index++) frames[index] = null;
        }

        private void applyStage() {
            renderMode = stage < 2 ? RenderMode.WEIGHTED_OIT : RenderMode.SORTED_FALLBACK;
            manualOrder = (stage & 1) == 0 ? SubmissionOrder.ORIGINAL : SubmissionOrder.REVERSE;
            automaticOrder = false;
            bufferView = BufferView.FINAL;
        }

        private boolean expectedPath() {
            return stage < 2 ? "WEIGHTED_OIT".equals(report.path())
                    : "SORTED_FALLBACK".equals(report.path());
        }

        private String expectedPathName() {
            return stage < 2 ? "WEIGHTED_OIT" : "SORTED_FALLBACK";
        }

        private CapturedFrame capture(String name) throws IOException {
            Minecraft minecraft = Minecraft.getInstance();
            NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget());
            try {
                Path directory = minecraft.gameDirectory.toPath()
                        .resolve("screenshots").resolve("kasuga_oit_visual");
                Files.createDirectories(directory);
                Path file = directory.resolve(name + ".png");
                image.writeToFile(file);
                return new CapturedFrame(image.getWidth(), image.getHeight(), image.getPixelsRGBA().clone());
            } finally {
                image.close();
            }
        }

        private void finish() {
            double oitError = meanRgbError(frames[0], frames[1]);
            double sortedError = meanRgbError(frames[2], frames[3]);
            boolean stable = oitError < OIT_THRESHOLD;
            boolean orderSensitive = sortedError > Math.max(SORTED_MINIMUM, oitError * 4.0);
            boolean passed = stable && orderSensitive;
            String result = "OIT screenshot test " + (passed ? "PASS" : "FAIL")
                    + ": E_oit=" + formatError(oitError)
                    + ", E_sorted=" + formatError(sortedError)
                    + ", threshold=" + formatError(OIT_THRESHOLD);
            writeReport(result);
            notifyPlayer(result + "; files in screenshots/kasuga_oit_visual");
            LOGGER.info(result);
            renderMode = RenderMode.WEIGHTED_OIT;
            manualOrder = SubmissionOrder.ORIGINAL;
            bufferView = BufferView.FINAL;
            for (int index = 0; index < frames.length; index++) frames[index] = null;
            stage = -1;
        }

        private void finishFailure(String reason) {
            String result = "OIT screenshot test NOT RUN: " + reason;
            writeReport(result);
            notifyPlayer(result);
            LOGGER.warn(result);
            cancel();
        }

        private void writeReport(String result) {
            try {
                Path directory = Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("screenshots").resolve("kasuga_oit_visual");
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("report.txt"), result + System.lineSeparator());
            } catch (IOException exception) {
                LOGGER.warn("Failed to write OIT screenshot report", exception);
            }
        }

        private void notifyPlayer(String message) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(Component.literal(message), false);
            }
        }

        private static double meanRgbError(CapturedFrame left, CapturedFrame right) {
            if (left == null || right == null || left.width != right.width || left.height != right.height) {
                return Double.POSITIVE_INFINITY;
            }
            long total = 0L;
            int pixels = left.width * left.height;
            for (int index = 0; index < pixels; index++) {
                int a = left.pixels[index];
                int b = right.pixels[index];
                total += Math.abs((a & 0xFF) - (b & 0xFF));
                total += Math.abs(((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF));
                total += Math.abs(((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF));
            }
            return total / (double) (pixels * 3L * 255L);
        }

        private static String formatError(double value) {
            return String.format(Locale.ROOT, "%.6f", value);
        }

        private record CapturedFrame(int width, int height, int[] pixels) {
        }
    }
}
