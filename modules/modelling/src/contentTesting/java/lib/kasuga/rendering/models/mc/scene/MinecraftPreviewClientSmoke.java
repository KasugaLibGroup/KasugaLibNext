package lib.kasuga.rendering.models.mc.scene;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.post.FullscreenPassRenderer;
import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.models.mc.backend.ui.MinecraftModelPreviewScreen;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Opt-in real-client pixel/interaction acceptance, using bundled vanilla buildings. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class MinecraftPreviewClientSmoke {
    public static final boolean ENABLED = Boolean.getBoolean("kasuga.testMinecraftPreview");
    private static final List<test.kasuga.modelling.demo.preview.MinecraftPreviewDemo.Example> SCENARIOS = new ArrayList<>();
    private static final LinkedHashMap<String, Object> REPORT = new LinkedHashMap<>();
    private static final Path OUTPUT = Path.of(System.getProperty("kasuga.minecraftPreview.output", "ksg_debug/minecraft-preview"));
    private static final long START = System.nanoTime();
    private static ShaderInstance tint;
    private static GuiModelPreview preview;
    private static MinecraftModelSource source;
    private static TextureTarget post;
    private static int scenario, stage, wait;
    private static byte[] baseline;
    private record Pixels(long count, double x, double y) {}
    private static Pixels baselinePixels, rotatedPixels, pannedPixels;
    private static boolean started, finished;

    @SubscribeEvent public static void shaders(RegisterShadersEvent event) throws java.io.IOException {
        if (ENABLED) event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.parse("kasuga_demo:minecraft_preview_tint"),
                DefaultVertexFormat.BLIT_SCREEN), shader -> tint = shader);
    }

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 180_000_000_000L) { finish(new IllegalStateException("Preview timeout at " + scenario + "/" + stage)); return; }
        if (mc.getOverlay() == null && mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen) {
            // The smoke runs in a disposable copy. Let vanilla back it up before upgrading its data packs.
            String proceed = Component.translatable("selectWorld.backupJoinConfirmButton").getString();
            for (var child : screen.children()) if (child instanceof net.minecraft.client.gui.components.Button button
                    && button.getMessage().getString().equals(proceed)) { button.onPress(); return; }
        }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null || tint == null) return;
        try {
            if (!started) {
                started = true;
                class Neighbors extends BlockModelSource {
                    Neighbors() { super(mc.level, java.util.List.of(
                            new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), null),
                            new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(net.minecraft.core.BlockPos.ZERO.above(), net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState(), null))); }
                    boolean preservesAppearance() { return appearanceView(new Slice(LayerLayout.Axis.Y, 0, geometryElements(), true))
                            .getBlockState(net.minecraft.core.BlockPos.ZERO.above()).is(net.minecraft.world.level.block.Blocks.GLASS); }
                }
                try (var probe = new Neighbors()) { check(probe.preservesAppearance(), "Layer filtering changed the neighbors used by connected-texture model data"); }
                SCENARIOS.addAll(test.kasuga.modelling.demo.preview.MinecraftPreviewDemo.examples().stream()
                        .filter(example -> !example.name().equals("create-airship")).toList());
                Files.createDirectories(OUTPUT);
                mc.options.pauseOnLostFocus = false; mc.setWindowActive(true);
            }
            if (preview == null) {
                source = SCENARIOS.get(scenario).factory().create();
                var bounds = source.snapshot(LayerLayout.ASSEMBLED, 0).bounds();
                preview = new GuiModelPreview(source);
                mc.setScreen(new MinecraftModelPreviewScreen(Component.literal(SCENARIOS.get(scenario).name() + " / 模型分层测试"), preview,
                        (int) Math.floor(bounds.minY()), (int) Math.ceil(bounds.maxY()) - 1));
                stage = 0; wait = 0;
            }
        } catch (Exception failure) { finish(failure); }
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || preview == null || ++wait < 8) return;
        try {
            check(preview.failure() == null, "Preview draw failed: " + preview.failure());
            FrameTexture frame = preview.currentFrame();
            check(frame != null, "Preview did not publish a frame");
            byte[] rgba = RgbaReadback.copy(frame);
            long opaque = 0, clear = 0;
            for (int i = 3; i < rgba.length; i += 4) { if ((rgba[i] & 255) > 32) opaque++; else if (rgba[i] == 0) clear++; }
            check(opaque > 100 && clear > 100, "Missing native geometry or transparent background: " + opaque + "/" + clear);
            String key = SCENARIOS.get(scenario).name() + "-" + stage;
            REPORT.put(key, java.util.Map.of("coveredPixels", opaque, "clearPixels", clear, "width", frame.width(), "height", frame.height()));
            save(key, frame, rgba);
            var mc = Minecraft.getInstance();
            try (var scope = new GuiRenderScope(); var screenshot = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                screenshot.writeToFile(OUTPUT.resolve(key + "-screen.png"));
            }
            if (stage == 0) {
                baseline = rgba;
                baselinePixels = pixels(frame, rgba);
                clickVisibleBlock(frame, rgba);
                // Real slider input drives explosion instead of bypassing the widget.
                double sliderX = 204 + (mc.screen.width - 220) * .4;
                check(mc.screen.mouseClicked(sliderX, mc.screen.height - 20, 0), "Explosion slider rejected click");
                mc.screen.mouseReleased(sliderX, mc.screen.height - 20, 0);
                check(preview.layers().separation() > 1, "Explosion slider did not update the source layout");
            } else if (stage == 1) {
                check(!Arrays.equals(baseline, rgba), "Explosion did not change actual RGBA pixels");
                clickVisibleBlock(frame, rgba);
                check(mc.screen.mouseClicked(201, mc.screen.height - 20, 0), "Explosion slider rejected reset");
                mc.screen.mouseReleased(201, mc.screen.height - 20, 0);
                check(preview.layers().separation() == 0, "Explosion slider did not reset to zero");
                check(mc.screen.mouseClicked(24, mc.screen.height - 20, 0), "Layer button rejected click");
                mc.screen.mouseReleased(24, mc.screen.height - 20, 0);
                check(preview.layers().lastLayer() < Integer.MAX_VALUE, "Layer button did not select a range");
            } else if (stage == 2) {
                check(!Arrays.equals(baseline, rgba), "Layer filtering did not change actual pixels");
                clickVisibleBlock(frame, rgba);
                check(mc.screen.mouseClicked(80, mc.screen.height - 20, 0), "All-layers button rejected click");
                mc.screen.mouseReleased(80, mc.screen.height - 20, 0);
                preview.setFrameProcessor(input -> {
                    check(input.depthTexture() > 0 && GL11.glIsTexture(input.depthTexture()), "No sampleable model depth");
                    if (post == null) post = new TextureTarget(input.color().width(), input.color().height(), false, Minecraft.ON_OSX);
                    else if (post.width != input.color().width() || post.height != input.color().height()) post.resize(input.color().width(), input.color().height(), Minecraft.ON_OSX);
                    FullscreenPassRenderer.draw(post, tint, true, shader -> {
                        shader.setSampler("ModelColor", input.color().textureId()); shader.setSampler("ModelDepth", input.depthTexture());
                    });
                    return new FrameTexture(post.frameBufferId, post.getColorTextureId(), post.width, post.height);
                });
            } else if (stage == 3) {
                check(!Arrays.equals(baseline, rgba), "Depth-aware post-processing did not change model pixels");
                preview.setFrameProcessor(input -> input.color());
                // Dragging a control into the canvas must remain a control gesture.
                check(mc.screen.mouseClicked(201, mc.screen.height - 20, 0), "Slider capture rejected click");
                mc.screen.mouseDragged(201, mc.screen.height / 2d, 0, 0, 20 - mc.screen.height / 2d);
                mc.screen.mouseReleased(201, mc.screen.height / 2d, 0);
                check(mc.screen.mouseClicked(201, mc.screen.height - 20, 0), "Slider rejected reset");
                mc.screen.mouseReleased(201, mc.screen.height - 20, 0);
                check(!mc.screen.mouseScrolled(10, 10, 0, 2), "Scrolling outside the canvas changed the view");
            } else if (stage == 4) {
                checkReset(frame, rgba, "Control drag changed model view");
                double x = mc.screen.width / 2d, y = mc.screen.height / 2d;
                check(mc.screen.mouseClicked(x, y, 0), "Rotation drag was not captured");
                check(mc.screen.mouseDragged(-10, y, 0, -10 - x, 0), "Rotation lost capture outside the canvas");
                check(mc.screen.mouseDragged(x + 40, y + 12, 0, x + 50, 12), "Rotation did not resume inside the canvas");
                check(mc.screen.mouseReleased(x + 40, y + 12, 0), "Rotation release was not handled");
                check(!mc.screen.mouseDragged(x + 50, y + 12, 0, 10, 0), "Rotation continued after release");
            } else if (stage == 5) {
                check(!Arrays.equals(baseline, rgba), "Rotation did not change actual pixels");
                rotatedPixels = pixels(frame, rgba);
                double x = mc.screen.width / 2d, y = mc.screen.height / 2d;
                double dx = (mc.screen.width - 16) * .06, dy = -(mc.screen.height - 72) * .04;
                check(mc.screen.mouseClicked(x, y, 2), "Pan drag was not captured");
                check(mc.screen.mouseDragged(x + dx, y + dy, 2, dx, dy), "Pan drag was not handled");
                check(mc.screen.mouseReleased(x + dx, y + dy, 2), "Pan release was not handled");
            } else if (stage == 6) {
                pannedPixels = pixels(frame, rgba);
                check(Math.abs(pannedPixels.x - rotatedPixels.x - frame.width() * .06) < 3, "Pan X pixels did not follow mouse delta");
                check(Math.abs(pannedPixels.y - rotatedPixels.y + frame.height() * .04) < 3, "Pan Y pixels did not follow mouse delta");
                check(Math.abs((double) pannedPixels.count / rotatedPixels.count - 1) < .02, "Pan changed model scale or clipped it");
                REPORT.put(SCENARIOS.get(scenario).name() + "-pan", java.util.Map.of(
                        "dxPixels", pannedPixels.x - rotatedPixels.x, "dyPixels", pannedPixels.y - rotatedPixels.y));
                check(mc.screen.mouseScrolled(mc.screen.width / 2d, mc.screen.height / 2d, 0, 2), "Wheel zoom was not handled");
            } else if (stage == 7) {
                var zoomed = pixels(frame, rgba);
                double ratio = (double) zoomed.count / pannedPixels.count;
                check(Math.abs(ratio - Math.exp(.4)) < .09, "Wheel zoom did not scale the actual silhouette: " + ratio);
                check(mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_R, 0, 0), "View reset was not handled");
            } else if (stage == 8) {
                checkReset(frame, rgba, "Reset did not restore the original model pixels");
                clickVisibleBlock(frame, rgba);
                // GUI resize must retire/reallocate the preview's own attachments.
                mc.options.guiScale().set(mc.options.guiScale().get() == 2 ? 3 : 2); mc.resizeDisplay();
            } else {
                try (var scope = new GuiRenderScope()) {
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frame.framebufferId());
                    check(GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE, "Resized model FBO is incomplete");
                }
                preview.close(); preview.close();
                check(!GL11.glIsTexture(frame.textureId()) && !GL30.glIsFramebuffer(frame.framebufferId()), "Model canvas leaked after close");
                preview = null; source = null; mc.setScreen(null);
                if (post != null) { post.destroyBuffers(); post = null; }
                if (++scenario >= SCENARIOS.size()) { finish(null); return; }
            }
            check(GL11.glGetError() == GL11.GL_NO_ERROR, "Model preview left a GL error");
            stage++; wait = 0;
        } catch (Exception failure) { finish(failure); }
    }

    private static void clickVisibleBlock(FrameTexture frame, byte[] rgba) {
        int pickedX = -1, pickedY = -1; double distance = Double.POSITIVE_INFINITY;
        for (int y = 2; y < frame.height() - 2; y += 3) for (int x = 2; x < frame.width() - 2; x += 3) {
            if ((rgba[(y * frame.width() + x) * 4 + 3] & 255) < 230) continue;
            double next = Math.pow(x - frame.width() / 2d, 2) + Math.pow(y - frame.height() / 2d, 2);
            if (next < distance) { distance = next; pickedX = x; pickedY = y; }
        }
        check(pickedX >= 0, "No opaque model pixel to inspect");
        var screen = (MinecraftModelPreviewScreen) Minecraft.getInstance().screen;
        double x = 8 + (pickedX + .5) / frame.width() * (screen.width - 16);
        double y = 30 + (pickedY + .5) / frame.height() * (screen.height - 72);
        check(screen.mouseClicked(x, y, 0), "Block inspection click rejected");
        check(screen.mouseReleased(x, y, 0), "Block inspection release rejected");
        var hit = screen.selectedBlock(); check(hit != null, "Visible block pixel did not produce inspection information");
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(hit.state().getBlock()).toString();
        if (SCENARIOS.get(scenario).name().equals("litematic")) {
            var expected = switch (hit.position().getY()) {
                case 1 -> net.minecraft.world.level.block.Blocks.RED_CONCRETE;
                case 2 -> net.minecraft.world.level.block.Blocks.LIME_CONCRETE;
                case 3 -> net.minecraft.world.level.block.Blocks.BLUE_CONCRETE;
                default -> throw new IllegalStateException("Wrong picked litematic layer");
            };
            check(hit.state().is(expected), "Inspected state disagrees with the source's known layer colors");
        }
        REPORT.put(SCENARIOS.get(scenario).name() + "-" + stage + "-inspection", java.util.Map.of("block", id, "position", hit.position().toShortString()));
    }

    private static Pixels pixels(FrameTexture frame, byte[] rgba) {
        long count = 0, xSum = 0, ySum = 0;
        for (int y = 0; y < frame.height(); y++) for (int x = 0; x < frame.width(); x++) {
            if ((rgba[(y * frame.width() + x) * 4 + 3] & 255) > 32) { count++; xSum += x; ySum += y; }
        }
        check(count > 100, "No model silhouette");
        return new Pixels(count, (double) xSum / count, (double) ySum / count);
    }

    private static void checkReset(FrameTexture frame, byte[] rgba, String message) {
        String name = SCENARIOS.get(scenario).name();
        if (name.equals("fluid-section-boundary")) {
            // Water's RGB texture animates independently of the view. Its entire alpha mask must reset exactly.
            check(baseline.length == rgba.length, message);
            for (int i = 3; i < rgba.length; i += 4) check(baseline[i] == rgba[i], message);
        } else if (!name.equals("create-contraption-actor")) check(Arrays.equals(baseline, rgba), message);
        else {
            // Drill actor animation may advance; its reset silhouette must retain position and scale.
            var actual = pixels(frame, rgba);
            check(Math.abs(actual.x - baselinePixels.x) < 2 && Math.abs(actual.y - baselinePixels.y) < 2
                    && Math.abs((double) actual.count / baselinePixels.count - 1) < .01, message);
        }
    }

    private static void check(boolean condition, String failure) { if (!condition) throw new IllegalStateException(failure); }
    private static void save(String name, FrameTexture frame, byte[] rgba) throws java.io.IOException {
        var image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < frame.height(); y++) for (int x = 0; x < frame.width(); x++) {
            int i = (y * frame.width() + x) * 4;
            image.setRGB(x, y, (rgba[i + 3] & 255) << 24 | (rgba[i] & 255) << 16 | (rgba[i + 1] & 255) << 8 | rgba[i + 2] & 255);
        }
        ImageIO.write(image, "png", OUTPUT.resolve(name + ".png").toFile());
    }
    private static void finish(Exception failure) {
        if (finished) return;
        finished = true;
        try {
            if (preview != null) preview.close();
            if (post != null) post.destroyBuffers();
            REPORT.put("passed", failure == null); REPORT.put("failure", failure == null ? "" : failure.toString());
            Files.createDirectories(OUTPUT);
            Files.writeString(OUTPUT.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("MINECRAFT_PREVIEW_SMOKE_PASS {}", OUTPUT);
            else LogUtils.getLogger().error("MINECRAFT_PREVIEW_SMOKE_FAIL", failure);
        } catch (Exception error) { LogUtils.getLogger().error("Cannot finish preview smoke", error); }
        finally { Minecraft.getInstance().stop(); }
    }
}
