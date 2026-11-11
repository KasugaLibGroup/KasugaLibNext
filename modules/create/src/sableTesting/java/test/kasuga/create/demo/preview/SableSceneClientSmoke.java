package test.kasuga.create.demo.preview;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import lib.kasuga.KasugaLib;
import lib.kasuga.create.client.model.SableSceneHosts;
import lib.kasuga.create.client.model.CreateModelSources;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSources;
import lib.kasuga.rendering.models.mc.scene.display.ModelDisplayTarget;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayScene;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget;
import lib.kasuga.scene.MinecraftStage;
import lib.kasuga.scene.SceneHost;
import lib.kasuga.scene.SceneModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;

/** Opt-in only: compiled when kasugaSableSmokeJar is supplied, against the unmodified official JAR. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class SableSceneClientSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testSableScene");
    private static final long START = System.nanoTime();
    private static final LinkedHashMap<String, Object> REPORT = new LinkedHashMap<>();
    private static final BlockPos ANCHOR = new BlockPos(0, 108, 0);
    private static volatile MinecraftStage stage;
    private static volatile ServerSubLevel serverSubLevel;
    private static volatile BlockPos plotAnchor;
    private static volatile Vec3 initialBodyPosition;
    private static volatile int requestedPhase;
    private static volatile String serverFailure;
    private static volatile int nestedEntityId = -1;
    private static SceneModels scene;
    private static SceneModels.Entry entry;
    private static SceneModels.Entry nestedEntry;
    private static SceneHost host;
    private static Panel scenePanel, directPanel, nestedPanel;
    private static ControlledContraptionEntity nestedEntity;
    private static boolean started, ready, finished;
    private static int warmup, frames, phaseFrames;
    private static int serverWarmup, bodyTicks;
    private static Vec3 firstOrigin;

    private SableSceneClientSmoke() {}

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        try {
            if (System.nanoTime() - START > 180_000_000_000L) throw new IllegalStateException("Sable smoke timeout phase=" + requestedPhase + " ready=" + ready);
            if (serverFailure != null) throw new IllegalStateException(serverFailure);
            if (mc.level == null || mc.player == null || mc.getOverlay() != null) return;
            if (!started && ++warmup > 60) {
                started = true;
                check(ModList.get().isLoaded("sable") && ModList.get().isLoaded("aeronautics"), "Official Sable and Aeronautics must both be installed");
                REPORT.put("bezierOffsetCalls", CreateBezierClientCheck.run());
                for (String id : List.of("sable", "sablecompanion", "aeronautics", "create", "neoforge")) {
                    REPORT.put(id, ModList.get().getModContainerById(id).orElseThrow().getModInfo().getVersion().toString());
                }
                mc.options.pauseOnLostFocus = false; mc.setWindowActive(true); mc.setScreen(null);
                var playerId = mc.player.getUUID(); var server = mc.getSingleplayerServer();
                check(server != null, "Private singleplayer is required");
                server.execute(() -> {
                    try {
                        var player = server.getPlayerList().getPlayer(playerId);
                        stage = MinecraftStage.open(player, CreateModelGallery.DIMENSION, new Vec3(1, 107, 15), 180, 0, world -> {
                            world.block(ANCHOR, Blocks.IRON_BLOCK.defaultBlockState());
                            world.block(ANCHOR.east(), Blocks.GOLD_BLOCK.defaultBlockState());
                            var levitite = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("aeronautics:levitite"));
                            check(levitite != Blocks.AIR, "Aeronautics levitite is missing");
                            world.block(ANCHOR.east(2), levitite.defaultBlockState());
                            world.block(ANCHOR.east(3), AllBlocks.MECHANICAL_BEARING.getDefaultState()
                                    .setValue(DirectionalKineticBlock.FACING, Direction.UP));
                            world.block(ANCHOR.east(3).above(), Blocks.DIAMOND_BLOCK.defaultBlockState());
                        });
                        player.setGameMode(GameType.CREATIVE); player.getAbilities().flying = true; player.onUpdateAbilities();
                    } catch (Exception error) { serverFailure = error.toString(); LogUtils.getLogger().error("Cannot create Sable fixture", error); }
                });
            }
            if (ready && nestedEntity != null) nestedEntity.setAngle(requestedPhase == 0 ? 25 : 65);
            if (!started || ready || plotAnchor == null || !mc.level.dimension().equals(CreateModelGallery.DIMENSION)) return;
            var nestedCandidate = mc.level.getEntity(nestedEntityId);
            if (warmup++ % 120 == 0) LogUtils.getLogger().info("SABLE_SCENE_WAIT entity={} candidate={} ready={}", nestedEntityId,
                    nestedCandidate, nestedCandidate instanceof ControlledContraptionEntity c && c.isReadyForRender());
            if (!(nestedCandidate instanceof ControlledContraptionEntity rotor) || !rotor.isReadyForRender()) return;
            var candidate = SableSceneHosts.block(mc.level, plotAnchor);
            var nested = SableSceneHosts.contraption(rotor);
            if (candidate.isEmpty() || nested.isEmpty()) {
                if (warmup % 120 == 1) LogUtils.getLogger().info("SABLE_SCENE_WAIT hosts block={} nested={} pos={}", candidate.isPresent(), nested.isPresent(), rotor.position());
                return;
            }
            nestedEntity = rotor; rotor.setAngle(25);
            host = candidate.get(); scene = SceneModels.open(mc.level);
            scenePanel = new Panel("航空学场景 / Scene", 0xFF2266CC);
            directPanel = new Panel("Sable 面板 / Direct", 0xFF187B51);
            nestedPanel = new Panel("船上 Create / Nested", 0xFF9C5B16);
            var source = MinecraftModelSources.capture(mc.level, plotAnchor, plotAnchor.east(3));
            source.snapshot(LayerLayout.ASSEMBLED, 0);
            var root = new WorldDisplayScene(); root.add(new ModelDisplayTarget(source));
            root.add(new WorldDisplayTarget() {
                @Override protected Snapshot contentSnapshot(LayerLayout layout, float partial) { return null; }
                @Override protected List<WorldDisplayTarget.Panel> contentPanels() {
                    return List.of(new WorldDisplayTarget.Panel(scenePanel,
                            new Matrix4f().translation(-2, 3, 2).scale(.025f, -.025f, .025f)));
                }
                @Override protected void closeContent() {}
            });
            entry = scene.add(root, host);
            Constants.UI_BACKEND.addRenderable(directPanel, host,
                    new Matrix4f().translation(-2, -1, 2).scale(.025f, -.025f, .025f));
            var nestedRoot = new WorldDisplayScene();
            nestedRoot.add(new ModelDisplayTarget(CreateModelSources.contraptionLocal(rotor)));
            nestedRoot.add(new WorldDisplayTarget() {
                @Override protected Snapshot contentSnapshot(LayerLayout layout, float partial) { return null; }
                @Override protected List<WorldDisplayTarget.Panel> contentPanels() {
                    return List.of(new WorldDisplayTarget.Panel(nestedPanel,
                            new Matrix4f().translation(1, 2, 2).scale(.018f, -.018f, .018f)));
                }
                @Override protected void closeContent() {}
            });
            nestedEntry = scene.add(nestedRoot, nested.get());
            Constants.UI_BACKEND.getInputRouter().setMaxDistance(64);
            firstOrigin = host.pose(1).origin(); ready = true;
            REPORT.put("plotAnchor", plotAnchor.toShortString());
            LogUtils.getLogger().info("SABLE_SCENE_CLIENT_READY anchor={}", plotAnchor);
        } catch (Exception error) { finish(error); }
    }

    @SubscribeEvent public static void serverTick(ServerTickEvent.Post event) {
        if (!ENABLED || finished || stage == null || requestedPhase >= 2) return;
        try {
            // Let teleport/chunk tracking settle before creating a body in the disposable dimension.
            if (serverSubLevel == null) {
                if (++serverWarmup < 40) return;
                var positions = List.of(ANCHOR, ANCHOR.east(), ANCHOR.east(2), ANCHOR.east(3), ANCHOR.east(3).above());
                for (var position : positions) check(!stage.level().getBlockState(position).isAir(), "Missing assembly block " + position);
                serverSubLevel = SubLevelAssemblyHelper.assembleBlocks(stage.level(), ANCHOR, positions,
                        new BoundingBox3i(ANCHOR, ANCHOR.east(3).above()));
                check(!serverSubLevel.isRemoved(), "New sublevel is already removed");
                var p = serverSubLevel.logicalPose().position(); initialBodyPosition = new Vec3(p.x(), p.y(), p.z());
                plotAnchor = serverSubLevel.getPlot().getCenterBlock().immutable();
                LogUtils.getLogger().info("SABLE_SCENE_SERVER_READY uuid={} anchor={}", serverSubLevel.getUniqueId(), plotAnchor);
            }
            var physics = SubLevelContainer.getContainer(stage.level()).physicsSystem().getPipeline();
            physics.resetVelocity(serverSubLevel);
            // Drive official server physics inputs, then let Sable synchronize/interpolate them.
            var position = new Vector3d(initialBodyPosition.x + (requestedPhase == 1 ? 2 : 0), initialBodyPosition.y, initialBodyPosition.z);
            var orientation = new Quaterniond(); if (requestedPhase == 1) orientation.rotateZ(.35).rotateY(.2);
            physics.teleport(serverSubLevel, position, orientation);
            if (nestedEntityId < 0 && ++bodyTicks > 20) {
                var bearing = (MechanicalBearingBlockEntity) stage.level().getBlockEntity(plotAnchor.east(3));
                check(bearing != null, "Missing bearing in Sable plot");
                // This fixture has no kinetic power source. Keep the assembled mechanism on stop.
                bearing.getBehaviour(com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour.TYPE)
                        .setValue(com.simibubi.create.content.contraptions.IControlContraption.RotationMode.ROTATE_NEVER_PLACE.ordinal());
                bearing.assemble();
                check(bearing.getMovedContraption() != null, "Nested Create bearing failed to assemble");
                nestedEntityId = bearing.getMovedContraption().getId();
                LogUtils.getLogger().info("SABLE_SCENE_NESTED_READY entity={}", nestedEntityId);
            }
        } catch (Exception error) { serverFailure = error.toString(); LogUtils.getLogger().error("Cannot drive Sable fixture", error); }
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || !ready || finished) return;
        try {
            if (++frames < 30) return;
            if (requestedPhase < 2) {
                var pose = entry.lastPose(); check(pose != null, "Scene has no rendered host pose");
                if (requestedPhase == 1 && pose.origin().distanceTo(firstOrigin) < 1.0) return;
                var nestedDebugPose = nestedEntry.lastPose();
                REPORT.put("nestedDebug", java.util.Map.of("closed", nestedEntry.isClosed(), "removed", nestedEntity.isRemoved(),
                        "position", nestedEntity.position().toString(), "oldPosition", new Vec3(nestedEntity.xOld, nestedEntity.yOld, nestedEntity.zOld).toString(),
                        "origin", nestedDebugPose == null ? "null" : nestedDebugPose.origin().toString(),
                        "transform", nestedDebugPose == null ? "null" : nestedDebugPose.transform().toString(),
                        "registered", Constants.UI_BACKEND.containsRenderable(nestedPanel)));
                check(scene.pick(pose.toWorld(new Vec3(.5, .5, -1)), pose.toWorld(new Vec3(.5, .5, 2)),
                        Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)).isPresent(), "Sable model picking missed");
                clickAndCheck(scenePanel); clickAndCheck(directPanel);
                clickAndCheck(nestedPanel);
                check(nestedEntry.lastPose() != null, "Missing nested Create rendered pose");
                var nestedPose = nestedEntry.lastPose();
                check(scene.pick(nestedPose.toWorld(new Vec3(.5, .5, -1)), nestedPose.toWorld(new Vec3(.5, .5, 2)),
                        Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)).isPresent(), "Nested Create picking missed");
                if (++phaseFrames < 30) return;
                screenshot(requestedPhase == 0 ? "sable-initial.png" : "sable-transformed.png");
                REPORT.put("phase-" + requestedPhase, java.util.Map.of("frames", phaseFrames,
                        "origin", pose.origin().toString(), "sceneClicks", scenePanel.clicks, "directClicks", directPanel.clicks,
                        "nestedClicks", nestedPanel.clicks, "childAngle", nestedEntity.getAngle(1)));
                if (requestedPhase == 0) { requestedPhase = 1; phaseFrames = 0; return; }
                var point = Constants.UI_BACKEND.getInputRouter().screenPosition(scenePanel, 100, 20);
                Constants.UI_BACKEND.getInputRouter().press(point.x, point.y, 0);
                requestedPhase = 2; phaseFrames = 0;
                Minecraft.getInstance().getSingleplayerServer().execute(() ->
                        SubLevelContainer.getContainer(stage.level()).removeSubLevel(serverSubLevel, SubLevelRemovalReason.REMOVED));
            } else if (++phaseFrames > 15 && entry.isClosed()) {
                check(!host.isAlive(), "Removed sublevel still resolves as alive");
                check(nestedEntry.isClosed(), "Nested Create entry survived removed parent");
                check(!Constants.UI_BACKEND.containsRenderable(scenePanel) && !Constants.UI_BACKEND.containsRenderable(directPanel)
                        && !Constants.UI_BACKEND.containsRenderable(nestedPanel), "Orphan Sable panel");
                check(Constants.UI_BACKEND.getInputRouter().keyboardFocus() == null, "Stale Sable keyboard focus");
                check(!Constants.UI_BACKEND.getInputRouter().release(0, 0, 0), "Stale Sable button capture");
                REPORT.put("unloadClearedPanelsAndInput", true); screenshot("sable-removed.png"); finish(null);
            }
        } catch (Exception error) { finish(error); }
    }

    private static void clickAndCheck(Panel panel) throws Exception {
        var mc = Minecraft.getInstance(); var router = Constants.UI_BACKEND.getInputRouter();
        var click = router.screenPosition(panel, 100, 20); var blank = router.screenPosition(panel, 10, 35);
        check(click != null && blank != null, "Missing Sable UI render surface");
        int x = Math.round(blank.x * mc.getWindow().getWidth() / mc.getWindow().getGuiScaledWidth());
        int y = Math.round(blank.y * mc.getWindow().getHeight() / mc.getWindow().getGuiScaledHeight());
        try (var scope = new GuiRenderScope(); var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            check(x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight(), "Sable UI is outside the viewport");
            int rgba = image.getPixelRGBA(x, y);
            check((rgba >>> 24) > 240 && Math.abs((rgba & 255) - (panel.colour >> 16 & 255)) < 15
                    && Math.abs((rgba >> 8 & 255) - (panel.colour >> 8 & 255)) < 15
                    && Math.abs((rgba >> 16 & 255) - (panel.colour & 255)) < 15, "Sable UI pixel mismatch: " + Integer.toHexString(rgba));
        }
        int old = panel.clicks; check(router.press(click.x, click.y, 0), "Sable UI press missed");
        check(router.release(click.x, click.y, 0), "Sable UI release missed"); check(panel.clicks == old + 1, "Wrong Sable UI target");
    }
    private static void screenshot(String name) throws Exception {
        var mc = Minecraft.getInstance(); var out = mc.gameDirectory.toPath().resolve("debug/sable-scene"); Files.createDirectories(out);
        try (var scope = new GuiRenderScope(); var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(out.resolve(name)); }
    }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private static void finish(Exception error) {
        if (finished) return; finished = true;
        try {
            if (error != null && Minecraft.getInstance().level != null) screenshot("failure.png");
            REPORT.put("passed", error == null); REPORT.put("failure", error == null ? "" : error.toString());
            var out = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/sable-scene/report.json"); Files.createDirectories(out.getParent());
            Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (error == null) LogUtils.getLogger().info("SABLE_SCENE_SMOKE_PASS {}", out); else LogUtils.getLogger().error("SABLE_SCENE_SMOKE_FAIL", error);
        } catch (Exception reportError) { LogUtils.getLogger().error("Cannot write Sable report", reportError); }
        finally {
            if (scene != null) scene.close();
            if (directPanel != null) Constants.UI_BACKEND.removeRenderable(directPanel);
            if (Constants.UI_BACKEND != null) Constants.UI_BACKEND.getInputRouter().setMaxDistance(5);
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null && stage != null) server.execute(() -> {
                try {
                    if (serverSubLevel != null && !serverSubLevel.isRemoved())
                        SubLevelContainer.getContainer(stage.level()).removeSubLevel(serverSubLevel, SubLevelRemovalReason.REMOVED);
                } finally { stage.close(); }
            });
            Minecraft.getInstance().stop();
        }
    }

    private static final class Panel extends AbstractWidget {
        final int colour; int clicks;
        Panel(String label, int colour) { super(0, 0, 200, 44, Component.literal(label)); this.colour = colour; }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
            graphics.fill(0, 0, width, height, colour);
            graphics.drawString(Minecraft.getInstance().font, getMessage(), 8, 8, 0xFFFFFFFF);
            graphics.drawString(Minecraft.getInstance().font, "Clicks / 点击: " + clicks, 8, 23, 0xFFFFD66B);
        }
        @Override public void onClick(double x, double y) { clicks++; }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
