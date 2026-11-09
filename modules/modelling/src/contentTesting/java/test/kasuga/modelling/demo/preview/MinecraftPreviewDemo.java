package test.kasuga.modelling.demo.preview;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import lib.kasuga.rendering.models.mc.backend.ui.MinecraftModelPreviewScreen;
import lib.kasuga.rendering.models.mc.scene.*;
import lib.kasuga.scene.SceneModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.joml.Matrix4f;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Development examples consume public APIs; automatic acceptance lives in a separate smoke. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class MinecraftPreviewDemo {
    @FunctionalInterface public interface Factory { MinecraftModelSource create() throws Exception; }
    public record Example(String name, Factory factory) {}
    private static final List<Example> EXAMPLES = new ArrayList<>();
    private static SceneModels scene;
    private static Object sceneLevel;
    private static boolean autoOpened;
    static {
        register("vanilla-end-city", () -> MinecraftModelSources.structure(Minecraft.getInstance().level,
                ResourceLocation.parse("minecraft:end_city/base_floor")));
        register("fluid-section-boundary", () -> MinecraftModelSources.blocks(Minecraft.getInstance().level, List.of(
                new StructureBlockInfo(new BlockPos(15, 0, 15), Blocks.STONE.defaultBlockState(), null),
                new StructureBlockInfo(new BlockPos(16, 0, 15), Blocks.STONE.defaultBlockState(), null),
                new StructureBlockInfo(new BlockPos(15, 1, 15), Blocks.WATER.defaultBlockState(), null),
                new StructureBlockInfo(new BlockPos(16, 1, 15), Blocks.GLASS.defaultBlockState(), null))));
        register("litematic", MinecraftPreviewDemo::litematic);
    }
    private MinecraftPreviewDemo() {}
    public static void register(String name, Factory factory) {
        if (EXAMPLES.stream().anyMatch(example -> example.name().equals(name))) throw new IllegalArgumentException("Duplicate example " + name);
        EXAMPLES.add(new Example(name, factory));
    }
    public static List<Example> examples() { return List.copyOf(EXAMPLES); }
    public static boolean selectorOpen() { return Minecraft.getInstance().screen instanceof Selector; }
    public static void open() { if (Minecraft.getInstance().level != null) Minecraft.getInstance().setScreen(new Selector()); }
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_model_preview")
                .executes(context -> { Minecraft.getInstance().tell(MinecraftPreviewDemo::open); return 1; })
                .then(Commands.literal("clear").executes(context -> { clearWorld(); return 1; })));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (scene != null && sceneLevel != mc.level) clearWorld();
        if (!autoOpened && Boolean.getBoolean("kasuga.minecraftPreview.interactive") && mc.level != null
                && mc.player != null && mc.getOverlay() == null) {
            autoOpened = true; open();
        }
    }
    public static void clearWorld() {
        if (scene != null) scene.close();
        scene = null; sceneLevel = null;
    }
    private static void showWorld(Example choice) throws Exception {
        var mc = Minecraft.getInstance();
        MinecraftModelSource source = choice.factory().create();
        try {
            var bounds = source.snapshot(LayerLayout.ASSEMBLED, 0).bounds();
            float scale = 3 / (float) Math.max(.001, Math.max(bounds.maxX() - bounds.minX(),
                    Math.max(bounds.maxY() - bounds.minY(), bounds.maxZ() - bounds.minZ())));
            var center = bounds.minimum().add(bounds.maximum()).mul(.5f);
            var camera = mc.gameRenderer.getMainCamera();
            Vec3 position = camera.getPosition().add(new Vec3(camera.getLookVector()).scale(6));
            clearWorld(); scene = SceneModels.open(mc.level); sceneLevel = mc.level;
            scene.add(source, position).transform(new Matrix4f().scale(scale).translate(-center.x, -center.y, -center.z));
            mc.setScreen(null);
        } catch (Exception failure) { source.close(); clearWorld(); throw failure; }
    }
    private static final class Selector extends Screen {
        private String error;
        private Selector() { super(Component.literal("Minecraft model previews / 模型预览")); }
        @Override protected void init() {
            int y = Math.max(60, height / 2 - EXAMPLES.size() * 13);
            for (var choice : examples()) {
                addRenderableWidget(Button.builder(Component.literal(choice.name()), button -> run(() -> {
                    var source = choice.factory().create();
                    GuiModelPreview preview = null;
                    try {
                        var bounds = source.snapshot(LayerLayout.ASSEMBLED, 0).bounds();
                        preview = new GuiModelPreview(source);
                        minecraft.setScreen(new MinecraftModelPreviewScreen(Component.literal(choice.name()), preview,
                                (int) Math.floor(bounds.minY()), (int) Math.ceil(bounds.maxY()) - 1, MinecraftPreviewDemo::open));
                    } catch (Exception failure) { if (preview != null) preview.close(); else source.close(); throw failure; }
                })).bounds(width / 2 - 170, y, 270, 20).build());
                addRenderableWidget(Button.builder(Component.literal("World"), button -> run(() -> showWorld(choice)))
                        .bounds(width / 2 + 106, y, 64, 20).build());
                y += 26;
            }
            addRenderableWidget(Button.builder(Component.literal("Clear world models"), button -> clearWorld())
                    .bounds(width / 2 - 170, y + 6, 340, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Return to game / 返回游戏"), button -> onClose())
                    .bounds(width / 2 - 170, y + 32, 340, 20).build());
        }
        @FunctionalInterface private interface Action { void run() throws Exception; }
        private void run(Action action) {
            try { action.run(); } catch (Exception failure) { error = failure.toString(); LogUtils.getLogger().error("Cannot open preview example", failure); }
        }
        @Override public void render(GuiGraphics graphics, int x, int y, float partialTick) {
            graphics.fill(0, 0, width, height, 0xEE121B27);
            graphics.drawCenteredString(font, title, width / 2, 20, 0xFFE8F5FF);
            graphics.drawCenteredString(font, "LMB rotate | MMB pan | Wheel zoom | R reset | Esc back", width / 2, 38, 0xFFB8CBE0);
            if (error != null) graphics.drawString(font, error, 12, height - 18, 0xFFFFA090);
            super.render(graphics, x, y, partialTick);
        }
        @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partialTick) {}
        @Override public boolean isPauseScreen() { return false; }
    }
    private static MinecraftModelSource litematic() throws Exception {
        var root = new CompoundTag(); root.putInt("Version", 7);
        root.putInt("MinecraftDataVersion", net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        var region = new CompoundTag(); region.put("Position", vector(2, 1, 0)); region.put("Size", vector(-2, 3, 1));
        var palette = new ListTag();
        for (var block : List.of(Blocks.AIR, Blocks.RED_CONCRETE, Blocks.LIME_CONCRETE, Blocks.BLUE_CONCRETE)) palette.add(NbtUtils.writeBlockState(block.defaultBlockState()));
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", new long[]{1L | 1L << 2 | 2L << 4 | 2L << 6 | 3L << 8 | 3L << 10});
        var regions = new CompoundTag(); regions.put("negative-size", region); root.put("Regions", regions);
        var path = Minecraft.getInstance().gameDirectory.toPath().resolve("ksg_debug/minecraft-preview/fixture.litematic");
        Files.createDirectories(path.getParent()); NbtIo.writeCompressed(root, path);
        return MinecraftModelSources.litematic(Minecraft.getInstance().level, path);
    }
    private static CompoundTag vector(int x, int y, int z) { var tag = new CompoundTag(); tag.putInt("x", x); tag.putInt("y", y); tag.putInt("z", z); return tag; }
}
