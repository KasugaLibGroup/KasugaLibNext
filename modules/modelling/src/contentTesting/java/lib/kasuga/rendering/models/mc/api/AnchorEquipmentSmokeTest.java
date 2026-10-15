package lib.kasuga.rendering.models.mc.api;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.*;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.util.List;

/** Neutral synthetic rig + vanilla items only; no user body model or textures are displayed. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class AnchorEquipmentSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testAnchorEquipment");
    private static final long START = System.nanoTime();
    private static McModelHandle handle;
    private static McModelEquipment equipment;
    private static CameraHandle camera;
    private static GearOwner owner;
    private static byte[] baseline;
    private static int warmup, frames, rendered, changedPixels, phase;
    private static boolean finished;

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 120_000_000_000L) { finish(new IllegalStateException("Equipment test timed out")); return; }
        if (mc.player == null || mc.level == null || mc.getOverlay() != null || ++warmup < 60) return;
        try {
            if (camera == null) {
                mc.setScreen(null); mc.options.pauseOnLostFocus = false;
                var origin = mc.player.position().add(0, 40, 0);
                ModelInstance instance = rig();
                instance.getSkeletonInstance().enableFloatingOrigin(new Vector3d(origin.x, origin.y, origin.z));
                handle = McModelHandle.custom(ResourceLocation.parse("test:equipment"), null, ResourceLocation.parse("test:equipment_actor"),
                        root -> instance, ignored -> null);
                handle.mount();
                owner = new GearOwner(mc.level);
                owner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
                owner.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.IRON_PICKAXE));
                owner.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
                equipment = new McModelEquipment(handle, () -> owner).bindHands("right_grip", "left_grip")
                        .bindHeadItem("head", new Transform().scale(.6f, .6f, .6f));
                camera = MinecraftCameras.createFree("test:anchor_equipment", new WorldCameraView(origin.x, origin.y + 1.3,
                        origin.z + 4, 180, 0, 0, 55, 320, 180), new CameraRenderSettings(2,
                        CameraRenderSettings.Quality.FAST, CameraRenderSettings.Shader.disabled()), frame -> {
                    byte[] rgba = RgbaReadback.copy(frame.resource());
                    if (phase == 0 && ++frames >= 30) { baseline = rgba; phase = 1; }
                    else if (phase > 0) {
                        frames++;
                        int different = 0;
                        for (int i = 0; i < rgba.length; i += 4) {
                            int delta = Math.abs((rgba[i] & 255) - (baseline[i] & 255))
                                    + Math.abs((rgba[i + 1] & 255) - (baseline[i + 1] & 255))
                                    + Math.abs((rgba[i + 2] & 255) - (baseline[i + 2] & 255));
                            if (delta > 50) different++;
                        }
                        changedPixels = Math.max(changedPixels, different);
                    }
                });
            }
            owner.left = frames >= 60;
            handle.instance().getSkeletonInstance().rotate("right", new Quaternionf().rotateZ((float) Math.sin(frames * .08) * .4f));
            handle.instance().updateImmediate();
            if (camera.state() == CameraState.FAILED) throw new IllegalStateException("Equipment camera failed", camera.failure().orElse(null));
        } catch (Exception failure) { finish(failure); }
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (!ENABLED || finished || phase == 0 || equipment == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES
                || !MinecraftWorldViews.currentViewId().equals("test:anchor_equipment")) return;
        try {
            var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
            rendered += equipment.render(event.getPoseStack(), buffers, event.getCamera().getPosition(), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            buffers.endBatch();
        } catch (Exception failure) { finish(failure); }
    }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || camera == null) return;
        if (GL11.glGetError() != GL11.GL_NO_ERROR) { finish(new IllegalStateException("Equipment GL error")); return; }
        if (frames >= 100) {
            if (rendered < 150 || changedPixels < 50) finish(new IllegalStateException("Items did not reach the rendered view: " + rendered + "/" + changedPixels));
            else finish(null);
        }
    }
    private static ModelInstance rig() {
        Bone root = new Bone("root", new Transform(), null);
        Bone right = new Bone("right", new Transform().translate(-.4f, 1.1f, 0), null);
        Bone left = new Bone("left", new Transform().translate(.4f, 1.1f, 0), null);
        Bone head = new Bone("head", new Transform().translate(0, 1.8f, 0), null);
        root.setChildren(new Bone[]{right, left, head});
        for (Bone bone : root.getChildren()) { bone.setParent(root); bone.setChildren(new Bone[0]); }
        Skeleton rig = new Skeleton(new Bone[]{root, right, left, head}, root, new Anchor[0], null, new Transform());
        rig.defineAnchor("right_grip", right, new Transform()); rig.defineAnchor("left_grip", left, new Transform());
        rig.defineAnchor("head", head, new Transform());
        Model model = new Model(new Vertex[0], new Mesh[0], rig.getBones(), rig, new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        return new ModelInstance(model, null, null, null, null, null);
    }
    private static final class GearOwner extends ArmorStand {
        boolean left;
        GearOwner(net.minecraft.world.level.Level level) { super(level, 0, 0, 0); }
        @Override public HumanoidArm getMainArm() { return left ? HumanoidArm.LEFT : HumanoidArm.RIGHT; }
    }
    private static void finish(Exception failure) {
        if (finished) return; finished = true;
        var mc = Minecraft.getInstance();
        try {
            if (equipment != null) equipment.close(); if (camera != null) camera.close();
            if (handle != null) { var instance = handle.instance(); handle.destroy(); if (instance != null) instance.close(); }
            var report = mc.gameDirectory.toPath().resolve("debug/anchor-equipment.json"); Files.createDirectories(report.getParent());
            Files.writeString(report, "{\"passed\":" + (failure == null) + ",\"frames\":" + frames + ",\"itemsRendered\":" + rendered + ",\"changedPixels\":" + changedPixels + "}");
            if (failure == null) LogUtils.getLogger().info("ANCHOR_EQUIPMENT_SMOKE_PASS {}", report);
            else LogUtils.getLogger().error("ANCHOR_EQUIPMENT_SMOKE_FAIL", failure);
        } catch (Exception cleanup) { LogUtils.getLogger().error("Equipment smoke cleanup failed", cleanup); }
        mc.stop();
    }
}
