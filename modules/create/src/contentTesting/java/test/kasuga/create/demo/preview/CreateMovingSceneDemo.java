package test.kasuga.create.demo.preview;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.create.client.model.CreateModelSources;
import lib.kasuga.create.client.model.CreateSceneHosts;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.display.ModelDisplayTarget;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayScene;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget;
import lib.kasuga.scene.SceneModels;
import lib.kasuga.scene.ScenePose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;

/** Development-only native windmill mount. The smoke samples final framebuffer pixels and actual UI routing. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CreateMovingSceneDemo {
    private static final boolean SMOKE = Boolean.getBoolean("kasuga.testCreateMounts");
    private static final LinkedHashMap<String, Object> REPORT = new LinkedHashMap<>();
    private static final long START = System.nanoTime();
    private static SceneModels scene;
    private static SceneModels.Entry entry;
    private static MountPanel panel, direct;
    private static boolean requested, ready, finished, autoStarted;
    private static int warmup, frames, stage, stageFrames;
    private static double previousAngle;
    private static double angleTravel;
    private static double startingX;
    private static final Matrix4f PANEL = new Matrix4f().translation(-3, 4, 4).scale(.025f, -.025f, .025f);

    private CreateMovingSceneDemo() {}

    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_create_mount")
                .executes(context -> { start(); return 1; })
                .then(Commands.literal("stop").executes(context -> { stop(); return 1; })));
    }

    private static void start() {
        REPORT.put("bezierOffsetCalls", CreateBezierClientCheck.run());
        requested = true; CreateModelGallery.start();
    }

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (finished) return;
        var mc = Minecraft.getInstance();
        if (SMOKE && System.nanoTime() - START > 180_000_000_000L) { finish(new IllegalStateException("Moving mount timed out at " + stage)); return; }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        try {
            if (!autoStarted && !requested && (SMOKE || Boolean.getBoolean("kasuga.createMountDemo")) && ++warmup > 40) {
                autoStarted = true; start();
            }
            if (SMOKE && ready && stage == 1) {
                var moving = CreateModelGallery.windmillEntity();
                if (moving != null) {
                    // The bearing owns its anchor and resets it on ticks. Drive the client render
                    // fixture before each frame to exercise translation independently of that controller.
                    moving.setPos(startingX + 2, moving.getY(), moving.getZ());
                    moving.xOld = startingX + 2;
                }
            }
            if (!requested || ready) return;
            var rotor = CreateModelGallery.windmillEntity();
            if (!CreateModelGallery.active() || rotor == null || !rotor.isReadyForRender()) return;
            var host = CreateSceneHosts.contraption(rotor);
            scene = SceneModels.open(mc.level);
            panel = new MountPanel("场景面板 / Scene", 0xFF2266CC);
            direct = new MountPanel("独立面板 / Direct", 0xFF187B51);
            var root = new WorldDisplayScene();
            // A small live copy beside the real rotor makes a double-applied rotation easy to see.
            var source = CreateModelSources.contraptionLocal(rotor);
            source.snapshot(LayerLayout.ASSEMBLED, 0);
            root.add(new ModelDisplayTarget(source)).transform(new Matrix4f().translation(0, 0, 4).scale(.22f));
            root.add(new WorldDisplayTarget() {
                @Override protected Snapshot contentSnapshot(LayerLayout layout, float partial) { return null; }
                @Override protected List<Panel> contentPanels() { return List.of(new Panel(panel, PANEL)); }
                @Override protected void closeContent() {}
            });
            entry = scene.add(root, host);
            Constants.UI_BACKEND.addRenderable(direct, host,
                    new Matrix4f().translation(-3, -1, 4).scale(.025f, -.025f, .025f));
            Constants.UI_BACKEND.getInputRouter().setMaxDistance(64);
            mc.options.pauseOnLostFocus = false;
            mc.setWindowActive(true);
            mc.setScreen(null);
            var playerId = mc.player.getUUID();
            mc.getSingleplayerServer().execute(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                if (player != null) player.teleportTo(rotor.getX() + .5, rotor.getY() - 1, rotor.getZ() + 18);
            });
            mc.player.setYRot(180); mc.player.yRotO = 180;
            mc.player.setXRot(0); mc.player.xRotO = 0;
            previousAngle = rotor.getAngle(1);
            ready = true;
            LogUtils.getLogger().info("CREATE_MOVING_SCENE_READY entity={}", rotor.getId());
        } catch (Exception failure) { if (SMOKE) finish(failure); else { stop(); LogUtils.getLogger().error("Cannot mount Create scene", failure); } }
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!SMOKE || !ready || finished) return;
        var mc = Minecraft.getInstance();
        try {
            if (++frames < 30) return;
            var rotor = CreateModelGallery.windmillEntity();
            if (stage == 0) {
                check(rotor != null && entry.lastPose() != null, "Missing live host frame");
                double angle = rotor.getAngle(1);
                angleTravel += Math.abs(Mth.wrapDegrees(angle - previousAngle)); previousAngle = angle;
                var expectedStack = new PoseStack();
                float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
                rotor.applyLocalTransforms(expectedStack, partial);
                var expected = new ScenePose(new Vec3(Mth.lerp(partial, rotor.xOld, rotor.getX()),
                        Mth.lerp(partial, rotor.yOld, rotor.getY()), Mth.lerp(partial, rotor.zOld, rotor.getZ())), expectedStack.last().pose());
                var camera = mc.gameRenderer.getMainCamera().getPosition();
                check(entry.lastPose().relativeTo(camera).equals(expected.relativeTo(camera), .0001f), "Create render pose disagrees with mounted pose");
                var hit = scene.pick(entry.lastPose().toWorld(new Vec3(.11, .11, 3)),
                        entry.lastPose().toWorld(new Vec3(.11, .11, 6)), partial);
                check(hit.isPresent(), "Moving scene model could not be picked");
                clickAndCheck(panel);
                clickAndCheck(direct);
                if (stageFrames == 0) screenshot("moving-create-start.png");
                if (++stageFrames < 90 || angleTravel < 5) return;
                REPORT.put("rotationDegrees", angleTravel);
                REPORT.put("movingFrames", stageFrames);
                screenshot("moving-create.png");
                startingX = rotor.getX();
                stage = 1; stageFrames = 0;
            } else if (stage == 1) {
                check(rotor != null, "Host disappeared during translation");
                if (rotor.getX() - startingX < 1.5 || ++stageFrames < 20) return;
                clickAndCheck(panel); clickAndCheck(direct);
                REPORT.put("translatedBlocks", rotor.getX() - startingX);
                screenshot("translated-create.png");
                // Retain an actual button capture while the native host is removed on the server.
                var point = Constants.UI_BACKEND.getInputRouter().screenPosition(panel, 100, 20);
                Constants.UI_BACKEND.getInputRouter().press(point.x, point.y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                var server = mc.getSingleplayerServer(); int entityId = rotor.getId();
                server.execute(() -> { var entity = server.getLevel(CreateModelGallery.DIMENSION).getEntity(entityId); if (entity != null) entity.discard(); });
                stage = 2; stageFrames = 0;
            } else if (++stageFrames > 15 && entry.isClosed()) {
                check(!Constants.UI_BACKEND.containsRenderable(panel), "Scene panel survived host unload");
                check(!Constants.UI_BACKEND.containsRenderable(direct), "Direct panel survived host unload");
                check(Constants.UI_BACKEND.getInputRouter().keyboardFocus() == null, "Stale keyboard focus");
                check(!Constants.UI_BACKEND.getInputRouter().release(0, 0, 0), "Mouse capture survived host unload");
                REPORT.put("hostRemovalClearedSceneAndDirectUi", true);
                REPORT.put("sceneClicks", panel.clicks);
                REPORT.put("directClicks", direct.clicks);
                screenshot("after-host-removal.png");
                finish(null);
            }
        } catch (Exception failure) { finish(failure); }
    }

    private static void clickAndCheck(MountPanel target) throws Exception {
        var mc = Minecraft.getInstance();
        var router = Constants.UI_BACKEND.getInputRouter();
        var point = router.screenPosition(target, 100, 20);
        check(point != null, "Missing rendered UI surface");
        check(point.x >= 0 && point.y >= 0 && point.x < mc.getWindow().getGuiScaledWidth()
                && point.y < mc.getWindow().getGuiScaledHeight(), "Panel moved outside view");
        // Read a blank coloured part of the panel in the actual main framebuffer, including alpha.
        var colourPoint = router.screenPosition(target, 10, 35);
        int px = Math.round(colourPoint.x * mc.getWindow().getWidth() / mc.getWindow().getGuiScaledWidth());
        // Screenshot.takeScreenshot already flips GL's bottom-up rows into image coordinates.
        int py = Math.round(colourPoint.y * mc.getWindow().getHeight() / mc.getWindow().getGuiScaledHeight());
        try (var scope = new GuiRenderScope(); var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int rgba = image.getPixelRGBA(Math.clamp(px, 0, image.getWidth() - 1), Math.clamp(py, 0, image.getHeight() - 1));
            check((rgba >>> 24) > 240, "Panel framebuffer alpha is missing");
            int red = rgba & 255, green = rgba >>> 8 & 255, blue = rgba >>> 16 & 255;
            check(Math.abs(red - (target.colour >> 16 & 255)) < 15 && Math.abs(green - (target.colour >> 8 & 255)) < 15
                    && Math.abs(blue - (target.colour & 255)) < 15, "Panel is absent at its projected world position: " + Integer.toHexString(rgba));
        }
        int previous = target.clicks;
        check(router.press(point.x, point.y, 0), "Moving UI press missed");
        check(router.release(point.x, point.y, 0), "Moving UI release missed");
        check(target.clicks == previous + 1, "Moving UI clicked the wrong widget");
    }

    private static void screenshot(String name) throws Exception {
        var mc = Minecraft.getInstance();
        var output = mc.gameDirectory.toPath().resolve("debug/create-moving-scene"); Files.createDirectories(output);
        try (var scope = new GuiRenderScope(); var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(name)); }
    }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private static void stop() {
        if (scene != null) scene.close(); scene = null;
        if (direct != null && Constants.UI_BACKEND != null) Constants.UI_BACKEND.removeRenderable(direct);
        if (Constants.UI_BACKEND != null) Constants.UI_BACKEND.getInputRouter().setMaxDistance(5);
        requested = ready = false;
        CreateModelGallery.stop();
    }
    private static void finish(Exception failure) {
        if (finished) return; finished = true;
        try {
            if (failure != null && Minecraft.getInstance().level != null) screenshot("failure.png");
            REPORT.put("passed", failure == null); REPORT.put("failure", failure == null ? "" : failure.toString());
            var output = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/create-moving-scene/report.json");
            Files.createDirectories(output.getParent()); Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("CREATE_MOVING_SCENE_SMOKE_PASS {}", output);
            else LogUtils.getLogger().error("CREATE_MOVING_SCENE_SMOKE_FAIL", failure);
        } catch (Exception error) { LogUtils.getLogger().error("Cannot report moving scene smoke", error); }
        finally { stop(); Minecraft.getInstance().stop(); }
    }

    private static final class MountPanel extends AbstractWidget {
        private final int colour;
        private int clicks;
        MountPanel(String label, int colour) { super(0, 0, 200, 44, Component.literal(label)); this.colour = colour; }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, colour);
            graphics.drawString(Minecraft.getInstance().font, getMessage(), 8, 8, 0xFFFFFFFF);
            graphics.drawString(Minecraft.getInstance().font, "Clicks / 点击: " + clicks, 8, 23, 0xFFFFD66B);
        }
        @Override public void onClick(double x, double y) { clicks++; }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
