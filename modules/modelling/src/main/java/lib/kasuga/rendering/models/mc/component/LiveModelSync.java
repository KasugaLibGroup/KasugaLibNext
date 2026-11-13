package lib.kasuga.rendering.models.mc.component;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ResourceSystem;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VfsSubscription;
import lib.kasuga.core.vfs.VfsWriteTransaction;
import lib.kasuga.core.vfs.VirtualFileSystem;
import lib.kasuga.core.vfs.backend.DirectoryVfs;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Opt-in local development bridge. Only watches the fixed preview pack's publication marker. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class LiveModelSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final VfsPath LIVE_MOUNT_POINT = VfsPath.of("live/kasuga_live");
    static final VfsPath PACK_MCMETA = VfsPath.of("pack.mcmeta");
    static final VfsPath SYNC_MARKER = VfsPath.of(".kasuga-sync.json");

    private static LiveModelSyncState state;
    private static Path directory;
    private static DirectoryVfs liveVfs;
    private static VfsSubscription liveMount;
    private static long nextPoll;
    private static String lastError;
    private static CompletableFuture<Void> reload;
    private static long completedReloads;

    private LiveModelSync() {}

    public static Path directory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("resourcepacks/kasuga_live");
    }

    static DirectoryVfs openLiveVfs(Path root) throws IOException {
        Files.createDirectories(root);
        DirectoryVfs vfs = new DirectoryVfs("kasuga-live-sync", root);
        try {
            if (!vfs.exists(PACK_MCMETA)) {
                try (VfsWriteTransaction tx = vfs.beginTransaction()) {
                    tx.putString(PACK_MCMETA,
                            "{\"pack\":{\"pack_format\":34,\"description\":\"Kasuga Blockbench live preview\"}}");
                    tx.commit();
                }
            }
            return vfs;
        } catch (IOException | RuntimeException e) {
            vfs.close();
            throw e;
        }
    }

    static DirectoryVfs liveVfs() {
        return liveVfs;
    }

    static void pollMarker(VirtualFileSystem vfs, LiveModelSyncState syncState, long now) throws IOException {
        if (vfs.isRegularFile(SYNC_MARKER)) {
            if (vfs.stat(SYNC_MARKER).size() > 8192) throw new IOException("preview marker exceeds 8 KiB");
            String signature = vfs.readString(SYNC_MARKER);
            var json = JsonParser.parseString(signature).getAsJsonObject();
            if (!json.has("version") || json.get("version").getAsInt() != 1 || !json.has("generation"))
                throw new IOException("unsupported preview marker");
            syncState.observe(signature, now);
        }
    }

    public static void start() throws IOException {
        closeVfs();
        Minecraft mc = Minecraft.getInstance();
        directory = directory();
        liveVfs = openLiveVfs(directory);
        var repository = mc.getResourcePackRepository();
        repository.reload();
        if (!repository.getSelectedIds().contains("file/kasuga_live") && !repository.addPack("file/kasuga_live")) {
            closeVfs();
            throw new IOException("Cannot enable resourcepacks/kasuga_live; check pack.mcmeta");
        }
        if (KasugaLib.isRunning()) {
            liveMount = KasugaLib.getBean(ResourceSystem.class)
                    .mountClientScoped(LIVE_MOUNT_POINT, liveVfs, true);
        }
        state = new LiveModelSyncState(350);
        lastError = null;
        nextPoll = 0;
        // The marker may not exist yet; mounting an empty preview pack still must take effect.
        state.observe("initial", System.nanoTime() / 1_000_000L - 350);
        message("自动同步已开启：" + directory.toAbsolutePath());
    }

    public static void stop() {
        closeVfs();
        state = null;
        directory = null;
        lastError = null;
        // An already running Minecraft reload belongs to Minecraft and is allowed to finish.
    }

    private static void closeVfs() {
        if (liveMount != null) {
            liveMount.close();
            liveMount = null;
        }
        if (liveVfs != null) {
            liveVfs.close();
            liveVfs = null;
        }
    }

    public static boolean enabled() { return state != null; }
    public static long completedReloads() { return completedReloads; }
    public static CompletableFuture<Void> currentReload() { return reload; }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_models")
                .then(Commands.literal("live")
                        .then(Commands.literal("start").executes(context -> {
                            try { start(); return 1; }
                            catch (IOException failure) {
                                context.getSource().sendFailure(Component.literal("无法开启同步：" + failure.getMessage()));
                                return 0;
                            }
                        }))
                        .then(Commands.literal("stop").executes(context -> {
                            stop();
                            context.getSource().sendSuccess(() -> Component.literal("已停止自动同步"), false);
                            return 1;
                        }))
                        .then(Commands.literal("status").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(enabled()
                                    ? "正在监控 " + directory.toAbsolutePath() + "；已重载 " + completedReloads + " 次"
                                    : "自动同步未开启"), false);
                            return 1;
                        }))));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (state == null) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime() / 1_000_000L;
        if (now < nextPoll || mc.getOverlay() != null) return;
        nextPoll = now + 100;
        try {
            if (!mc.getResourcePackRepository().getSelectedIds().contains("file/kasuga_live")) {
                stop();
                message("预览资源包已关闭，自动同步停止；可用 /kasuga_models live start 重新开启");
                return;
            }
            DirectoryVfs vfs = liveVfs;
            if (vfs == null && directory != null) {
                vfs = openLiveVfs(directory);
                liveVfs = vfs;
            }
            if (vfs != null) {
                pollMarker(vfs, state, now);
            }
            if (reload != null && !reload.isDone()) return;
            String publication = state.beginReload(now);
            if (publication == null) return;
            var owner = state;
            try { reload = mc.reloadResourcePacks(); }
            catch (RuntimeException failure) {
                owner.completeReload(publication);
                throw failure;
            }
            reload.whenComplete((ignored, failure) -> mc.execute(() -> {
                if (state != owner) return;
                owner.completeReload(publication);
                if (failure != null) fail("自动重载失败，请修正模型后重新保存", failure);
                else {
                    completedReloads++;
                    lastError = null;
                    LOGGER.info("KASUGA_LIVE_MODEL_RELOAD_COMPLETE generation={}", completedReloads);
                    message("Blockbench 模型已自动重载");
                }
            }));
        } catch (Exception failure) {
            fail("自动同步读取失败：" + failure.getMessage(), failure);
        }
    }

    private static void fail(String text, Throwable failure) {
        if (text.equals(lastError)) return;
        lastError = text;
        LOGGER.warn(text, failure);
        message(text);
    }

    private static void message(String text) {
        if (Minecraft.getInstance().player != null)
            Minecraft.getInstance().player.displayClientMessage(Component.literal(text), false);
    }
}
