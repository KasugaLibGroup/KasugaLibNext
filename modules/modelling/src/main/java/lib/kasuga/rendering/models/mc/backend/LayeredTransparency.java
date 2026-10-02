package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.uml.backend.gpu.GlslProgram;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.GlStateBackup;
import org.lwjgl.opengl.*;
import org.slf4j.Logger;

/** Native Sodium terrain + model transparency, peeled together front-to-back.
 * Nothing touches scene color until resolve, so a failed peel can fall back safely.
 */
public final class LayeredTransparency implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static LayeredTransparency armed, active;
    // Stay inside vanilla's 12-slot texture-state cache, even without Iris.
    // Material samplers use 0..4; skinning/batch buffers reserve 7, 10 and 11.
    private static final int FOOTPRINT_UNIT = 5, PREVIOUS_UNIT = 6, SCENE_UNIT = 8, COVERAGE_UNIT = 9;
    private static final int PEEL = 1, FOOTPRINT = 2, OUTSIDE = 3;
    private final MCBackend backend;
    private MCBackendContext context;
    private final PeelTarget scene = new PeelTarget(), accumulation = new PeelTarget();
    private final PeelTarget footprint = new PeelTarget();
    // Ping-pong targets are reused after layer zero; keep its depth separately
    // for vanilla's later cloud/particle passes, without changing scene depth
    // while we are still peeling model and terrain fragments.
    private final PeelTarget nearest = new PeelTarget();
    private PeelTarget previous = new PeelTarget(), current = new PeelTarget();
    private boolean disabled, handled, logged, overflowLogged;
    private int program, vao, layerSampler, nearestSampler, writeDepthUniform;
    private int phase;
    private int captureFrames;
    private final java.util.Map<Integer, Integer> enabledUniforms = new java.util.HashMap<>();
    private final PeelLayerLoop peelLoop = new PeelLayerLoop();
    private int observedPassBound, ringBusyFrames;
    private long profileFrames, profileStart, profileElapsed, profileWaiting, profilePeeling;
    private int profileLayers, profileQueries;

    LayeredTransparency(MCBackend backend) { this.backend = backend; }

    void beginFrame() {
        handled = false;
        // Shader reloads may recycle GL program names between frames.
        enabledUniforms.clear();
        if (armed == this) armed = null;
        context = null;
    }

    void arm(MCBackendContext context) {
        if (disabled || BackendInstance.isIrisEnabled() || Minecraft.useShaderTransparency()
                || !Boolean.parseBoolean(System.getProperty("kasuga.layeredTransparency", "true"))
                || backend.getRenderingObjects().isEmpty()) return;
        this.context = context;
        armed = this;
    }

    boolean handled() { return handled; }

    /** Called by the Sodium terrain hook, before its normal translucent draw. */
    public static boolean renderWorld(Runnable terrain) {
        if (active != null || armed == null) return false;
        LayeredTransparency renderer = armed;
        armed = null;
        return renderer.render(terrain);
    }

    /** Reassert after BOTH Sodium and Kasuga shader setup; their RenderTypes reset GL state. */
    public static void bindShader() {
        bindShader(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
    }

    public static void bindShader(int shader) {
        if (shader == 0) return;
        if (active == null) {
            int enabled = GL20.glGetUniformLocation(shader, "ksg_PeelEnabled");
            if (enabled >= 0) GL20.glUniform1i(enabled, 0);
            return;
        }
        int enabled = active.enabledUniforms.computeIfAbsent(shader, id -> {
            GL20.glUniform1i(GL20.glGetUniformLocation(id, "ksg_PeelPrevious"), PREVIOUS_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(id, "ksg_PeelScene"), SCENE_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(id, "ksg_PeelCoverage"), COVERAGE_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(id, "ksg_PeelFootprint"), FOOTPRINT_UNIT);
            return GL20.glGetUniformLocation(id, "ksg_PeelEnabled");
        });
        if (enabled < 0) throw new IllegalStateException("Terrain/model shader lacks depth-peeling support");
        GL20.glUniform1i(enabled, active.phase);
        int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        if (active.phase != OUTSIDE) bindTexture(SCENE_UNIT, active.scene.depth);
        // Never bind the footprint for sampling while writing it.
        if (active.phase != FOOTPRINT) bindTexture(FOOTPRINT_UNIT, active.footprint.color);
        if (active.phase == PEEL) {
            bindTexture(PREVIOUS_UNIT, active.previous.depth);
            bindTexture(COVERAGE_UNIT, active.accumulation.color);
        }
        activeTexture(unit);
        if (active.phase == OUTSIDE) {
            // COLOR_DEPTH_WRITE assumes the default depth mask is already on.
            // Our fullscreen accumulation explicitly disabled it.
            RenderSystem.enableDepthTest();
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            GL11.glDepthMask(true);
            return; // Keep Sodium's blend state and main framebuffer.
        }
        if (active.phase == FOOTPRINT) {
            active.footprint.bind();
            RenderSystem.disableBlend();
            GL11.glDisable(GL11.GL_BLEND);
            RenderSystem.disableDepthTest();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            RenderSystem.depthMask(false);
            GL11.glDepthMask(false);
            GL11.glColorMask(true, true, true, true);
            return;
        }
        active.current.bind();
        // Nearest surviving fragment wins independently of terrain/model submission order.
        RenderSystem.disableBlend();
        GL11.glDisable(GL11.GL_BLEND);
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderSystem.depthFunc(GL11.GL_LESS);
        GL11.glDepthFunc(GL11.GL_LESS);
        RenderSystem.depthMask(true);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
    }

    private boolean render(Runnable terrain) {
        long started = System.nanoTime();
        var main = Minecraft.getInstance().getMainRenderTarget();
        if (context == null || !main.useDepth) return false;
        GlStateBackup backup = new GlStateBackup();
        RenderSystem.backupGlState(backup);
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] units = {0, FOOTPRINT_UNIT, PREVIOUS_UNIT, SCENE_UNIT, COVERAGE_UNIT};
        int[] textures = new int[units.length];
        for (int i = 0; i < units.length; i++) {
            activeTexture(GL13.GL_TEXTURE0 + units[i]);
            textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        activeTexture(oldUnit);
        boolean resolving = false;
        try {
            // Culling/empty material passes must not trigger fullscreen clears,
            // depth copies or target allocation. Terrain still draws normally.
            var models = backend.prepareTranslucent(context);
            if (models.isEmpty()) return false;
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            scene.ensure(main.width, main.height, false, PeelTarget.sceneFormat(main.frameBufferId));
            scene.copyDepth(main.frameBufferId);
            var floatDepth = OitDepthFormat.matching(32, GL11.GL_FLOAT, 0);
            previous.ensure(main.width, main.height, true, floatDepth);
            current.ensure(main.width, main.height, true, floatDepth);
            nearest.ensure(main.width, main.height, false, floatDepth);
            accumulation.ensure(main.width, main.height, true, null);
            footprint.ensure(main.width, main.height, GL30.GL_R8, null);
            ensureProgram();
            previous.clear(0.0);
            accumulation.clear(1.0);
            footprint.clear(1.0);
            PeelDebugCapture capture = Boolean.getBoolean("kasuga.debugTransparency")
                    && ++captureFrames == Math.max(1, Integer.getInteger("kasuga.debugTransparency.frame", 120))
                    ? PeelDebugCapture.create() : null;
            if (capture != null) {
                capture.target("scene", scene);
                capture.capture("main-before", main.frameBufferId, main.width, main.height, true, false);
            }
            int limit = Math.clamp(Integer.getInteger("kasuga.transparencyLayers", 32), 1, 256);
            int queryBatch = Math.clamp(Integer.getInteger("kasuga.transparencyQueryBatch", 4), 1, 16);
            long peelingStarted = System.nanoTime();
            active = this;
            phase = FOOTPRINT;
            for (var model : models) model.draw(context, 5);
            if (capture != null) {
                capture.target("footprint", footprint);
            }
            phase = PEEL;
            var result = peelLoop.render(limit, queryBatch, new PeelLayerLoop.Layers() {
                @Override public void clear() { current.clear(1.0); }
                @Override public void draw(int layer) {
                    terrain.run();
                    if (capture != null && layer < 4) capture.target("layer" + layer + "-terrain", current);
                    for (var model : models) model.draw(context, 4);
                    if (capture != null && layer < 4) capture.target("layer" + layer + "-mixed", current);
                }
                @Override public void pinNearestDepth() { nearest.copyDepth(current.framebuffer); }
                @Override public void accumulate() {
                    accumulation.bind();
                    fullscreen(current.color, GL11.GL_ONE_MINUS_DST_ALPHA, GL11.GL_ONE, false);
                }
                @Override public void swap() {
                    PeelTarget swap = previous;
                    previous = current;
                    current = swap;
                }
            });
            int layers = result.submittedLayers();
            observedPassBound = result.observedPassBound();
            if (result.ringBusy()) ringBusyFrames++;
            if (result.observedOverflowLimit() > 0 && !overflowLogged) {
                overflowLogged = true;
                LOGGER.warn("Unified transparency reached {} layers; deeper layers may be omitted. "
                        + "Increase kasuga.transparencyLayers for this scene (maximum 256).", result.observedOverflowLimit());
            }
            long peelingNanos = System.nanoTime() - peelingStarted;
            if (capture != null) capture.target("accumulation", accumulation);
            PeelTarget.checkError("unified depth peeling");
            main.bindWrite(true);
            resolving = true;
            // Pixels without any surviving model alpha keep the native terrain
            // pass. Only the disjoint footprint is replaced by the peel resolve.
            phase = OUTSIDE;
            terrain.run();
            active = null;
            main.bindWrite(true);
            fullscreen(accumulation.color, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, true);
            if (capture != null) {
                capture.target("nearest", nearest);
                capture.capture("main-after", main.frameBufferId, main.width, main.height, true, true);
                capture.finish();
            }
            handled = true;
            if (!logged) {
                logged = true;
                LOGGER.info("Kasuga unified terrain/model depth peeling active (pixel footprint + prepared models + 3-slot RingBuffer): {} queued passes, limit {}, query batch {}",
                        layers, limit, queryBatch);
            }
            recordProfile(started, peelingNanos, 0, layers, result.issuedQueries());
            return true;
        } catch (RuntimeException failure) {
            disabled = true;
            LOGGER.warn("Disabling unified transparency; using existing terrain/model passes", failure);
            handled = resolving;
            return resolving;
        } finally {
            active = null;
            for (int i = 0; i < units.length; i++) bindTexture(units[i], textures[i]);
            activeTexture(oldUnit);
            GL20.glUseProgram(oldProgram);
            GL30.glBindVertexArray(oldVao);
            RenderSystem.restoreGlState(backup);
            main.bindWrite(true);
        }
    }

    private static void bindTexture(int unit, int texture) {
        activeTexture(GL13.GL_TEXTURE0 + unit);
        RenderSystem.bindTexture(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    private static void activeTexture(int unit) {
        RenderSystem.activeTexture(unit);
        GL13.glActiveTexture(unit);
    }

    private void recordProfile(long started, long peeling, long waiting, int layers, int queries) {
        if (!Boolean.getBoolean("kasuga.profileTransparency")) return;
        long now = System.nanoTime();
        if (profileStart == 0) profileStart = started;
        profileElapsed += now - started;
        profilePeeling += peeling;
        profileWaiting += waiting;
        profileLayers += layers;
        profileQueries += queries;
        profileFrames++;
        if (now - profileStart < 5_000_000_000L) return;
        double ms = 1e-6 / profileFrames;
        LOGGER.info("Transparency profile: frames={}, avgCPU={}ms, peel={}ms, queryWait={}ms, queuedPasses={}, queries={}, observedPassBound={}, ringBusyFrames={}",
                profileFrames, Math.round(profileElapsed * ms * 100) / 100.0,
                Math.round(profilePeeling * ms * 100) / 100.0,
                Math.round(profileWaiting * ms * 100) / 100.0,
                profileLayers / (double) profileFrames, profileQueries / (double) profileFrames,
                observedPassBound, ringBusyFrames);
        profileFrames = profileElapsed = profilePeeling = profileWaiting = 0;
        profileLayers = profileQueries = 0;
        ringBusyFrames = 0;
        profileStart = now;
    }

    private void fullscreen(int texture, int source, int destination, boolean writeDepth) {
        if (writeDepth) {
            RenderSystem.enableDepthTest();
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
        } else {
            RenderSystem.disableDepthTest();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        RenderSystem.depthMask(writeDepth);
        GL11.glDepthMask(writeDepth);
        // This triangle is front-facing. Preserve culling for subsequent
        // geometry: vanilla's CULL shard assumes the enabled default and does
        // not re-enable it after a fullscreen pass disables it.
        RenderSystem.enableBlend();
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        com.mojang.blaze3d.platform.GlStateManager._blendFuncSeparate(source, destination, source, destination);
        GL14.glBlendFuncSeparate(source, destination, source, destination);
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);
        bindTexture(0, texture);
        GL20.glUniform1i(layerSampler, 0);
        GL20.glUniform1i(writeDepthUniform, writeDepth ? 1 : 0);
        GL20.glUniform1i(nearestSampler, PREVIOUS_UNIT);
        if (writeDepth) bindTexture(PREVIOUS_UNIT, nearest.depth);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private void ensureProgram() {
        if (program != 0) return;
        try (var resource = LayeredTransparency.class.getResourceAsStream(
                "/assets/kasuga_lib/shaders/core/ksglib_peel_resolve.fsh")) {
            if (resource == null) throw new java.io.IOException("Missing peel resolve shader");
            program = GlslProgram.link(GlslProgram.FULLSCREEN_VERTEX,
                    new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot load peel resolve shader", failure);
        }
        vao = GL30.glGenVertexArrays();
        layerSampler = GL20.glGetUniformLocation(program, "Layer");
        nearestSampler = GL20.glGetUniformLocation(program, "NearestDepth");
        writeDepthUniform = GL20.glGetUniformLocation(program, "WriteDepth");
    }

    @Override
    public void close() {
        if (armed == this) armed = null;
        scene.close(); previous.close(); current.close(); accumulation.close(); footprint.close(); nearest.close();
        if (program != 0) GL20.glDeleteProgram(program);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        peelLoop.close();
        program = vao = 0;
    }
}
