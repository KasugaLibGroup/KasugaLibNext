package test.kasuga.create.demo.preview;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.scene.MinecraftStage;
import lib.kasuga.scene.SceneModels;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.joml.Matrix4f;

import java.util.UUID;

/** Playable, development-only gallery; the displayed contraption is never spawned in the world. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CreateModelGallery {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("kasuga_demo:create_model_gallery"));
    public static final Vec3 CENTER = new Vec3(0, 108, 0);
    private static volatile Session session;
    private static boolean autoStarted;
    private CreateModelGallery() {}
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_create_gallery")
                .executes(context -> start() ? 1 : 0)
                .then(Commands.literal("factory").executes(context -> startFactory() ? 1 : 0))
                .then(Commands.literal("stop").executes(context -> { stop(); return 1; })));
    }
    /** Same gallery controls and source owner as rig/windmill, selected from the blueprint menu. */
    public static boolean startFactory() {
        String path = System.getProperty("kasuga.minecraftPreview.schematic");
        if (path == null || path.isBlank()) return reject("No external blueprint was supplied to this development client.");
        if (!start()) return false;
        session.factoryRequested = true;
        Minecraft.getInstance().setScreen(null);
        return true;
    }
    public static boolean start() {
        if (session != null) return true;
        var mc = Minecraft.getInstance(); var server = mc.getSingleplayerServer();
        if (mc.player == null || server == null || server.isPublished()) return reject("Create gallery requires private singleplayer.");
        var next = new Session(server, mc.player.getUUID()); session = next;
        server.execute(() -> {
            if (session != next) return;
            var player = server.getPlayerList().getPlayer(next.player);
            if (player == null) { next.failure = "Player left before gallery entry"; return; }
            try {
                next.stage = MinecraftStage.open(player, DIMENSION, new Vec3(0, 106, 24), 180, 0, stage -> {});
                if (session != next) { next.stage.close(); return; }
                next.nativeWindmill = CreateWindmillExample.install(next.stage);
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true; player.onUpdateAbilities();
                next.entered = true;
            } catch (Exception error) { next.failure = error.toString(); LogUtils.getLogger().error("Cannot enter Create gallery", error); }
        });
        mc.setScreen(null); return true;
    }
    public static boolean active() { return session != null && session.entry != null; }
    static SceneModels.Entry entry() { return session == null ? null : session.entry; }
    public static boolean openControls() {
        if (!active()) return false;
        Minecraft.getInstance().setScreen(new InspectionScreen(session)); return true;
    }
    static LayerLayout layout() { return session.layout; }
    static boolean factorySelected() { return session != null && session.factorySelected; }
    static com.simibubi.create.content.contraptions.ControlledContraptionEntity windmillEntity() {
        var current = session;
        if (current == null || current.nativeWindmill == null || Minecraft.getInstance().level == null) return null;
        var entity = Minecraft.getInstance().level.getEntity(current.nativeWindmill.entityId);
        return entity instanceof com.simibubi.create.content.contraptions.ControlledContraptionEntity rotor ? rotor : null;
    }
    static boolean selectWindmill(boolean windmill) {
        var current = session;
        if (current == null || windmill && current.windmillEntry == null) return false;
        current.windmillSelected = windmill;
        current.factorySelected = false;
        current.rigEntry.visible(!windmill);
        if (current.windmillEntry != null) current.windmillEntry.visible(windmill);
        if (current.factoryEntry != null) current.factoryEntry.visible(false);
        current.entry = windmill ? current.windmillEntry : current.rigEntry;
        current.baseTransform = windmill ? current.windmillBase : current.rigBase;
        current.minimumLayer = windmill ? current.windmillMinimum : current.rigMinimum;
        current.maximumLayer = windmill ? current.windmillMaximum : current.rigMaximum;
        current.layer = current.maximumLayer;
        current.layout = LayerLayout.ASSEMBLED; current.scale = 1; current.all = current.cumulative = true; current.selected = null;
        current.entry.layers(current.layout).transform(current.baseTransform);
        return true;
    }
    private static boolean selectFactory() {
        var current = session;
        if (current == null || current.scene == null) return false;
        if (current.factoryEntry == null) {
            lib.kasuga.rendering.models.mc.scene.MinecraftModelSource source = null;
            try {
                source = lib.kasuga.create.client.model.CreateModelSources.schematic(Minecraft.getInstance().level,
                        java.nio.file.Path.of(System.getProperty("kasuga.minecraftPreview.schematic")));
                var frame = source.snapshot(LayerLayout.ASSEMBLED, 0);
                var bounds = frame.bounds(); var center = bounds.minimum().add(bounds.maximum()).mul(.5f);
                float scale = (float) (17 / Math.max(bounds.maxX()-bounds.minX(), Math.max(bounds.maxY()-bounds.minY(),bounds.maxZ()-bounds.minZ())));
                current.factoryBase = new Matrix4f().scale(scale).translate(-center.x,-center.y,-center.z);
                var layers = source.snapshot(LayerLayout.ASSEMBLED.separation(.001),0);
                current.factoryMinimum = layers.parts().stream().mapToInt(part -> part.layer()).min().orElse(0);
                current.factoryMaximum = layers.parts().stream().mapToInt(part -> part.layer()).max().orElse(0);
                current.factoryDisplay = new lib.kasuga.rendering.models.mc.scene.display.ModelDisplayTarget(source).autoPanel();
                current.factoryEntry = current.scene.add(current.factoryDisplay,CENTER).transform(current.factoryBase).visible(false);
            } catch (Exception error) {
                if (source != null && current.factoryEntry == null) source.close();
                reject("Cannot inspect blueprint: " + error.getMessage());
                LogUtils.getLogger().error("Cannot inspect external blueprint",error); return false;
            }
        }
        current.rigEntry.visible(false);
        if (current.windmillEntry != null) current.windmillEntry.visible(false);
        current.factoryEntry.visible(true);
        current.factorySelected = true; current.windmillSelected = false; current.entry = current.factoryEntry;
        current.baseTransform = current.factoryBase;
        current.minimumLayer = current.factoryMinimum;current.maximumLayer = current.factoryMaximum;current.layer = current.maximumLayer;
        current.layout = LayerLayout.ASSEMBLED;current.scale=1;current.all=current.cumulative=true;current.selected=null;
        current.entry.layers(current.layout).transform(current.baseTransform);
        return true;
    }
    @SubscribeEvent public static void serverTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var current = session;
        if (current != null && current.nativeWindmill != null) current.nativeWindmill.tick();
    }
    @SubscribeEvent public static void key(net.neoforged.neoforge.client.event.InputEvent.Key event) {
        if (active() && Minecraft.getInstance().screen == null && event.getKey() == org.lwjgl.glfw.GLFW.GLFW_KEY_I
                && event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS) openControls();
    }
    static String failure() { return session == null ? null : session.failure; }
    public static java.util.Optional<lib.kasuga.rendering.models.mc.scene.MinecraftModelSource.BlockHit> inspect() {
        var current = session; var mc = Minecraft.getInstance();
        if (current == null || current.scene == null) return java.util.Optional.empty();
        var camera = mc.gameRenderer.getMainCamera();
        var from = camera.getPosition();
        var hit = current.scene.pick(from, from.add(new Vec3(camera.getLookVector()).scale(64)),
                mc.getTimer().getGameTimeDeltaPartialTick(false));
        current.selected = hit.orElse(null); current.selectedUntil = System.nanoTime() + 5_000_000_000L;
        return hit;
    }
    @SubscribeEvent public static void click(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre event) {
        if (active() && Minecraft.getInstance().screen == null && event.getButton() == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS) inspect();
            event.setCanceled(true);
        }
    }
    @SubscribeEvent public static void hud(net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) {
        var current = session; var mc = Minecraft.getInstance();
        if (current == null || current.scene == null) return;
        String selected = current.factorySelected ? "Factory" : current.windmillSelected ? "Windmill" : "Rig";
        event.getGuiGraphics().drawString(mc.font, selected + " | I layers / explode | Click inspect | WASD fly | Space up | Shift down", 8, 8, 0xFFF0F8FF);
        if (current.selected != null && System.nanoTime() < current.selectedUntil)
            lib.kasuga.rendering.models.mc.backend.ui.ModelBlockInfo.draw(event.getGuiGraphics(), mc.font, current.selected, 8, 24);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (!autoStarted && Boolean.getBoolean("kasuga.createGallery.auto") && mc.level != null && mc.player != null && mc.getOverlay() == null) {
            autoStarted = true; start();
        }
        var current = session;
        if (current == null) return;
        if (mc.level == null || mc.player == null || current.failure != null) { stop(); return; }
        if (current.scene != null && !mc.level.dimension().equals(DIMENSION)) { stop(); return; }
        if (!current.entered || !mc.level.dimension().equals(DIMENSION) || mc.getOverlay() != null) return;
        if (current.scene != null) {
            var rotor = windmillEntity();
            if (current.windmillEntry == null && rotor != null && rotor.getContraption() != null && rotor.isReadyForRender()) {
                var windmill = lib.kasuga.create.client.model.CreateModelSources.contraption(rotor.getContraption());
                try {
                    var bounds = windmill.snapshot(LayerLayout.ASSEMBLED, 0).bounds();
                    var center = bounds.minimum().add(bounds.maximum()).mul(.5f);
                    current.windmillBase = new Matrix4f().translate(-center.x, -center.y, -center.z);
                    var layered = windmill.snapshot(LayerLayout.ASSEMBLED.separation(.001), 0);
                    current.windmillMinimum = layered.parts().stream().mapToInt(part -> part.layer()).min().orElse(0);
                    current.windmillMaximum = layered.parts().stream().mapToInt(part -> part.layer()).max().orElse(0);
                    current.windmillEntry = current.scene.add(windmill, CENTER).transform(current.windmillBase).visible(false);
                    LogUtils.getLogger().info("CREATE_WINDMILL_PREVIEW_READY entity={} backlink={}", rotor.getId(), rotor.getContraption().entity == rotor);
                } catch (Exception error) { windmill.close(); LogUtils.getLogger().error("Cannot preview native windmill", error); }
            }
            if (current.factoryRequested) {
                current.factoryRequested = false;
                if (selectFactory()) {
                    openControls(); LogUtils.getLogger().info("CREATE_FACTORY_INSPECTION_READY");
                }
            }
            return;
        }
        lib.kasuga.rendering.models.mc.scene.MinecraftModelSource source = null;
        try {
            source = CreateAirshipExample.create();
            var bounds = source.snapshot(LayerLayout.ASSEMBLED, 0).bounds();
            var center = bounds.minimum().add(bounds.maximum()).mul(.5f);
            var layerFrame = source.snapshot(LayerLayout.ASSEMBLED.separation(.001), 0);
            current.minimumLayer = layerFrame.parts().stream().mapToInt(part -> part.layer()).min().orElse(0);
            current.maximumLayer = layerFrame.parts().stream().mapToInt(part -> part.layer()).max().orElse(0);
            current.layer = current.maximumLayer;
            current.baseTransform = new Matrix4f().translate(-center.x, -center.y, -center.z);
            current.rigBase = current.baseTransform; current.rigMinimum = current.minimumLayer; current.rigMaximum = current.maximumLayer;
            current.scene = SceneModels.open(mc.level);
            current.entry = current.scene.add(source, CENTER).transform(current.baseTransform);
            current.rigEntry = current.entry;
            LogUtils.getLogger().info("CREATE_MODEL_GALLERY_READY center={} layers={}..{}", CENTER, bounds.minY(), bounds.maxY());
        } catch (Exception failure) {
            if (source != null && current.entry == null) source.close();
            current.failure = failure.toString(); stop(); LogUtils.getLogger().error("Cannot display Create gallery", failure);
        }
    }
    public static void stop() {
        var previous = session; session = null;
        if (previous == null) return;
        if (Minecraft.getInstance().screen instanceof InspectionScreen) Minecraft.getInstance().setScreen(null);
        if (previous.scene != null) previous.scene.close();
        previous.server.execute(() -> {
            if (previous.nativeWindmill != null) previous.nativeWindmill.close();
            if (previous.stage != null) previous.stage.close();
        });
    }
    private static boolean reject(String text) {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(Component.literal(text), false);
        return false;
    }
    private static final class Session {
        final MinecraftServer server; final UUID player;
        volatile MinecraftStage stage;
        volatile boolean entered;
        volatile String failure;
        volatile CreateWindmillExample nativeWindmill;
        SceneModels scene; SceneModels.Entry entry;
        SceneModels.Entry rigEntry, windmillEntry;
        SceneModels.Entry factoryEntry;
        lib.kasuga.rendering.models.mc.scene.display.ModelDisplayTarget factoryDisplay;
        Matrix4f rigBase, windmillBase;
        Matrix4f factoryBase;
        int rigMinimum, rigMaximum, windmillMinimum, windmillMaximum;
        int factoryMinimum, factoryMaximum;
        boolean windmillSelected, factorySelected, factoryRequested;
        lib.kasuga.rendering.models.mc.scene.MinecraftModelSource.BlockHit selected;
        long selectedUntil;
        LayerLayout layout = LayerLayout.ASSEMBLED;
        Matrix4f baseTransform;
        int minimumLayer, maximumLayer, layer;
        boolean all = true, cumulative = true;
        float scale = 1;
        Session(MinecraftServer server, UUID player) { this.server = server; this.player = player; }
    }
    private static final class InspectionScreen extends net.minecraft.client.gui.screens.Screen {
        private final Session state;
        private InspectionScreen(Session state) { super(Component.literal("World model inspection / 世界模型检查")); this.state = state; }
        private void apply() {
            state.layout = state.layout.range(state.all ? Integer.MIN_VALUE : state.cumulative ? state.minimumLayer : state.layer,
                    state.all ? Integer.MAX_VALUE : state.layer);
            state.entry.layers(state.layout).transform(new Matrix4f().scale(state.scale).mul(state.baseTransform));
        }
        @Override protected void init() {
            int bottom = height - 30;
            if(state.factorySelected && state.factoryDisplay!=null) {
                addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Animation + counts / 分层统计"),button->{
                    state.entry.layers(LayerLayout.ASSEMBLED);
                    state.factoryDisplay.layers(state.layout);
                    Minecraft.getInstance().setScreen(new lib.kasuga.rendering.models.mc.backend.ui.WorldDisplayScreen(
                            Component.literal("Factory / 蓝图"),state.factoryDisplay,state.factoryDisplay,true,false,()->{
                        state.layout=state.factoryDisplay.layers();state.entry.layers(state.layout);
                        Minecraft.getInstance().setScreen(new InspectionScreen(state));
                    }));
                }).bounds(12,32,220,20).build());
            }
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("◀"), button -> {
                state.all = false; state.layer = Math.max(state.minimumLayer, state.layer - 1); apply();
            }).bounds(12, bottom, 24, 20).build());
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("▶"), button -> {
                state.all = false; state.layer = Math.min(state.maximumLayer, state.layer + 1); apply();
            }).bounds(40, bottom, 24, 20).build());
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("All"), button -> {
                state.all = true; apply();
            }).bounds(68, bottom, 34, 20).build());
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Stack / slice"), button -> {
                state.all = false; state.cumulative = !state.cumulative; apply();
            }).bounds(106, bottom, 86, 20).build());
            addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(200, bottom, Math.max(72, width - 212), 20,
                    Component.empty(), Math.min(1, state.layout.separation() / 3)) {
                { updateMessage(); }
                @Override protected void updateMessage() { setMessage(Component.literal("Explode " + Math.round(value * 100) + "%")); }
                @Override protected void applyValue() { state.layout = state.layout.separation(value * 3); apply(); }
            });
            addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(12, bottom - 28, 180, 20,
                    Component.empty(), (state.scale - .25) / 1.75) {
                { updateMessage(); }
                @Override protected void updateMessage() { setMessage(Component.literal("Scale " + String.format(java.util.Locale.ROOT, "%.2fx", .25 + value * 1.75))); }
                @Override protected void applyValue() { state.scale = (float) (.25 + value * 1.75); apply(); }
            });
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Reset"), button -> {
                state.layout = LayerLayout.ASSEMBLED; state.scale = 1; state.all = state.cumulative = true; state.layer = state.maximumLayer;
                state.selected = null; apply(); rebuildWidgets();
            }).bounds(200, bottom - 28, 70, 20).build());
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Done / fly"), button -> onClose())
                    .bounds(278, bottom - 28, 90, 20).build());
            var target = addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal(state.windmillSelected ? "Windmill / Rig" : "Rig / Windmill"), button -> {
                if (selectWindmill(!state.windmillSelected)) rebuildWidgets();
            }).bounds(376, bottom - 28, 112, 20).build());
            target.active = state.windmillEntry != null;
            String blueprint = System.getProperty("kasuga.minecraftPreview.schematic");
            if (blueprint != null && !blueprint.isBlank()) addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Factory"), button -> {
                if (selectFactory()) rebuildWidgets();
            }).bounds(496,bottom-28,70,20).build());
        }
        @Override public void render(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, float partialTick) {
            graphics.fill(4, height - 85, width - 4, height - 4, 0xDD101926);
            graphics.drawString(font, title.copy().append(state.factorySelected ? " / Factory" : state.windmillSelected ? " / Windmill" : " / Rig"), 12, height - 80, 0xFFF0F8FF);
            graphics.drawString(font, state.all ? "All layers" : "Layer " + state.layer + (state.cumulative ? " / stack" : " / slice"),
                    Math.max(380, width - 130), height - 52, 0xFFB8CBE0);
            super.render(graphics, x, y, partialTick);
        }
        @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_I) { onClose(); return true; }
            return super.keyPressed(key, scanCode, modifiers);
        }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void renderBackground(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, float partialTick) {}
    }
}
