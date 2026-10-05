package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.cloud.*;
import lib.kasuga.rendering.cloud.gl.CloudVolumeRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Path;
import java.util.*;

/** The complete production sky field, not a collection of individually rendered test volumes. */
final class SkyCloudRegression {
    private static final int W = 768, H = 384;
    private static final Vector3f SUN = new Vector3f(-.5f, .8f, .3f).normalize();
    private static final Vector3f DIRECT = new Vector3f(.9f, .84f, .74f), AMBIENT = new Vector3f(.30f, .32f, .35f);
    private SkyCloudRegression() {}

    static void run(Path output) throws Exception {
        CloudVolumeRenderer.prepareNoise().join();
        GlFixture.resource(CloudVolumeRenderer.RESOURCE_ROOT + "sky.fsh");
        try (var fixture = new GlFixture(); var renderer = new CloudVolumeRenderer()) {
            int target = target(fixture, W, H);
            var camera = new Vector3d(0, 64, 0);
            var field = new SkyCloudField(SkyCloudSettings.DEFAULT);
            var frame = field.sample(0, 64, 0, 1, 0);
            require(frame.weatherCellCount() > 200, "Sky weather window is not populated");
            var empty = new SkyCloudField(SkyCloudSettings.DEFAULT.withCoverage(0)).sample(0, 64, 0, 1, 0);
            float[] baseline = render(renderer, target, W, H, camera, empty, 0, .20f, false);
            float[] first = null; double greatestCardinalDifference = 0;
            for (int cardinal = 0; cardinal < 4; cardinal++) {
                float[] sky = render(renderer, target, W, H, camera, frame, cardinal * (float) Math.PI / 2, .20f, false);
                finite(sky);
                double difference = difference(sky, baseline, W, H, 0, W);
                greatestCardinalDifference = Math.max(greatestCardinalDifference, difference);
                System.out.println("Sky cardinal " + cardinal + " MAD=" + difference);
                GlFixture.image(output.resolve("sky-" + cardinal + ".png"), W, H, sky);
                if (cardinal == 0) first = sky;
            }
            require(greatestCardinalDifference > .003, "Default weather produced an entirely empty sky");
            require(difference(first, render(renderer, target, W, H, camera, frame, 0, .20f, false), W, H, 0, W) < 1e-7,
                    "Sampling multiple cameras advanced sky weather");
            renderer.adaptiveMarching(false);
            float[] reference = render(renderer, target, W, H, camera, frame, 0, .20f, false);
            renderer.adaptiveMarching(true);
            double samplingError = difference(first, reference, W, H, 0, W);
            require(samplingError < .015, "Layer integration lost cloud shape: " + samplingError);
            System.out.println("Sky budget/double-budget MAD=" + samplingError);
            float[] occluded = render(renderer, target, W, H, camera, frame, 0, .20f, true);
            require(difference(occluded, baseline, W, H, 0, W / 2 + 1) < 1e-6, "Sky leaked across an unaligned opaque silhouette");
            require(difference(occluded, baseline, W, H, W / 2 + 1, W) > .0005, "Foreground removed the entire sky");
            GlFixture.image(output.resolve("sky-depth.png"), W, H, occluded);
            double sparsePixels = 0, densePixels = 0;
            for (var preset : new SkyCloudSettings[]{SkyCloudSettings.SCATTERED, SkyCloudSettings.DEFAULT, SkyCloudSettings.OVERCAST}) {
                var sample = new SkyCloudField(preset).sample(0, 64, 0, 1, 0);
                float[] picture = render(renderer, target, W, H, camera, sample, 0, .55f, false);
                finite(picture);
                String name = preset == SkyCloudSettings.SCATTERED ? "sparse" : preset == SkyCloudSettings.OVERCAST ? "dense" : "layered";
                double pixels = changedPixels(picture, baseline);
                if (name.equals("sparse")) sparsePixels = pixels;
                if (name.equals("dense")) densePixels = pixels;
                GlFixture.image(output.resolve("sky-" + name + ".png"), W, H, picture);
                System.out.println("Sky " + name + " changed pixels=" + pixels);
            }
            require(sparsePixels > .01 && sparsePixels < .65, "Scattered preset is empty or an opaque roof: " + sparsePixels);
            require(densePixels > sparsePixels + .2 && densePixels > .65, "Dense preset did not form broad banks/overcast: " + densePixels);
            var muted = SkyCloudSettings.OVERCAST.withLayers(List.of(SkyCloudSettings.DEFAULT.layers().getFirst().withDensity(0)));
            require(difference(baseline, render(renderer, target, W, H, camera,
                    new SkyCloudField(muted).sample(0, 64, 0, 1, 0), 0, .20f, false), W, H, 0, W) < 1e-7,
                    "Muted low layer still draws clouds or embedded storms");
            for (var layer : SkyCloudSettings.DEFAULT.layers()) {
                var isolated = SkyCloudSettings.DEFAULT.withLayers(List.of(layer)).withCoverage(.85f);
                float[] picture = render(renderer, target, W, H, camera, new SkyCloudField(isolated).sample(0, 64, 0, 1, 0), 0, .6f, false);
                finite(picture); require(changedPixels(picture, baseline) > .01, "Missing layer " + layer.type());
                GlFixture.image(output.resolve("sky-layer-" + layer.type().name().toLowerCase(Locale.ROOT) + ".png"), W, H, picture);
            }
            var shifted = field.sample(SkyCloudSettings.DEFAULT.cellSize(), 64, 0, 1, 0);
            var recentered = new SkyCloudField.Frame(shifted.settings(), shifted.window(), shifted.timeSeconds(),
                    shifted.originX() + SkyCloudSettings.DEFAULT.cellSize(), shifted.baseY(), shifted.originZ(), shifted.coverage());
            double seam = difference(first, render(renderer, target, W, H, camera, recentered, 0, .20f, false), W, H, 0, W);
            require(seam < .00001, "Camera window recenter changed overlapping clouds: " + seam);
            field.tick(10);
            require(difference(first, render(renderer, target, W, H, camera, field.sample(0, 64, 0, 1, 0), 0, .20f, false), W, H, 0, W) > .001,
                    "Wind and cloud development do not change the sky");
            var rainy = field.sample(0, 64, 0, 1, 1);
            require(rainy.weatherCellCount() > frame.weatherCellCount(), "Rain did not increase sky coverage");
            for (double[] position : new double[][]{{40000, 64, -40000}, {29_999_999.125, 64, -29_999_999.25}, {0, 1800, 0}, {0, 5500, 0}}) {
                var observer = new Vector3d(position);
                var sample = field.sample(observer.x, observer.y, observer.z, 1, 0);
                float elevation = observer.y > 5000 ? -.4f : .2f;
                float[] image = render(renderer, target, W, H, observer, sample, 0, elevation, false);
                finite(image); require(difference(image, baseline, W, H, 0, W) > .0005, "Cloud field disappeared after travelling or entering the layer");
                GlFixture.image(output.resolve("sky-at-" + (long) observer.x + "-" + (long) observer.y + ".png"), W, H, image);
            }
            // The added weather unit must restore both its texture and sampler, including the first upload.
            GL13.glActiveTexture(GL13.GL_TEXTURE5);
            int hostTexture = fixture.texture(GL11.GL_RGBA8, GL11.GL_RGBA, 8, 8, null);
            int sampler = GL.getCapabilities().OpenGL33 ? GL33.glGenSamplers() : 0;
            if (sampler != 0) GL33.glBindSampler(5, sampler);
            render(renderer, target, W, H, camera, frame, 0, .20f, false);
            require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE5
                    && GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == hostTexture, "Weather unit texture state not restored");
            if (sampler != 0) {
                require(GL11.glGetInteger(GL33.GL_SAMPLER_BINDING) == sampler, "Weather sampler not restored");
                GL33.glBindSampler(5, 0); GL33.glDeleteSamplers(sampler);
            }
            for (int i = 0; i < 12; i++) {
                var p = new Vector3d(i * 5000, 64, 0);
                render(renderer, target, W, H, p, field.sample(p.x, p.y, p.z, 1, 0), 0, .2f, false);
            }
            require(renderer.cachedWeatherCount() == 8, "Weather GPU cache grows with travel");
            System.out.println("Sky field: " + frame.weatherCellCount() + " weather cells/window, recenter MAD=" + seam);
            GlFixture.noError("complete sky field");
        }
    }
    private static int target(GlFixture fixture, int w, int h) {
        return fixture.framebuffer(fixture.texture(GL11.GL_RGBA8, GL11.GL_RGBA, w, h, null),
                fixture.texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, w, h, null));
    }
    private static Matrix4f view(float yaw, float elevation) {
        return new Matrix4f().lookAt(new Vector3f(), new Vector3f((float) Math.sin(yaw) * (float) Math.cos(elevation),
                (float) Math.sin(elevation), -(float) Math.cos(yaw) * (float) Math.cos(elevation)), new Vector3f(0, 1, 0));
    }
    private static void draw(CloudVolumeRenderer renderer, int target, int w, int h, Vector3d camera,
                             SkyCloudField.Frame frame, float yaw, float elevation, boolean occluded) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target); GL11.glViewport(0, 0, w, h);
        GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glColorMask(true, true, true, true); GL11.glDepthMask(true);
        GL11.glClearColor(.22f, .42f, .67f, 1); GL11.glClearDepth(1); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        if (occluded) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(0, 0, w / 2 + 1, h); GL11.glClearDepth(.01);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT); GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glClearDepth(1);
        }
        var projection = new Matrix4f().perspective((float) Math.toRadians(90), (float) w / h, .1f, 512);
        try (var pass = renderer.begin(view(yaw, elevation), projection, camera, SUN, DIRECT, AMBIENT)) { pass.drawSky(frame); }
    }
    private static float[] render(CloudVolumeRenderer renderer, int target, int w, int h, Vector3d camera,
                                  SkyCloudField.Frame frame, float yaw, float elevation, boolean occluded) {
        draw(renderer, target, w, h, camera, frame, yaw, elevation, occluded);
        return GlFixture.read(w, h, GL11.GL_RGBA, 4);
    }
    static Map<String, Object> benchmark(StandaloneRenderHarness.Options options) {
        if (!GL.getCapabilities().OpenGL33 && !GL.getCapabilities().GL_ARB_timer_query)
            throw new IllegalStateException("Sky GPU benchmark requires timer queries");
        var scenarios = new LinkedHashMap<String, Object>();
        try (var fixture = new GlFixture(); var renderer = new CloudVolumeRenderer()) {
            int target = target(fixture, options.width(), options.height()), query = GL15.glGenQueries();
            try {
                for (String name : new String[]{"ground", "sparse", "overcast", "inside", "rain", "travel"}) {
                    var settings = name.equals("sparse") ? SkyCloudSettings.SCATTERED
                            : name.equals("overcast") ? SkyCloudSettings.OVERCAST : SkyCloudSettings.DEFAULT;
                    var field = new SkyCloudField(settings);
                    var camera = new Vector3d(name.equals("travel") ? 40000 : 0, name.equals("inside") ? 1800 : 64, 0);
                    var frame = field.sample(camera.x, camera.y, camera.z, 1, name.equals("rain") ? 1 : 0);
                    for (int i = 0; i < options.warmup(); i++) {
                        field.tick(1.0 / 60);
                        frame = field.sample(camera.x, camera.y, camera.z, 1, name.equals("rain") ? 1 : 0);
                        draw(renderer, target, options.width(), options.height(), camera, frame, 0, .2f, false);
                    }
                    GL11.glFinish(); double[] times = new double[Math.max(1, options.frames())];
                    for (int i = 0; i < times.length; i++) {
                        field.tick(1.0 / 60); frame = field.sample(camera.x, camera.y, camera.z, 1, name.equals("rain") ? 1 : 0);
                        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
                        draw(renderer, target, options.width(), options.height(), camera, frame, 0, .2f, false);
                        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        times[i] = ARBTimerQuery.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT) / 1e6;
                    }
                    Arrays.sort(times);
                    scenarios.put(name, Map.of("gpuP50Ms", times[times.length / 2], "gpuP95Ms", times[Math.min(times.length - 1, (int) Math.ceil(times.length * .95) - 1)],
                            "frames", times.length, "weatherCellsInWindow", frame.weatherCellCount(), "layers", settings.layers().size()));
                    System.out.println("Sky GPU " + name + ": " + scenarios.get(name));
                }
            } finally { GL15.glDeleteQueries(query); }
        }
        return Map.of("type", "sky-field-gpu", "width", options.width(), "height", options.height(), "scenarios", scenarios,
                "measurement", "GL_TIME_ELAPSED including depth copy, whole field and composite; isolated context");
    }
    static Map<String, Object> preview(StandaloneRenderHarness.Options options, long window) throws Exception {
        var field = new SkyCloudField(SkyCloudSettings.DEFAULT); var camera = new Vector3d(-4000, 64, 7000);
        float yaw = (float) Math.PI, elevation = .55f; int frames = 0; long previous = System.nanoTime();
        try (var fixture = new GlFixture(); var renderer = new CloudVolumeRenderer()) {
            int target = target(fixture, options.width(), options.height());
            while (!GLFW.glfwWindowShouldClose(window) && (options.frames() == 0 || frames < options.frames())) {
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) break;
                long now = System.nanoTime(); float dt = (float) Math.min(.1, (now - previous) / 1e9); previous = now;
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS) yaw -= dt;
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS) yaw += dt;
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS) elevation = Math.min(1.5f, elevation + dt);
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS) elevation = Math.max(-1.5f, elevation - dt);
                float forward = (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS ? 1 : 0) - (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS ? 1 : 0);
                camera.add(Math.sin(yaw) * forward * dt * 2000, 0, -Math.cos(yaw) * forward * dt * 2000);
                field.tick(dt); draw(renderer, target, options.width(), options.height(), camera, field.sample(camera.x, camera.y, camera.z, 1, 0), yaw, elevation, false);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
                GL30.glBlitFramebuffer(0, 0, options.width(), options.height(), 0, 0, options.width(), options.height(), GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
                GLFW.glfwSwapBuffers(window); GLFW.glfwPollEvents(); frames++;
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target);
            GlFixture.image(options.report().getParent().resolve("sky-preview.png"), options.width(), options.height(), GlFixture.read(options.width(), options.height(), GL11.GL_RGBA, 4));
        }
        return Map.of("type", "sky-field-preview", "frames", frames, "controls", "arrows: look; W/S: fly; Esc: close");
    }
    private static double changedPixels(float[] image, float[] baseline) {
        int changed = 0;
        for (int pixel = 0; pixel < image.length; pixel += 4) {
            float delta = 0;
            for (int c = 0; c < 3; c++) delta = Math.max(delta, Math.abs(image[pixel + c] - baseline[pixel + c]));
            if (delta > .02) changed++;
        }
        return changed / (image.length / 4.0);
    }
    private static double difference(float[] a, float[] b, int w, int h, int left, int right) {
        double sum = 0;
        for (int y = 0; y < h; y++) for (int x = left; x < right; x++) for (int c = 0; c < 3; c++) sum += Math.abs(a[(y * w + x) * 4 + c] - b[(y * w + x) * 4 + c]);
        return sum / (h * (right - left) * 3.0);
    }
    private static void finite(float[] image) { for (float v : image) require(Float.isFinite(v) && v >= 0 && v <= 1, "Invalid sky output"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
