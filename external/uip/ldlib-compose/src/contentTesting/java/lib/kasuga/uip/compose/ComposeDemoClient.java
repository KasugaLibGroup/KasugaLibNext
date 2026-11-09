package lib.kasuga.uip.compose;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.uip.ldlib.LdMenuElement;
import lib.kasuga.uip.ldlib.ModelPreviewElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

/** Real menu packets, recomposition and projected pointer input; never fabricates a client mirror. */
@EventBusSubscriber(modid = ComposeUiProvider.MOD_ID, value = Dist.CLIENT)
public final class ComposeDemoClient {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.composeDemo");
    private static final boolean WORLD = Boolean.parseBoolean(System.getProperty("kasuga.composeDemo.world", "true"));
    private static final String PEER = System.getProperty("kasuga.composeDemo.peer", "host");
    private static final boolean PAIRED = Boolean.getBoolean("kasuga.composeDemo.paired");
    private static final long START = System.nanoTime();
    private static final LinkedHashMap<String, Object> REPORT = new LinkedHashMap<>();
    private static boolean opened, finished, sawPeer, connecting;
    private static int warmup, frames, stage, settle, disposals;
    private static ComposeDemoMenu holder, previousHolder;
    private static LdMenuElement world, previousWorld;
    private static Matrix4f transform;
    private static Vector2f pointer;
    private static UIElement stableRow;
    private static GuiModelPreview preview;
    private static FrameTexture retired;
    private static long originalHash;

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        mc.setWindowActive(true);
        String server = System.getProperty("kasuga.composeDemo.server");
        if (!connecting && server != null && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            connecting = true;
            ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(server),
                    new ServerData("Compose LAN fixture", server, ServerData.Type.LAN), false, null);
        }
        if (mc.getSingleplayerServer() != null && mc.getSingleplayerServer().getPlayerCount() >= 2) sawPeer = true;
        if (mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        if (!opened && ++warmup >= 90) {
            mc.options.guiScale().set(2);
            mc.options.pauseOnLostFocus = false;
            mc.resizeDisplay();
            String port = System.getProperty("kasuga.composeDemo.lanPort");
            if (port != null) {
                if (!mc.getSingleplayerServer().publishServer(GameType.CREATIVE, false, Integer.parseInt(port)))
                    throw new IllegalStateException("Cannot publish the isolated Compose LAN fixture");
                try { Files.writeString(output().resolveSibling("server-ready.json"), "{\"port\":" + port + "}"); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }
            opened = true;
            mc.getConnection().sendCommand("kasuga_compose");
        }
        if (WORLD && mc.player.containerMenu instanceof ModularUIContainerMenu menu
                && mc.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() == menu
                && ComposeDemoMenu.client != null && ComposeDemoMenu.client.getClientModel().getValue().getRows().size() == 3) {
            var camera = mc.gameRenderer.getMainCamera();
            var center = camera.getPosition().add(camera.getLookVector().x * 2.5,
                    camera.getLookVector().y * 2.5, camera.getLookVector().z * 2.5).add(0, .5, 0);
            transform = new Matrix4f().translation((float) center.x, (float) center.y, (float) center.z)
                    .rotate(camera.rotation()).scale(.0048f, -.0048f, 1).translate(-200, -180, 0);
            world = LdMenuElement.attach(Constants.UI_BACKEND, menu, 400, 360, transform);
            Constants.UI_BACKEND.openPointer();
        }
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || System.getProperty("kasuga.composeDemo.capture") == null) return;
        try {
            if (System.nanoTime() - START > Long.getLong("kasuga.composeDemo.timeoutSeconds", 240L) * 1_000_000_000L)
                throw new IllegalStateException("Compose smoke timed out at stage " + stage);
            var mc = Minecraft.getInstance();
            if (!opened || mc.player == null || ComposeDemoMenu.client == null || ++frames < 20) return;
            holder = ComposeDemoMenu.client;
            if (stage < 19 && WORLD && (world == null || world.isClosed())) return;
            switch (stage) {
                case 0 -> {
                    if (!rows(3) || model().renderedFrames() < 10) return;
                    if (PAIRED && PEER.equals("host") && !sawPeer) return;
                    if (PAIRED && PEER.equals("guest") && !Files.isRegularFile(output().resolveSibling("isolation-ready.json"))) return;
                    check(mc.player.containerMenu instanceof ModularUIContainerMenu, "Missing real server menu");
                    preview = model();
                    check(pixels(preview.currentFrame())[0] > 100, "No model geometry in composition");
                    REPORT.put("initialModelPixels", pixels(preview.currentFrame())[0]);
                    stableRow = element("row-2");
                    holder.setTickProbe(7);
                    move("compose-add");
                }
                case 1 -> click();
                case 2 -> {
                    if (!rows(4) || state().getTickProbe() != 7) return;
                    REPORT.put("menuSyncTickRoundTrip", true);
                    if (PAIRED && PEER.equals("host")) {
                        if (ComposeDemoMenu.servers.size() < 2) return;
                        boolean isolated = mc.getSingleplayerServer().submit(() -> ComposeDemoMenu.servers.entrySet().stream()
                                .allMatch(entry -> entry.getValue().getServerModel().getRows().size()
                                        == (entry.getKey().equals(mc.player.getUUID()) ? 4 : 3))).get(3, TimeUnit.SECONDS);
                        check(isolated, "One player's menu changed another player's server model");
                        REPORT.put("perPlayerServerIsolation", true);
                        Files.writeString(output().resolveSibling("isolation-ready.json"), "{\"hostRows\":4,\"guestRows\":3}");
                    }
                    var invalid = new CompoundTag(); invalid.putString("id", "not-an-integer");
                    holder.getHost().sendMessage("remove", invalid);
                    move("compose-ping");
                }
                case 3 -> click();
                case 4 -> {
                    if (state().getPings() != 1) return;
                    check(rows(4), "Invalid remove payload changed server data");
                    REPORT.put("fixedRpcAfterDynamicWidgets", true);
                    move("remove-1");
                }
                case 5 -> click();
                case 6 -> {
                    if (!rows(3) || holder.getUi().select("#row-1").findAny().isPresent()) return;
                    check(element("row-2") == stableRow, "key(row.id) did not retain the untouched row");
                    move("compose-clear");
                }
                case 7 -> click();
                case 8 -> { if (!rows(0)) return; move("compose-add"); }
                case 9 -> click();
                case 10 -> { if (!rows(1)) return; move("compose-ping"); }
                case 11 -> click();
                case 12 -> {
                    if (state().getPings() != 2) return;
                    REPORT.put("serverRowsRoundTrip", true);
                    originalHash = pixels(model().currentFrame())[1];
                    move("compose-rotate");
                }
                case 13 -> click();
                case 14 -> {
                    if (++settle < 15) return;
                    check(pixels(model().currentFrame())[1] != originalHash, "Compose state did not rotate the model");
                    preview = model(); retired = preview.currentFrame();
                    move("compose-toggle-model");
                }
                case 15 -> click();
                case 16 -> {
                    if (holder.getUi().select("#compose-model").findAny().isPresent()) return;
                    check(preview.isClosed() && !GL11.glIsTexture(retired.textureId())
                            && !GL30.glIsFramebuffer(retired.framebufferId()), "Conditional removal retained model GPU resources");
                    REPORT.put("conditionalModelCleanup", true);
                    move("compose-toggle-model");
                }
                case 17 -> click();
                case 18 -> {
                    if (holder.getUi().select("#compose-model").findAny().isEmpty() || model().renderedFrames() < 10) return;
                    check(pixels(model().currentFrame())[0] > 100, "Recreated model has no geometry");
                    save();
                    REPORT.put("footerPixels", textPixels("compose-footer"));
                    check(textPixels("compose-footer") > 20, "Following Chinese UI text has no visible pixels");
                    if (mc.getSingleplayerServer() != null) {
                        boolean empty = mc.getSingleplayerServer().submit(() -> ComposeDemoMenu.servers.values().stream()
                                .allMatch(s -> s.getHost().getChildren().isEmpty())).get(3, TimeUnit.SECONDS);
                        check(empty, "The server executed a composition");
                        REPORT.put("serverDoesNotCompose", true);
                    }
                    preview = model(); retired = preview.currentFrame();
                    disposals = ComposeDemoMenu.disposals.get();
                    previousHolder = holder; previousWorld = world;
                    if (world != null) world.close(); else mc.player.closeContainer();
                }
                case 19 -> {
                    if (!preview.isClosed() || mc.player.containerMenu != mc.player.inventoryMenu) return;
                    check(ComposeDemoMenu.disposals.get() > disposals, "Composition disposal was not called");
                    check(!GL11.glIsTexture(retired.textureId()) && !GL30.glIsFramebuffer(retired.framebufferId()), "Menu close retained GPU objects");
                    REPORT.put("menuAndCompositionCleanup", true);
                    mc.getConnection().sendCommand("kasuga_compose");
                }
                case 20 -> {
                    if (holder == previousHolder || !rows(3) || !(mc.player.containerMenu instanceof ModularUIContainerMenu)) return;
                    if (WORLD && (world == previousWorld || world == null || world.isClosed())) return;
                    if (previousWorld != null) previousWorld.close();
                    check(mc.player.containerMenu == holder.getUi().getMenu(), "Old host closed a newer menu");
                    holder.getHost().sendMessage("close");
                }
                case 21 -> {
                    if (mc.player.containerMenu != mc.player.inventoryMenu || WORLD && !world.isClosed()) return;
                    REPORT.put("reopenAndServerClose", true);
                    if (System.getProperty("kasuga.composeDemo.lanPort") != null) {
                        if (!sawPeer || !Files.isRegularFile(output().resolveSibling("guest-report.json"))) return;
                        check(JsonParser.parseString(Files.readString(output().resolveSibling("guest-report.json")))
                                .getAsJsonObject().get("passed").getAsBoolean(), "The guest client smoke failed");
                        REPORT.put("twoRealClients", true);
                    }
                    finish(null); return;
                }
                default -> throw new IllegalStateException("Unexpected stage " + stage);
            }
            stage++;
        } catch (Exception failure) { finish(failure); }
    }

    private static ComposeDemoMenu.Model state() { return holder.getClientModel().getValue(); }
    private static boolean rows(int count) {
        return state().getRows().size() == count && holder.getUi().select(".compose-row").count() == count;
    }
    private static UIElement element(String id) { return holder.getUi().select("#" + id).findFirst().orElseThrow(); }
    private static GuiModelPreview model() { return ((ModelPreviewElement) element("compose-model")).preview(); }
    private static Vector2f project(UIElement element, float fx, float fy) {
        var mc = Minecraft.getInstance();
        if (!WORLD) return new Vector2f(element.getPositionX() + element.getSizeWidth() * fx, element.getPositionY() + element.getSizeHeight() * fy);
        var camera = mc.gameRenderer.getMainCamera();
        var point = new Vector4f(element.getPositionX() + element.getSizeWidth() * fx,
                element.getPositionY() + element.getSizeHeight() * fy, 0, 1);
        transform.transform(point);
        point.sub((float) camera.getPosition().x, (float) camera.getPosition().y, (float) camera.getPosition().z, 0);
        point.rotate(new Quaternionf(camera.rotation()).conjugate());
        mc.gameRenderer.getProjectionMatrix(mc.options.fov().get()).transform(point);
        return new Vector2f((point.x / point.w + 1) * .5f * mc.getWindow().getGuiScaledWidth(),
                (1 - point.y / point.w) * .5f * mc.getWindow().getGuiScaledHeight());
    }
    private static void move(String id) throws Exception {
        var mc = Minecraft.getInstance(); pointer = project(element(id), .5f, .5f);
        var method = MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
        method.setAccessible(true);
        method.invoke(mc.mouseHandler, mc.getWindow().getWindow(), pointer.x * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
                pointer.y * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight());
    }
    private static void click() {
        var screen = Minecraft.getInstance().screen;
        check(screen != null, "Missing input screen");
        screen.mouseClicked(pointer.x, pointer.y, 0); screen.mouseReleased(pointer.x, pointer.y, 0);
    }
    private static long[] pixels(FrameTexture frame) {
        check(frame != null, "Preview has no frame");
        long count = 0, hash = 1;
        try (var scope = new GuiRenderScope()) {
            var data = MemoryUtil.memAlloc(frame.width() * frame.height() * 4);
            try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frame.framebufferId());
                GL11.glReadPixels(0, 0, frame.width(), frame.height(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
                for (int i = 0; i < data.limit(); i += 4) {
                    if ((data.get(i + 3) & 255) > 32) count++;
                    hash = hash * 31 + data.getInt(i);
                }
            } finally { MemoryUtil.memFree(data); }
        }
        return new long[]{count, hash};
    }
    private static long textPixels(String id) {
        var mc = Minecraft.getInstance(); var a = project(element(id), 0, 0); var b = project(element(id), 1, 1);
        long count = 0;
        try (var scope = new GuiRenderScope(); var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            float sx = (float) image.getWidth() / mc.getWindow().getGuiScaledWidth(), sy = (float) image.getHeight() / mc.getWindow().getGuiScaledHeight();
            for (int y = Math.max(0, (int) (a.y * sy)); y < Math.min(image.getHeight(), (int) (b.y * sy) + 1); y++)
                for (int x = Math.max(0, (int) (a.x * sx)); x < Math.min(image.getWidth(), (int) (b.x * sx) + 1); x++) {
                    int p = image.getPixelRGBA(x, y);
                    if ((p & 255) > 180 && (p >>> 8 & 255) > 180 && (p >>> 16 & 255) > 180) count++;
                }
        }
        return count;
    }
    private static Path output() throws Exception {
        var path = Path.of(System.getProperty("kasuga.composeDemo.capture")).toAbsolutePath();
        Files.createDirectories(path.getParent()); return path;
    }
    private static void save() throws Exception {
        try (var scope = new GuiRenderScope(); var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { image.writeToFile(output()); }
    }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private static void finish(Exception failure) {
        finished = true;
        REPORT.put("peer", PEER); REPORT.put("world", WORLD); REPORT.put("stage", stage);
        REPORT.put("passed", failure == null); REPORT.put("failure", failure == null ? "" : failure.toString());
        try { Files.writeString(output().resolveSibling(PEER + "-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT)); }
        catch (Exception e) { LogUtils.getLogger().error("Cannot write Compose smoke report", e); }
        if (failure != null) LogUtils.getLogger().error("COMPOSE_SMOKE_FAIL", failure);
        else LogUtils.getLogger().info("COMPOSE_SMOKE_PASS {}", PEER);
        if (world != null) world.close();
        Minecraft.getInstance().stop();
    }
}
