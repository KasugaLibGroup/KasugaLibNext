package test.kasuga.create.demo.preview;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.create.client.model.CreateModelSources;
import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.models.mc.backend.ui.MinecraftModelPreviewScreen;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.SchematicModelData;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Read-only acceptance for an explicitly supplied local blueprint; user data never ships with the demo. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CreateExternalSchematicSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testExternalSchematic");
    private static final LinkedHashMap<String, Object> REPORT = new LinkedHashMap<>();
    private static GuiModelPreview preview;
    private static int stage, wait;
    private static boolean finished;
    private static final long START = System.nanoTime();
    private static Path output() { return Minecraft.getInstance().gameDirectory.toPath().resolve("debug/external-schematic"); }
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        if (System.nanoTime() - START > 300_000_000_000L) { finish(new IllegalStateException("External blueprint timed out")); return; }
        var mc = Minecraft.getInstance();
        if (preview != null || mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        try {
            var path = Path.of(System.getProperty("kasuga.minecraftPreview.schematic"));
            var root = NbtIo.readCompressed(path, NbtAccounter.create(SchematicModelData.MAX_NBT_BYTES));
            var lookup = mc.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK);
            var data = SchematicModelData.structure(root, lookup, 0);
            int properties = 0;
            for (var entry : root.getList("palette", Tag.TAG_COMPOUND)) {
                var encoded = (CompoundTag) entry; var state = NbtUtils.readBlockState(lookup, encoded);
                var props = encoded.getCompound("Properties");
                for (String name : props.getAllKeys()) {
                    var property = state.getBlock().getStateDefinition().getProperty(name);
                    if (property == null || !encodedProperty(state, property).equals(props.getString(name)))
                        throw new IllegalStateException("Blueprint state did not survive decoding: " + encoded);
                    properties++;
                }
            }
            REPORT.put("blocks", data.size()); REPORT.put("statePropertiesVerified", properties);
            preview = new GuiModelPreview(CreateModelSources.schematic(mc.level, path));
            mc.setScreen(new MinecraftModelPreviewScreen(Component.literal("External Create factory / 工厂蓝图"), preview, 0, root.getList("size", Tag.TAG_INT).getInt(1) - 1));
            Files.createDirectories(output()); mc.options.pauseOnLostFocus = false; mc.setWindowActive(true);
        } catch (Exception failure) { finish(failure); }
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static String encodedProperty(net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.block.state.properties.Property property) { return property.getName(state.getValue(property)); }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || preview == null || ++wait < 4) return;
        try {
            if (preview.failure() != null) throw new IllegalStateException(preview.failure());
            var frame = preview.currentFrame(); if (frame == null) throw new IllegalStateException("External blueprint published no frame");
            byte[] rgba = RgbaReadback.copy(frame); long count = 0;
            for (int i = 3; i < rgba.length; i += 4) if ((rgba[i] & 255) > 32) count++;
            if (count < 100) throw new IllegalStateException("External blueprint has no visible geometry");
            REPORT.put("stage-" + stage, java.util.Map.of("coveredPixels", count, "width", frame.width(), "height", frame.height()));
            var mc = Minecraft.getInstance();
            try (var scope = new GuiRenderScope(); var screenshot = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                screenshot.writeToFile(output().resolve("factory-" + stage + ".png"));
            }
            if (stage == 0) preview.setLayers(LayerLayout.ASSEMBLED.range(20, 30));
            else if (stage == 1) preview.setLayers(LayerLayout.ASSEMBLED.range(20, 30).separation(.2));
            else { finish(null); return; }
            stage++; wait = 0;
        } catch (Exception failure) { finish(failure); }
    }
    private static void finish(Exception failure) {
        if (finished) return; finished = true;
        try {
            if (preview != null) preview.close();
            REPORT.put("passed", failure == null); REPORT.put("failure", failure == null ? "" : failure.toString());
            Files.createDirectories(output()); Files.writeString(output().resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("CREATE_EXTERNAL_SCHEMATIC_PASS {}", output());
            else LogUtils.getLogger().error("CREATE_EXTERNAL_SCHEMATIC_FAIL", failure);
        } catch (Exception error) { LogUtils.getLogger().error("Cannot report blueprint validation", error); }
        finally { Minecraft.getInstance().stop(); }
    }
}
