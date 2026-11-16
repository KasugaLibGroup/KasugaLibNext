package lib.kasuga.rendering.models.mc.backend;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaShaderInstance;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.SpriteHolder;
import lib.kasuga.rendering.models.uml.backend.DepthBias;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.*;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

/**
 * Menu-safe production shader/buffer/batcher acceptance. Enable with
 * -PkasugaTestModelDepthBias=true on runClient or runClientHeadless (no quick-play world).
 * Writes debug/model-depth-bias/{report.json,*.png} in gameDirectory and stops the client.
 * Does not exercise world scheduling, terrain peeling/resolve, resource reload or shader packs.
 */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelDepthBiasClientSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testModelDepthBias");
    private static final long START = System.nanoTime();
    private static final int SIZE = 128, LEFT = 38, RIGHT = 90, CENTER = 64;
    private static final DepthBias BACK = new DepthBias(1, 1);
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static final Matrix4f VIEW = new Matrix4f().translation(0, 0, -2);
    private static TextureTarget target;
    private static Matrix4f projection;
    private static PoseStack pose;
    private static int warmup, scenario;
    private static boolean finished;

    private ModelDepthBiasClientSmoke() {}

    @SubscribeEvent
    public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished) return;
        try {
            check(System.nanoTime() - START < 180_000_000_000L, "Timeout waiting/running scenario " + scenario);
            var mc = Minecraft.getInstance();
            if (mc.getOverlay() != null || Constants.TEXTURE_BASIC == null
                    || RenderState.UML_SHADER_INSTANCE == null || RenderState.GLOBAL_BATCH_SHADER_INSTANCE == null
                    || RenderState.OPAQUE_RENDER_TYPE == null || RenderState.GLOBAL_CUTOUT_RENDER_TYPE == null) return;
            if (++warmup < 10) return;
            check(!BackendInstance.isIrisEnabled(), "Disable shader packs for this native shader smoke");
            // GuiRenderScope does not restore polygon offset; check production draw scopes before it closes.
            Offset original = Offset.read();
            var nativeShader = (KasugaShaderInstance) RenderState.UML_SHADER_INSTANCE;
            var batchShader = (KasugaShaderInstance) RenderState.GLOBAL_BATCH_SHADER_INSTANCE;
            float nativeParallax = nativeShader.getParallaxScale(), batchParallax = batchShader.getParallaxScale();
            try (var ignored = new GuiRenderScope()) {
                // The missing sprite's fallback normal has alpha zero: parallax would leave its tiny atlas rectangle.
                nativeShader.disableParallax(); batchShader.disableParallax();
                RenderSystem.polygonOffset(3, 7);
                RenderSystem.disablePolygonOffset();
                RenderSystem.disableScissor();
                RenderSystem.colorMask(true, true, true, true);
                RenderSystem.setShaderColor(1, 1, 1, 1);
                RenderSystem.setShaderFogStart(Float.MAX_VALUE);
                RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
                if (target == null) target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
                boolean perspective = (Integer.bitCount(scenario) & 1) != 0;
                projection = perspective ? new Matrix4f().perspective((float) Math.toRadians(60), 1, .1f, 10)
                        : new Matrix4f().ortho(-1, 1, -1, 1, .1f, 10);
                pose = new PoseStack();
                REPORT.put("scenario-" + scenario + "-projection", perspective ? "perspective" : "orthographic");
                if (scenario < 8) solid((scenario & 1) == 0, (scenario & 2) != 0,
                        (scenario & 4) == 0 ? ModelRenderPass.OPAQUE : ModelRenderPass.MASK);
                else transparent((scenario & 1) == 0);
                noError("scenario " + scenario);
            } finally {
                nativeShader.setParallaxScale(nativeParallax); batchShader.setParallaxScale(batchParallax);
                original.restore();
            }
            if (++scenario == 10) finish(null);
        } catch (Throwable failure) { finish(failure); }
    }

    private static void solid(boolean cpu, boolean batch, ModelRenderPass pass) throws Exception {
        String name = scenario + "-" + pass + "-" + (batch ? "batch-mixed" : "direct") + (cpu ? "-cpu" : "-gpu");
        try (var base = quad(cpu, pass, -.85f, .85f, .7f, 0, 1, 0, 1);
             var left = quad(cpu, pass, -.8f, -.08f, .65f, 0, 0, 1, 1);
             var right = quad(batch != cpu, pass, .08f, .8f, .65f, 0, 0, 1, 1);
             var front = quad(cpu, pass, -.18f, .18f, .25f, .4f, 1, 1, 1);
             var batcher = new GlobalModelBatcher()) {
            Pixels background = scene(name + "-base", batch, batcher, base, front);
            Pixels none = scene(name + "-none-forward", batch, batcher, base, left, right, front);
            Pixels reverse = scene(name + "-none-reverse", batch, batcher, front, right, left, base);
            check(background.channel(LEFT, 0) > 50 && background.channel(LEFT, 2) < 30, "Missing red base");
            check(none.channel(LEFT, 2) > 50 && none.channel(LEFT, 0) < 30, "Missing blue overlay");
            same(reverse, LEFT, background, LEFT, "NONE must retain submission order");
            left.instance.setDepthBias(DepthBias.DECAL); right.instance.setDepthBias(BACK);
            for (boolean reversed : new boolean[]{false, true}) {
                Pixels biased = reversed ? scene(name + "-bias-reverse", batch, batcher, front, right, left, base)
                        : scene(name + "-bias-forward", batch, batcher, base, left, right, front);
                same(biased, LEFT, none, LEFT, "Forward bias lost/contaminated");
                same(biased, RIGHT, background, RIGHT, "Positive bias leaked into NONE base");
                same(biased, CENTER, background, CENTER, "Biased overlay pierced real foreground");
                check(biased.depth(LEFT) < background.depth(LEFT), "Forward bias did not change final depth");
                check(biased.depth(RIGHT) == background.depth(RIGHT), "NONE depth changed in mixed batch");
                check(biased.depth(CENTER) == background.depth(CENTER), "Foreground depth changed");
                if (batch) {
                    Pixels direct = reversed ? scene(name + "-direct-reverse", false, batcher, front, right, left, base)
                            : scene(name + "-direct-forward", false, batcher, base, left, right, front);
                    for (int x : new int[]{LEFT, RIGHT, CENTER}) {
                        same(biased, x, direct, x, "Batch/direct color disagreement");
                        check(biased.depth(x) == direct.depth(x), "Batch/direct depth disagreement at " + x);
                    }
                }
            }
            left.instance.setDepthBias(BACK); right.instance.setDepthBias(DepthBias.DECAL);
            Pixels changed = scene(name + "-reassigned", batch, batcher, base, left, right, front);
            same(changed, LEFT, background, LEFT, "Stale left policy after reassignment");
            same(changed, RIGHT, none, RIGHT, "Stale right policy after reassignment");
            // Non-default caller state must also survive the actual production draw/flush.
            RenderSystem.enablePolygonOffset();
            scene(name + "-enabled-sentinel", batch, batcher, left);
            RenderSystem.disablePolygonOffset();
        }
    }

    private static void transparent(boolean cpu) throws Exception {
        String name = scenario + "-translucent-" + (cpu ? "cpu" : "gpu");
        try (var base = quad(cpu, ModelRenderPass.OPAQUE, -.85f, .85f, .7f, 0, 1, 0, 1);
             var overlay = quad(cpu, ModelRenderPass.TRANSLUCENT, -.8f, .8f, .65f, 0, 0, 1, .5f);
             var coverage = quad(cpu, ModelRenderPass.TRANSLUCENT, -.8f, .8f, .65f, 0, 0, 1, 1);
             var front = quad(cpu, ModelRenderPass.OPAQUE, -.18f, .18f, .25f, .4f, 1, 1, 1);
             var batcher = new GlobalModelBatcher()) {
            batcher.begin(VIEW, projection);
            check(!batcher.submit(overlay.backend, overlay.pass, pose.last().pose(), pose.last().normal(), 0, 4),
                    "Translucent geometry must retain sorted draw boundaries");
            batcher.flush(); batcher.endFrame();
            Pixels background = scene(name + "-base", false, batcher, base, front);
            Pixels once = scene(name + "-none-once", false, batcher, base, front, overlay);
            check(Math.abs(once.channel(LEFT, 0) - background.channel(LEFT, 0)) > 25,
                    "Translucent overlay did not contribute");
            overlay.instance.setDepthBias(BACK);
            same(scene(name + "-behind", false, batcher, base, front, overlay), LEFT, background, LEFT,
                    "Positive translucent bias was ignored");
            overlay.instance.setDepthBias(DepthBias.DECAL);
            Pixels biased = scene(name + "-decal-once", false, batcher, base, front, overlay);
            same(biased, LEFT, once, LEFT, "Translucent contribution duplicated or lost");
            same(biased, CENTER, background, CENTER, "Transparent decal pierced foreground");
            check(biased.depth(LEFT) == background.depth(LEFT), "Sorted transparency wrote depth");
            // Capture production prepared records, then mutate the source policy before ALL replays.
            check(overlay.backend.prepareDraw(overlay.pass), "No prepared translucent geometry");
            coverage.instance.setDepthBias(DepthBias.DECAL);
            check(coverage.backend.prepareDraw(coverage.pass), "No prepared coverage geometry");
            var prepared = new MCBackend.PreparedModelDraw(overlay.backend, pose.last(), 0, 4, overlay.instance.getDepthBias());
            var solid = new MCBackend.PreparedModelDraw(coverage.backend, pose.last(), 0, 4, coverage.instance.getDepthBias());
            overlay.instance.setDepthBias(DepthBias.NONE); coverage.instance.setDepthBias(DepthBias.NONE);
            var context = new MCBackendContext(null, pose, null, null, null, null, VIEW, projection, 0, null, null);
            for (int mode : new int[]{0, OitRenderer.OPAQUE_COVERAGE, 4, 5}) {
                clear(); draw(base); draw(front);
                Offset before = Offset.read();
                // Production opaque render state supplies depth writes for the replay depth witness.
                (mode == OitRenderer.OPAQUE_COVERAGE ? solid : prepared)
                        .draw(context, RenderState.getRenderType(false, ModelRenderPass.OPAQUE), mode);
                check(Offset.read().equals(before), "Prepared replay leaked polygon offset mode=" + mode);
                Pixels replay = capture(name + "-captured-mode-" + mode);
                check(replay.depth(LEFT) < background.depth(LEFT), "Prepared replay lost captured DECAL mode=" + mode);
                same(replay, CENTER, background, CENTER, "Prepared replay pierced foreground mode=" + mode);
            }
        }
    }

    private static Pixels scene(String name, boolean batch, GlobalModelBatcher batcher, Quad... quads) throws Exception {
        clear();
        Offset before = Offset.read();
        if (batch) {
            batcher.begin(VIEW, projection);
            try {
                for (Quad quad : quads) check(batcher.submit(quad.backend, quad.pass,
                        pose.last().pose(), pose.last().normal(), 0, 4), "Production batch rejected " + name);
                batcher.flush();
            } finally { batcher.endFrame(); }
        } else for (Quad quad : quads) draw(quad);
        check(Offset.read().equals(before), "Production draw/flush leaked polygon offset: " + name);
        return capture(name);
    }

    private static void draw(Quad quad) {
        Offset before = Offset.read();
        quad.backend.drawBuffer(quad.pass, pose.last(), RenderState.getRenderType(false, quad.pass), VIEW, projection, 0, 4);
        check(Offset.read().equals(before), "Individual draw leaked polygon offset");
    }

    private static void clear() {
        target.bindWrite(true);
        RenderSystem.depthMask(true); RenderSystem.depthFunc(GL11.GL_LEQUAL);
        target.setClearColor(0, 0, 0, 0); target.clear(Minecraft.ON_OSX); target.bindWrite(true);
        check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE, "Incomplete target");
    }

    private record AtlasTexel(TextureAtlasSprite sprite) implements TextureData, SpriteHolder {
        @Override public TextureAtlasSprite getSprite() { return sprite; }
        @Override public boolean shouldDividedByTextureSize() { return false; }
    }

    @SuppressWarnings("unchecked")
    private static Quad quad(boolean cpu, ModelRenderPass pass, float x0, float x1, float halfHeight,
                             float z, float red, float blue, float alpha) {
        Bone root = new Bone("depth_bias_root", new Transform(), null); root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        // The built-in missing sprite always has a solid pink quadrant. Tint it into red/blue/magenta.
        var atlasSprite = Constants.TEXTURE_BASIC.getTextureAtlas().getSprite(MissingTextureAtlasSprite.getLocation());
        var originalImage = atlasSprite.contents().getOriginalImage();
        int fixturePixel = originalImage.getPixelRGBA(4, 12);
        REPORT.put("fixturePixelRGBA", List.of(fixturePixel & 255, fixturePixel >>> 8 & 255,
                fixturePixel >>> 16 & 255, fixturePixel >>> 24 & 255));
        check((fixturePixel & 255) > 100 && (fixturePixel >>> 16 & 255) > 100, "Fixture source texel is not pink");
        Texture texture = new Texture("depth_bias_texel", 16, 16, new AtlasTexel(atlasSprite));
        Material material = new Material(new Texture[]{texture}, new MaterialData() {
            @Override public MaterialAlphaMode alphaMode() {
                return switch (pass) { case OPAQUE -> MaterialAlphaMode.OPAQUE; case MASK -> MaterialAlphaMode.MASK;
                    case TRANSLUCENT -> MaterialAlphaMode.BLEND; };
            }
        });
        material.addSprite(new SpriteSet(null, new Sprite(texture, new Vector2f(), new Vector2f(1, 0),
                new Vector2f(1, 1), new Vector2f(0, 1), new Vector4f(red, 0, blue, alpha), null, null, null)));
        Vertex[] vertices = {new Vertex(new Vector3f(x0, -halfHeight, z), null), new Vertex(new Vector3f(x1, -halfHeight, z), null),
                new Vertex(new Vector3f(x1, halfHeight, z), null), new Vertex(new Vector3f(x0, halfHeight, z), null)};
        Mesh mesh = new Mesh(vertices, new Vector3f(0, 0, 1), new Transform(), new Material[]{material}, null);
        for (int i = 0; i < vertices.length; i++) {
            Vertex vertex = vertices[i];
            vertex.setBinding(new BoneBinding(new Pair[]{Pair.of(root, 1f)}, BoneBindingFunc.BDEF, null));
            // A small UV square stays inside the pink quadrant and supplies non-degenerate tangents.
            vertex.addUV(mesh, material, new Vector2f(i == 0 || i == 3 ? .2f : .3f, i < 2 ? .7f : .8f));
        }
        MaterialSet materials = new MaterialSet(texture, material);
        Model asset = new Model(vertices, new Mesh[]{mesh}, new Bone[]{root}, skeleton, materials, MeshMode.QUADS, null, null);
        ModelInstance instance = new ModelInstance(asset, new Transform(), null, null, new MaterialSetInstance(materials), null);
        check(instance.getDepthBias().equals(DepthBias.NONE), "New instance default is not NONE");
        instance.updateImmediate();
        return new Quad(instance, new BackendInstance(instance, ForkJoinPool.commonPool(), cpu, true), pass);
    }

    private record Quad(ModelInstance instance, BackendInstance backend, ModelRenderPass pass) implements AutoCloseable {
        @Override public void close() throws Exception { try { backend.close(); } finally { instance.close(); } }
    }

    private record Offset(boolean fill, boolean line, boolean point, float factor, float units) {
        static Offset read() { return new Offset(GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL),
                GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_LINE), GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_POINT),
                GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR), GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS)); }
        void restore() { RenderSystem.polygonOffset(factor, units);
            if (fill) RenderSystem.enablePolygonOffset(); else RenderSystem.disablePolygonOffset(); }
    }

    private record Pixels(int[] rgba, float[] depths) {
        int channel(int x, int c) { return rgba[(CENTER * SIZE + x) * 4 + c]; }
        float depth(int x) { return depths[CENTER * SIZE + x]; }
    }

    private static Pixels capture(String name) throws Exception {
        target.bindWrite(false);
        var color = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        var depth = MemoryUtil.memAllocFloat(SIZE * SIZE);
        int[] rgba = new int[SIZE * SIZE * 4]; float[] depths = new float[SIZE * SIZE];
        try {
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, color);
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            for (int i = 0; i < rgba.length; i++) rgba[i] = color.get(i) & 255;
            depth.get(depths);
        } finally { MemoryUtil.memFree(color); MemoryUtil.memFree(depth); }
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int p = (y * SIZE + x) * 4;
            image.setRGB(x, SIZE - 1 - y, rgba[p + 3] << 24 | rgba[p] << 16 | rgba[p + 1] << 8 | rgba[p + 2]);
        }
        Files.createDirectories(output()); ImageIO.write(image, "PNG", output().resolve(name + ".png").toFile());
        Pixels pixels = new Pixels(rgba, depths);
        REPORT.put(name, Map.of("leftDepth", pixels.depth(LEFT), "rightDepth", pixels.depth(RIGHT),
                "foregroundDepth", pixels.depth(CENTER), "leftRGBA", List.of(pixels.channel(LEFT, 0),
                        pixels.channel(LEFT, 1), pixels.channel(LEFT, 2), pixels.channel(LEFT, 3))));
        noError(name);
        return pixels;
    }

    private static void same(Pixels actual, int x, Pixels expected, int refX, String message) {
        for (int y = CENTER - 2; y <= CENTER + 2; y++) for (int dx = -2; dx <= 2; dx++) for (int c = 0; c < 4; c++)
            check(Math.abs(actual.rgba[(y * SIZE + x + dx) * 4 + c]
                    - expected.rgba[(y * SIZE + refX + dx) * 4 + c]) <= 3, message + " at " + x + ", channel " + c);
    }

    private static Path output() { return Minecraft.getInstance().gameDirectory.toPath().resolve("debug/model-depth-bias"); }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private static void noError(String where) { int error = GL11.glGetError(); check(error == GL11.GL_NO_ERROR, where + " GL error=" + error); }

    private static void finish(Throwable failure) {
        if (finished) return;
        finished = true;
        try { if (target != null) target.destroyBuffers(); }
        catch (Throwable cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        REPORT.put("passed", failure == null); REPORT.put("completedScenarios", scenario);
        REPORT.put("failure", failure == null ? "" : failure.toString());
        REPORT.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
        REPORT.put("elapsedSeconds", (System.nanoTime() - START) / 1_000_000_000.0);
        REPORT.put("limitations", "Native shaders; offscreen direct/batch and prepared replay only. No world/terrain peel resolve, reload, shader packs or other drivers.");
        try {
            Files.createDirectories(output());
            Files.writeString(output().resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
        } catch (Exception write) { if (failure == null) failure = write; else failure.addSuppressed(write); }
        if (failure == null) LogUtils.getLogger().info("MODEL_DEPTH_BIAS_SMOKE_PASS {}", output());
        else LogUtils.getLogger().error("MODEL_DEPTH_BIAS_SMOKE_FAIL {}", output(), failure);
        Minecraft.getInstance().stop();
    }
}
