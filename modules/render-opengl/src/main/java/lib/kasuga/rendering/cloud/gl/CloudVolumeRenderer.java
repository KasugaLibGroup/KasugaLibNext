package lib.kasuga.rendering.cloud.gl;

import lib.kasuga.rendering.cloud.*;
import lib.kasuga.rendering.models.uml.backend.gpu.GlslProgram;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** OpenGL 3.2 renderer with baked shape data, cached lighting and depth-aware upsampling. */
public final class CloudVolumeRenderer implements AutoCloseable {
    public static final String RESOURCE_ROOT = "/assets/kasuga_lib/shaders/cloud/";
    public static final String FRAGMENT_RESOURCE = RESOURCE_ROOT + "cumulonimbus.fsh";
    private static final CompletableFuture<CloudNoise> NOISE = CompletableFuture.supplyAsync(() -> CloudNoise.generate(64));
    private static final CompletableFuture<float[]> SHAPE = CompletableFuture.supplyAsync(() -> CloudShapes.bake(64));
    private static final CompletableFuture<CloudNoise> READY = NOISE.thenCombine(SHAPE, (noise, shape) -> noise);
    private final LinkedHashMap<ViewKey, ViewTarget> views = new LinkedHashMap<>(8, .75f, true);
    private final LinkedHashMap<LightKey, LightTarget> lights = new LinkedHashMap<>(8, .75f, true);
    private final LinkedHashMap<SkyCloudField.Key, WeatherTarget> weather = new LinkedHashMap<>(8, .75f, true);
    private Program march, illuminate, upsample, sky;
    private int vao, noise, shape;
    private boolean closed, inPass, adaptiveMarching = true;

    public static CompletableFuture<CloudNoise> prepareNoise() { return READY.copy(); }
    public boolean isReady() { return READY.isDone(); }
    public int cachedWeatherCount() { return weather.size(); }
    public int cachedViewCount() { return views.size(); }
    public void adaptiveMarching(boolean value) {
        if (closed || inPass) throw new IllegalStateException("Change cloud sampling outside an open pass");
        adaptiveMarching = value;
    }

