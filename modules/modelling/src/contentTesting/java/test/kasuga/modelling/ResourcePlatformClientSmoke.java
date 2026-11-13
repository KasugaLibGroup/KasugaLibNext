package test.kasuga.modelling;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.identity.*;
import lib.kasuga.core.resource.ResourceSystem;
import lib.kasuga.core.resource.lifecycle.ResourceCoordinator;
import lib.kasuga.core.resource.pack.ApprovedResourcePacks;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.*;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.mc.source.resource.ModelResourceSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Menu-only real GPU evidence: initial discovery, same-ID replacement, then deletion. No world is opened. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ResourcePlatformClientSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testResources");
    private static final long START = System.nanoTime();
    private static final String PATH = "模型/列车 A.bbmodel";
    private static final ResourceUri PARENT = ResourceUri.file("resource-smoke", "main", PATH);
    private static final ResourceLocation MODEL = ResourceLocation.parse(MinecraftNameCodec.encode(PARENT.child("minecraft/model"), ".bbmodel").replace(":v1/", ":models/v1/"));
    private static final ResourceLocation SPRITE = ResourceLocation.parse(MinecraftNameCodec.encode(PARENT.child("texture/uuid/736d6f6b65"), ""));
    private static final InMemoryVfs INPUT = new InMemoryVfs("resource-smoke");
    private static final ApprovedResourcePacks PACKS = new ApprovedResourcePacks();
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static ModelResourceSource source;
    private static CompletableFuture<Void> reload;
    private static int phase;
    private static boolean finished;
    private static ResourceCoordinator clientScope, processScope;
    private ResourcePlatformClientSmoke() {}

    @EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
    public static final class Discovery {
        @SubscribeEvent public static void find(AddPackFindersEvent event) {
            if (!ENABLED || event.getPackType() != PackType.CLIENT_RESOURCES) return;
            try {
                if (source == null) {
                    writeModel(0xffff2200);
                    var descriptor = new ResourceSourceDescriptor("smoke", "resource-smoke", "main", 100, ResourceSourceDescriptor.Origin.EXTERNAL);
                    source = new ModelResourceSource(ResourceSources.vfs(descriptor, INPUT, false, "", "", InputPolicy.DEFAULT), InputPolicy.DEFAULT, 16, true);
                    var routing = ResourceRouting.decode("{\"format_version\":1,\"bindings\":[{\"id\":\"root\",\"source_id\":\"resource-smoke\",\"schema_id\":\"minecraft_resource_schema\"}]}", ResourceRouting.builtinTemplates(), Set.of("resource-smoke"));
                    PACKS.register("smoke", List.of(source), routing);
                }
                PACKS.onAddPackFinders(event);
            } catch (Exception failure) { throw new IllegalStateException("Resource smoke discovery failed", failure); }
        }
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        if (!ENABLED || finished) return;
        try {
            check(System.nanoTime() - START < 180_000_000_000L, "resource smoke timed out");
            var mc = Minecraft.getInstance();
            if (mc.getOverlay() != null || PipelineRegistry.bbmodel() == null) return;
            if (reload != null) {
                if (!reload.isDone()) return;
                reload.getNow(null); // Propagate a completed reload failure; never wait on the render thread.
                reload = null;
            }
            if (phase == 0) {
                var resources = KasugaLib.getBean(ResourceSystem.class);
                clientScope = resources.createClientResourceScope("resource-smoke/client");
                processScope = resources.createProcessResourceScope("resource-smoke/process");
                check(PipelineRegistry.bbmodel().hasModel(MODEL), "generated model missing from initial publication");
                assertPixel(255, 34, 0);
                REPORT.put("initialModelAndGpuTexture", true);
                writeModel(0xff00dd44);
                phase = 1; reload = mc.reloadResourcePacks();
            } else if (phase == 1) {
                check(PipelineRegistry.bbmodel().hasModel(MODEL), "same-ID model disappeared on replacement");
                assertPixel(0, 221, 68);
                REPORT.put("replacementUsesNewGpuBytes", true);
                INPUT.delete(VfsPath.of(PATH));
                phase = 2; reload = mc.reloadResourcePacks();
            } else {
                check(!PipelineRegistry.bbmodel().hasModel(MODEL), "deleted model survived publication");
                var atlas = (TextureAtlas) mc.getTextureManager().getTexture(RenderState.KSG_LAYER_0);
                check(!atlas.getSprite(SPRITE).contents().name().equals(SPRITE), "deleted texture survived atlas rebuild");
                REPORT.put("deletionRemovesModelAndSprite", true);
                finish(null);
            }
        } catch (Throwable failure) { finish(failure); }
    }
    private static void assertPixel(int red, int green, int blue) {
        var atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager().getTexture(RenderState.KSG_LAYER_0);
        var sprite = atlas.getSprite(SPRITE);
        check(sprite.contents().name().equals(SPRITE), "generated texture is the missing sprite");
        check(sprite.contents().width() == 2 && sprite.contents().height() == 2, "generated texture dimensions changed");
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas.getId());
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        var pixels = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width, height), 4));
        try {
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            int x = Math.round(sprite.getU0() * width), y = Math.round(sprite.getV0() * height), offset = (y * width + x) * 4;
            check((pixels.get(offset) & 255) == red && (pixels.get(offset + 1) & 255) == green && (pixels.get(offset + 2) & 255) == blue,
                    "GPU atlas pixel does not match captured source bytes");
        } finally { MemoryUtil.memFree(pixels); GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous); }
    }
    private static void writeModel(int color) throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, color);
        var png = new ByteArrayOutputStream(); ImageIO.write(image, "png", png);
        INPUT.writeString(VfsPath.of(PATH), """
                {"meta":{"format_version":"5.0","model_format":"free"},"resolution":{"width":2,"height":2},
                 "textures":[{"uuid":"smoke","name":"same","width":2,"height":2,"source":"data:image/png;base64,%s"}],
                 "elements":[{"uuid":"cube","name":"cube","from":[0,0,0],"to":[16,16,16],
                 "faces":{"north":{"uv":[0,0,2,2],"texture":0}}}],"outliner":["cube"]}
                """.formatted(Base64.getEncoder().encodeToString(png.toByteArray())));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void shuttingDown(GameShuttingDownEvent event) {
        if (!ENABLED || clientScope == null) return;
        boolean closed = clientScope.isClosed() && processScope.isClosed();
        REPORT.put("ownerScopesCloseAtGameShutdown", closed);
        if (!closed) { REPORT.put("passed", false); REPORT.put("failure", "Game shutdown did not close owner scopes"); }
        try {
            Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("debug/resource-platform/report.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
        } catch (Exception failure) { LogUtils.getLogger().error("Cannot record shutdown evidence", failure); }
    }
    private static void finish(Throwable failure) {
        if (finished) return; finished = true;
        try {
            REPORT.put("passed", failure == null);
            REPORT.put("failure", failure == null ? "" : failure.toString());
            REPORT.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
            var path = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/resource-platform/report.json");
            Files.createDirectories(path.getParent()); Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("RESOURCE_PLATFORM_CLIENT_SMOKE_PASS {}", path);
            else LogUtils.getLogger().error("RESOURCE_PLATFORM_CLIENT_SMOKE_FAIL", failure);
            PACKS.close(); if (source != null) source.close(); INPUT.close();
        } catch (Exception cleanup) { LogUtils.getLogger().error("Resource smoke cleanup failed", cleanup); }
        Minecraft.getInstance().stop();
    }
}
