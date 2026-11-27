package test.kasuga.modelling;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.registration.minecraft.block.shape.BlockShapeBindings;
import lib.kasuga.rendering.models.mc.shape.ShapeOutlineGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.nio.file.Files;
import java.util.LinkedHashMap;

/** Opt-in headless acceptance for data-driven factory shape installation and Minecraft cache use. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class BlockShapeHeadlessSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testBlockShapes");
    private static boolean finished;

    private BlockShapeHeadlessSmoke() {
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        finished = true;
        Exception failure = null;
        LinkedHashMap<String, Object> report = new LinkedHashMap<>();
        try {
            Block block = BuiltInRegistries.BLOCK.get(FanDataDrivenFactories.BLOCK_ID);
            if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
                throw new IllegalStateException("Data-driven fan block was not registered");
            }
            if (!BlockShapeBindings.isInstalled(block)) {
                throw new IllegalStateException("Data-driven fan factory did not install its shape plan");
            }
            BlockState off = block.defaultBlockState();
            BlockState on = off.setValue(FanBlock.RUNNING, true);
            VoxelShape offShape = off.getShape(
                    EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
            VoxelShape onShape = on.getShape(
                    EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
            if (offShape.isEmpty() || offShape != onShape) {
                throw new IllegalStateException("Static shape was absent or reconverted between states");
            }
            if (!off.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
                    || !off.getCollisionShape(
                    EmptyBlockGetter.INSTANCE,
                    BlockPos.ZERO,
                    CollisionContext.empty()).isEmpty()) {
                throw new IllegalStateException("Native collision override was not preserved");
            }
            if (!off.getOcclusionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                throw new IllegalStateException("Native occlusion override was not preserved");
            }
            var outline = ShapeOutlineGeometry.local(FanDataDrivenFactories.FAN_SHAPE);
            if (outline.size() != 12) {
                throw new IllegalStateException("Expected twelve external edges, got " + outline.size());
            }
            report.put("outlineEdges", outline.size());
            report.put("shapeBounds", offShape.bounds().toString());
        } catch (Exception exception) {
            failure = exception;
        }

        report.put("passed", failure == null);
        report.put("failure", failure == null ? "" : failure.toString());
        try {
            var mc = Minecraft.getInstance();
            var path = mc.gameDirectory.toPath().resolve("debug/block-shapes-headless.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            if (failure == null) {
                LogUtils.getLogger().info("BLOCK_SHAPE_HEADLESS_PASS {}", path);
            } else {
                LogUtils.getLogger().error("BLOCK_SHAPE_HEADLESS_FAIL", failure);
            }
            mc.stop();
        } catch (Exception reportingFailure) {
            LogUtils.getLogger().error("Block-shape headless reporting failed", reportingFailure);
            Minecraft.getInstance().stop();
        }
    }
}