    /** The current draw framebuffer supplies scene depth and receives the cloud composite. */
    public Pass begin(Matrix4fc view, Matrix4fc projection, Vector3dc camera,
                      Vector3fc sunDirection, Vector3fc sunColor, Vector3fc ambientColor) {
        if (closed || inPass) throw new IllegalStateException("Cloud renderer is closed or already in a pass");
        if (!isReady()) throw new IllegalStateException("Cloud noise is still preparing");
        Objects.requireNonNull(camera); Objects.requireNonNull(sunColor); Objects.requireNonNull(ambientColor);
        var sun = new Vector3f(Objects.requireNonNull(sunDirection));
        if (!sun.isFinite() || sun.lengthSquared() < 1e-8f || !sunColor.isFinite() || !ambientColor.isFinite()
                || !Double.isFinite(camera.x()) || !Double.isFinite(camera.y()) || !Double.isFinite(camera.z()))
            throw new IllegalArgumentException("Cloud camera and lighting must be finite, with a nonzero sun direction");
        if (sunColor.x() < 0 || sunColor.y() < 0 || sunColor.z() < 0 || ambientColor.x() < 0 || ambientColor.y() < 0 || ambientColor.z() < 0)
            throw new IllegalArgumentException("Cloud light intensities cannot be negative");
        var viewProjection = new Matrix4f(projection).mul(view);
        var inverse = new Matrix4f(viewProjection).invert();
        if (!inverse.isFinite()) throw new IllegalArgumentException("Cloud view/projection is not invertible");
        var state = new CloudGlState();
        try {
            initialize();
            ViewTarget target = captureDepth(state);
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST); GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GL11.glColorMask(true, true, true, true);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL30.glBindVertexArray(vao);
            if (GL.getCapabilities().OpenGL33) for (int unit = 0; unit < 6; unit++) GL33.glBindSampler(unit, 0);
            inPass = true;
            return new Pass(state, target, new Vector3d(camera), sun.normalize(), new Vector3f(sunColor),
                    new Vector3f(ambientColor), viewProjection, inverse);
        } catch (RuntimeException | Error failure) { state.close(); throw failure; }
    }

    private void initialize() {
        if (march != null) return;
        try {
            String density = resource("density.glsl");
            march = new Program(resource("cumulonimbus.fsh").replace("// CLOUD_DENSITY", density));
            illuminate = new Program(resource("lighting.fsh").replace("// CLOUD_DENSITY", density));
            upsample = new Program(resource("upsample.fsh"));
            sky = new Program(resource("sky.fsh"));
            vao = GL30.glGenVertexArrays();
            noise = texture3d(1, true);
            var data = READY.join();
            var bytes = MemoryUtil.memAlloc(data.size() * data.size() * data.size() * 4);
            try {
                bytes.put(data.pixels()).flip(); CloudGlState.tightUnpack();
                GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, data.size(), data.size(), data.size(), 0,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
                GL30.glGenerateMipmap(GL12.GL_TEXTURE_3D);
            } finally { MemoryUtil.memFree(bytes); }
            shape = texture3d(2, false); CloudGlState.tightUnpack();
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RGBA16F, 64, 64, 64, 0, GL11.GL_RGBA, GL11.GL_FLOAT, SHAPE.join());
        } catch (RuntimeException | Error failure) { release(); throw failure; }
    }

    private static String resource(String name) {
        try (var stream = CloudVolumeRenderer.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (stream == null) throw new IllegalStateException("Missing cloud shader " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Cannot read cloud shader " + name, failure); }
    }
    private static int texture3d(int unit, boolean repeat) {
        int texture = GL11.glGenTextures(); bind3d(unit, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, repeat ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        for (int axis : new int[]{GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T, GL12.GL_TEXTURE_WRAP_R})
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, axis, repeat ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE);
        return texture;
    }
    private static int texture2d(int unit, int internal, int format, int type, int width, int height, boolean linear) {
        int texture = GL11.glGenTextures(); bind2d(unit, texture); CloudGlState.tightUnpack();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, width, height, 0, format, type, 0L);
        return texture;
    }
    private static int framebuffer(int texture, boolean depth) {
        int fbo = GL30.glGenFramebuffers(); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, depth ? GL30.GL_DEPTH_ATTACHMENT : GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glReadBuffer(depth ? GL11.GL_NONE : GL30.GL_COLOR_ATTACHMENT0);
        GL11.glDrawBuffer(depth ? GL11.GL_NONE : GL30.GL_COLOR_ATTACHMENT0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(fbo); throw new IllegalStateException("Cloud framebuffer incomplete");
        }
        return fbo;
    }

    private ViewTarget captureDepth(CloudGlState state) {
        int w = state.viewport[2], h = state.viewport[3];
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("Empty cloud viewport");
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, state.draw);
        int attachment = state.draw == 0 ? GL11.GL_DEPTH : GL30.GL_DEPTH_ATTACHMENT;
        int bits = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
        int stencil = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);
        int internal;
        if (stencil != 0) internal = type == GL11.GL_FLOAT ? GL30.GL_DEPTH32F_STENCIL8 : GL30.GL_DEPTH24_STENCIL8;
        else if (type == GL11.GL_FLOAT && bits == 32) internal = GL30.GL_DEPTH_COMPONENT32F;
        else internal = switch (bits) { case 16 -> GL14.GL_DEPTH_COMPONENT16; case 24 -> GL14.GL_DEPTH_COMPONENT24; case 32 -> GL14.GL_DEPTH_COMPONENT32;
            default -> throw new IllegalStateException("World framebuffer has no supported depth attachment: " + bits); };
        var key = new ViewKey(w, h, internal);
        var target = views.get(key);
        if (target == null) {
            target = new ViewTarget(key);
            try {
                target.depth = texture2d(0, internal, stencil != 0 ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT,
                        stencil != 0 ? (type == GL11.GL_FLOAT ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL30.GL_UNSIGNED_INT_24_8) : GL11.GL_FLOAT, w, h, false);
                target.fbo = framebuffer(target.depth, true);
                views.put(key, target); evict(views);
            } catch (RuntimeException | Error failure) { target.close(); throw failure; }
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, state.draw); GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.fbo);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL30.glBlitFramebuffer(state.viewport[0], state.viewport[1], state.viewport[0] + w, state.viewport[1] + h,
                0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        return target;
    }
    private static <K, V extends Target> void evict(LinkedHashMap<K, V> cache) {
        if (cache.size() <= 8) return;
        var iterator = cache.entrySet().iterator(); var old = iterator.next(); iterator.remove(); old.getValue().close();
    }
    private static void bind2d(int unit, int texture) { GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture); }
    private static void bind3d(int unit, int texture) { GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit); GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture); }
    private static void triangle() { GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3); }

    private void release() {
        views.values().forEach(ViewTarget::close); views.clear(); lights.values().forEach(LightTarget::close); lights.clear();
        weather.values().forEach(WeatherTarget::close); weather.clear();
        if (sky != null) sky.close(); sky = null;
        if (noise != 0) GL11.glDeleteTextures(noise); if (shape != 0) GL11.glDeleteTextures(shape);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (march != null) march.close(); if (illuminate != null) illuminate.close(); if (upsample != null) upsample.close();
        noise = shape = vao = 0; march = illuminate = upsample = null;
    }
    @Override public void close() {
        if (closed) return;
        if (inPass) throw new IllegalStateException("Close the cloud pass before its renderer");
        closed = true; release();
    }

    public final class Pass implements AutoCloseable {
        private final CloudGlState state;
        private final ViewTarget target;
        private final Vector3d camera;
        private final Vector3f sun, sunColor, ambient;
        private final Matrix4f viewProjection, inverse;
        private final Vector3f atmosphere = new Vector3f(.22f, .42f, .67f);
        private boolean finished;
        private Pass(CloudGlState state, ViewTarget target, Vector3d camera, Vector3f sun, Vector3f sunColor,
                     Vector3f ambient, Matrix4f viewProjection, Matrix4f inverse) {
            this.state = state; this.target = target; this.camera = camera; this.sun = sun;
            this.sunColor = sunColor; this.ambient = ambient; this.viewProjection = viewProjection; this.inverse = inverse;
        }
        public void atmosphereColor(Vector3fc value) {
            if (finished) throw new IllegalStateException("Cloud pass is closed");
            if (value == null || !value.isFinite() || value.x() < 0 || value.y() < 0 || value.z() < 0)
                throw new IllegalArgumentException("Cloud atmosphere color must be finite and nonnegative");
            atmosphere.set(value);
        }
        public void draw(CumulonimbusVolume.Frame frame) {
            if (finished) throw new IllegalStateException("Cloud pass is closed");
            Objects.requireNonNull(frame);
            if (frame.settings().density() == 0 || frame.settings().coverage() == 0) return;
            var transform = frame.pose().worldToLocal(camera.x, camera.y, camera.z);
            if (!transform.isFinite()) throw new IllegalArgumentException("Cloud transform is outside GPU coordinate range");
            Rect bounds = projectedBounds(frame.pose());
            if (bounds == null) return;
            var lighting = lighting(frame, transform);
            int divisor = frame.settings().quality().resolutionDivisor;
            ColorTarget color = divisor == 1 ? null : target.color(divisor);
            int w = color == null ? target.key.width : color.width, h = color == null ? target.key.height : color.height;
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, color == null ? state.draw : color.fbo);
            GL11.glViewport(color == null ? state.viewport[0] : 0, color == null ? state.viewport[1] : 0, w, h);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (color != null) {
                GL11.glDisable(GL11.GL_BLEND);
                GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[4]);
            } else GL11.glEnable(GL11.GL_BLEND);
            scissor(bounds, w, h, color == null ? state.viewport[0] : 0, color == null ? state.viewport[1] : 0);
            march.use(); densityUniforms(march, frame, frame.timeSeconds());
            bind2d(0, target.depth); bind2d(3, lighting.texture);
            march.integer("SceneDepth", 0); march.integer("LightVolume", 3); march.integer("LightGridSize", lighting.grid);
            march.matrix("InverseViewProjection", inverse); march.matrix("WorldToLocal", transform);
            march.vector("SunDirection", sun); march.vector("SunColor", sunColor); march.vector("AmbientColor", ambient);
            march.viewport(color == null ? state.viewport[0] : 0, color == null ? state.viewport[1] : 0, w, h);
            march.pair("DepthFootprint", divisor == 1 ? 0 : .49f / w, divisor == 1 ? 0 : .49f / h);
            march.integer("ViewSteps", frame.settings().quality().viewSteps); march.integer("AdaptiveMarching", adaptiveMarching ? 1 : 0);
            triangle();
            if (color != null) {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, state.draw);
                GL11.glViewport(state.viewport[0], state.viewport[1], target.key.width, target.key.height);
                GL11.glEnable(GL11.GL_BLEND);
                scissor(bounds, target.key.width, target.key.height, state.viewport[0], state.viewport[1]);
                upsample.use(); bind2d(0, target.depth); bind2d(1, color.texture);
                upsample.integer("SceneDepth", 0); upsample.integer("CloudColor", 1); upsample.matrix("InverseViewProjection", inverse);
                upsample.viewport(state.viewport[0], state.viewport[1], target.key.width, target.key.height);
                upsample.pair("CloudSize", w, h); upsample.pair("DepthFootprint", .49f / w, .49f / h); triangle();
            }
        }

        /** World-anchored density layers share one bounded-cost march and one composite. */
        public void drawSky(SkyCloudField.Frame frame) {
            if (finished) throw new IllegalStateException("Cloud pass is closed");
            Objects.requireNonNull(frame);
            if (frame.coverage() == 0) return;
            var settings = frame.settings();
            var key = frame.window().key();
            var weatherTarget = weather.get(key);
            if (weatherTarget == null) {
                weatherTarget = new WeatherTarget();
                try { weatherTarget.allocate(frame.window()); weather.put(key, weatherTarget); evict(weather); }
                catch (RuntimeException | Error failure) { weatherTarget.close(); throw failure; }
            }
            int divisor = settings.quality().resolutionDivisor;
            var color = target.color(divisor);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, color.fbo);
            GL11.glViewport(0, 0, color.width, color.height);
            GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL11.GL_BLEND);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[4]);
            sky.use(); bind2d(0, target.depth); bind3d(1, noise); bind3d(2, shape);
            bind2d(3, weatherTarget.layout); bind2d(4, weatherTarget.dimensions); bind2d(5, weatherTarget.layers);
            sky.integer("SceneDepth", 0); sky.integer("NoiseVolume", 1); sky.integer("ShapeVolume", 2);
            sky.integer("WeatherLayout", 3); sky.integer("WeatherDimensions", 4); sky.integer("WeatherLayers", 5);
            sky.matrix("InverseViewProjection", inverse); sky.viewport(0, 0, color.width, color.height);
            sky.pair("DepthFootprint", .49f / color.width, .49f / color.height);
            sky.vector("GridOrigin", new Vector3f(frame.originX(), frame.baseY(), frame.originZ()));
            sky.vector("AtmosphereColor", atmosphere);
            sky.vector("SunDirection", sun); sky.vector("SunColor", sunColor); sky.vector("AmbientColor", ambient);
            sky.scalar("CellSize", settings.cellSize()); sky.scalar("MaxHeight", settings.maxHeight());
            sky.scalar("Coverage", frame.coverage()); sky.scalar("MaxDistance", settings.distance());
            sky.vector("NoiseOffset", new Vector3f(phase(frame.timeSeconds() * .004), phase(-frame.timeSeconds() * .0014), 0));
            sky.integer("GridSize", SkyCloudField.GRID_SIZE);
            sky.integer("LayerCount", settings.layers().size());
            int stormLayer = -1;
            double worldX = key.x() * (double) settings.cellSize() - frame.originX();
            double worldZ = key.z() * (double) settings.cellSize() - frame.originZ();
            for (int index = 0; index < settings.layers().size(); index++) {
                var layer = settings.layers().get(index);
                if (stormLayer < 0 && layer.type() == SkyCloudLayer.Type.CUMULUS) stormLayer = index;
                sky.quad("Layers[" + index + "]", frame.baseY() + layer.baseHeight() - settings.baseHeight(),
                        layer.thickness(), layer.detailScale(), layer.type().ordinal());
                sky.scalar("LayerBias[" + index + "]", layer.coverageBias());
                sky.scalar("LayerDensity[" + index + "]", layer.density());
                boolean cirrus = layer.type() == SkyCloudLayer.Type.CIRRUS;
                sky.vector("LayerNoiseOrigin[" + index + "]", new Vector3f(
                        phase((cirrus ? worldX * .25 + worldZ * .5 : worldX) / layer.detailScale() + index * .173 + frame.timeSeconds() * .001),
                        phase(frame.timeSeconds() * -.0007 + index * .31),
                        phase(worldZ * (cirrus ? 2.7 : 1) / layer.detailScale() + index * .419)));
            }
            sky.integer("StormLayer", stormLayer); sky.integer("LightSteps", settings.quality().lightSteps);
            sky.integer("SampleBudget", settings.quality().sampleBudget * (adaptiveMarching ? 1 : 2)); triangle();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, state.draw);
            GL11.glViewport(state.viewport[0], state.viewport[1], target.key.width, target.key.height);
            GL11.glEnable(GL11.GL_BLEND);
            upsample.use(); bind2d(0, target.depth); bind2d(1, color.texture);
            upsample.integer("SceneDepth", 0); upsample.integer("CloudColor", 1); upsample.matrix("InverseViewProjection", inverse);
            upsample.viewport(state.viewport[0], state.viewport[1], target.key.width, target.key.height);
            upsample.pair("CloudSize", color.width, color.height);
            upsample.pair("DepthFootprint", .49f / color.width, .49f / color.height); triangle();
        }

        private void densityUniforms(Program shader, CumulonimbusVolume.Frame frame, double time) {
            bind3d(1, noise); bind3d(2, shape);
            shader.integer("NoiseVolume", 1); shader.integer("ShapeVolume", 2);
            var settings = frame.settings(); int seed = settings.seed() * 0x45d9f3b; seed ^= seed >>> 16;
            var pose = frame.pose();
            float noiseSize = Math.max(pose.width(), Math.max(pose.height(), pose.depth())) * .5f;
            float warpSize = Math.min(pose.width(), Math.min(pose.height(), pose.depth())) * .12f;
            // Isotropic world-space billows: tall clouds must not stretch the noise vertically.
            shader.vector("NoiseScale", new Vector3f(pose.width() * .5f, pose.height(), pose.depth() * .5f).div(noiseSize));
            shader.vector("WarpScale", new Vector3f(warpSize / (pose.width() * .5f), warpSize / pose.height(), warpSize / (pose.depth() * .5f)));
            shader.vector("NoiseOffset", new Vector3f(phase((seed & 255) / 256.0 + time * .006),
                    phase(((seed >>> 8) & 255) / 256.0 - time * .0021), phase(((seed >>> 16) & 255) / 256.0)));
            GL20.glUniform4f(shader.uniform("Optical"), settings.density(), settings.extinction(), settings.erosion(), settings.anvil());
            GL20.glUniform1f(shader.uniform("Coverage"), settings.coverage());
        }
        private LightTarget lighting(CumulonimbusVolume.Frame frame, Matrix4f transform) {
            var key = new LightKey(frame.settings().seed(), frame.settings().quality());
            var light = lights.get(key);
            if (light == null) {
                light = new LightTarget(frame.settings().quality().lightGridSize);
                try { light.allocate(); lights.put(key, light); evict(lights); }
                catch (RuntimeException | Error failure) { light.close(); throw failure; }
            }
            long bucket = (long) Math.floor(frame.timeSeconds() * 4);
            CloudPose pose = frame.pose();
            if (light.settings != null && light.settings.equals(frame.settings()) && light.bucket == bucket
                    && light.poseWidth == pose.width() && light.poseHeight == pose.height() && light.poseDepth == pose.depth()
                    && light.yaw == pose.yaw() && light.sun.distanceSquared(sun) < .0001f) return light;
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, light.fbo);
            GL11.glViewport(0, 0, light.width, light.height);
            GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_SCISSOR_TEST);
            illuminate.use(); densityUniforms(illuminate, frame, bucket / 4.0);
            illuminate.vector("LocalSunDirection", transform.transformDirection(new Vector3f(sun)));
            var tangent = sun.cross(Math.abs(sun.y) > .95f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0), new Vector3f()).normalize();
            illuminate.vector("LocalSunTangent", transform.transformDirection(new Vector3f(tangent)));
            illuminate.vector("LocalSunBitangent", transform.transformDirection(sun.cross(tangent, new Vector3f()).normalize()));
            illuminate.integer("LightGridSize", light.grid); illuminate.integer("LightSteps", frame.settings().quality().lightSteps); triangle();
            light.settings = frame.settings(); light.bucket = bucket; light.sun.set(sun);
            light.poseWidth = pose.width(); light.poseHeight = pose.height(); light.poseDepth = pose.depth(); light.yaw = pose.yaw();
            return light;
        }
        private Rect projectedBounds(CloudPose pose) {
            var localToClip = new Matrix4f(viewProjection).mul(pose.worldToLocal(camera.x, camera.y, camera.z).invert());
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY; int behind = 0;
            for (int x = -1; x <= 1; x += 2) for (int y = 0; y <= 1; y++) for (int z = -1; z <= 1; z += 2) {
                var p = localToClip.transform(new Vector4f(x, y, z, 1));
                if (p.w <= .00001f) { behind++; continue; }
                float px = p.x / p.w, py = p.y / p.w;
                minX = Math.min(minX, px); maxX = Math.max(maxX, px); minY = Math.min(minY, py); maxY = Math.max(maxY, py);
            }
            if (behind == 8) return null;
            if (behind != 0) return new Rect(0, 0, 1, 1);
            if (maxX < -1 || minX > 1 || maxY < -1 || minY > 1) return null;
            return new Rect(Math.max(0, minX * .5f + .5f), Math.max(0, minY * .5f + .5f),
                    Math.min(1, maxX * .5f + .5f), Math.min(1, maxY * .5f + .5f));
        }
        private void scissor(Rect bounds, int w, int h, int x, int y) {
            int left = Math.max(0, (int) Math.floor(bounds.minX * w) - 2), bottom = Math.max(0, (int) Math.floor(bounds.minY * h) - 2);
            int right = Math.min(w, (int) Math.ceil(bounds.maxX * w) + 2), top = Math.min(h, (int) Math.ceil(bounds.maxY * h) + 2);
            GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(x + left, y + bottom, right - left, top - bottom);
        }
        private float phase(double value) { return (float) (value - Math.floor(value)); }
        @Override public void close() { if (!finished) { finished = true; inPass = false; state.close(); } }
    }

    private record ViewKey(int width, int height, int format) {}
    private record LightKey(int seed, CloudSettings.Quality quality) {}
    private record Rect(float minX, float minY, float maxX, float maxY) {}
    private interface Target extends AutoCloseable { @Override void close(); }
    private static final class ViewTarget implements Target {
        final ViewKey key; int depth, fbo;
        final Map<Integer, ColorTarget> colors = new HashMap<>();
        ViewTarget(ViewKey key) { this.key = key; }
        ColorTarget color(int divisor) {
            var result = colors.get(divisor);
            if (result != null) return result;
            result = new ColorTarget((key.width + divisor - 1) / divisor, (key.height + divisor - 1) / divisor);
            try { result.allocate(); colors.put(divisor, result); return result; }
            catch (RuntimeException | Error failure) { result.close(); throw failure; }
        }
        @Override public void close() {
            colors.values().forEach(ColorTarget::close); colors.clear();
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo); if (depth != 0) GL11.glDeleteTextures(depth); fbo = depth = 0;
        }
    }
    private static class ColorTarget implements Target {
        final int width, height; int texture, fbo;
        ColorTarget(int width, int height) { this.width = width; this.height = height; }
        void allocate() {
            texture = texture2d(1, GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT, width, height, false); fbo = framebuffer(texture, false);
        }
        @Override public void close() {
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo); if (texture != 0) GL11.glDeleteTextures(texture); fbo = texture = 0;
        }
    }
    private static final class LightTarget extends ColorTarget {
        final int grid;
        CloudSettings settings; long bucket; float poseWidth, poseHeight, poseDepth, yaw;
        final Vector3f sun = new Vector3f();
        LightTarget(int grid) { super(grid * 8, grid * ((grid + 7) / 8)); this.grid = grid; }
        @Override void allocate() {
            texture = texture2d(3, GL30.GL_R16F, GL11.GL_RED, GL11.GL_FLOAT, width, height, true); fbo = framebuffer(texture, false);
        }
    }
    private static final class WeatherTarget implements Target {
        int layout, dimensions, layers;
        void allocate(SkyCloudField.Window window) {
            layout = upload(3, window.layout()); dimensions = upload(4, window.dimensions());
            layers = texture2d(5, GL30.GL_RGBA32F, GL11.GL_RGBA, GL11.GL_FLOAT,
                    SkyCloudField.GRID_SIZE, SkyCloudField.GRID_SIZE * SkyCloudField.MAX_LAYERS, true);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SkyCloudField.GRID_SIZE,
                    SkyCloudField.GRID_SIZE * SkyCloudField.MAX_LAYERS, GL11.GL_RGBA, GL11.GL_FLOAT, window.weatherLayers());
        }
        private static int upload(int unit, float[] data) {
            int texture = texture2d(unit, GL30.GL_RGBA32F, GL11.GL_RGBA, GL11.GL_FLOAT,
                    SkyCloudField.GRID_SIZE, SkyCloudField.GRID_SIZE, false);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SkyCloudField.GRID_SIZE, SkyCloudField.GRID_SIZE,
                    GL11.GL_RGBA, GL11.GL_FLOAT, data);
            return texture;
        }
        @Override public void close() {
            if (layout != 0) GL11.glDeleteTextures(layout); if (dimensions != 0) GL11.glDeleteTextures(dimensions);
            if (layers != 0) GL11.glDeleteTextures(layers);
            layout = dimensions = layers = 0;
        }
    }
    private static final class Program implements AutoCloseable {
        final int id; final Map<String, Integer> uniforms = new HashMap<>();
        Program(String fragment) { id = GlslProgram.link(GlslProgram.FULLSCREEN_VERTEX, fragment); }
        int uniform(String name) { return uniforms.computeIfAbsent(name, key -> GL20.glGetUniformLocation(id, key)); }
        void use() { GL20.glUseProgram(id); }
        void integer(String name, int value) { GL20.glUniform1i(uniform(name), value); }
        void quad(String name, float x, float y, float z, float w) { GL20.glUniform4f(uniform(name), x, y, z, w); }
        void scalar(String name, float value) { GL20.glUniform1f(uniform(name), value); }
        void pair(String name, float x, float y) { GL20.glUniform2f(uniform(name), x, y); }
        void vector(String name, Vector3fc value) { GL20.glUniform3f(uniform(name), value.x(), value.y(), value.z()); }
        void viewport(int x, int y, int width, int height) { GL20.glUniform4f(uniform("Viewport"), x, y, width, height); }
        void matrix(String name, Matrix4fc value) {
            try (var stack = MemoryStack.stackPush()) { GL20.glUniformMatrix4fv(uniform(name), false, value.get(stack.mallocFloat(16))); }
        }
        @Override public void close() { GL20.glDeleteProgram(id); }
    }
}
