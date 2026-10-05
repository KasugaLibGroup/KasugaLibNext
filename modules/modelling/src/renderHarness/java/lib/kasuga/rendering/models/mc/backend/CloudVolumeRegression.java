package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.cloud.*;
import lib.kasuga.rendering.cloud.gl.CloudVolumeRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

/** Exercises the production ray marcher with scene depth, inside views and independent view sizes. */
final class CloudVolumeRegression {
    private static final Vector3f SUN = new Vector3f(-.5f, .8f, .3f).normalize();
    private static final Vector3f SUN_COLOR = new Vector3f(.9f, .84f, .74f), AMBIENT = new Vector3f(.1f, .15f, .23f);
    private static final int WIDTH = 384, HEIGHT = 256;
    private CloudVolumeRegression() {}

    static void run(Path output) throws Exception {
        CloudVolumeRenderer.prepareNoise().join();
        GlFixture.resource(CloudVolumeRenderer.FRAGMENT_RESOURCE);
        for (String name : new String[]{"density.glsl", "lighting.fsh", "upsample.fsh"})
            GlFixture.resource(CloudVolumeRenderer.RESOURCE_ROOT + name);
        try (var fixture = new GlFixture(); var renderer = new CloudVolumeRenderer()) {
            int color = fixture.texture(GL30.GL_RGBA16F, GL11.GL_RGBA, WIDTH, HEIGHT, null);
            int depth = fixture.texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, WIDTH, HEIGHT, null);
            int target = fixture.framebuffer(color, depth);
            var pose = new CloudPose(0, 0, 0, 240, 200, 180, 0);
            var frame = new CumulonimbusVolume.Frame(pose, CloudSettings.CUMULONIMBUS.withQuality(CloudSettings.Quality.HIGH), 0);
            var camera = new Vector3d(0, 80, 360);
            float[] baseline = render(renderer, target, WIDTH, HEIGHT, camera, frame, true, false);
            float[] cloud = render(renderer, target, WIDTH, HEIGHT, camera, frame, false, false);
            finite(cloud);
            GlFixture.image(output.resolve("cumulonimbus.png"), WIDTH, HEIGHT, cloud);
            GlFixture.noError("cloud render");
            double visibleDifference = difference(baseline, cloud, 0, WIDTH);
            System.out.println("Cloud mean color difference: " + visibleDifference);
            require(visibleDifference > .01, "Volume produced no visible cloud: " + visibleDifference);
            float[] again = render(renderer, target, WIDTH, HEIGHT, camera, frame, false, false);
            require(difference(cloud, again, 0, WIDTH) < 1e-7, "Static cloud is not deterministic");
            renderer.adaptiveMarching(false);
            float[] reference = render(renderer, target, WIDTH, HEIGHT, camera, frame, false, false);
            renderer.adaptiveMarching(true);
            double adaptiveError = difference(cloud, reference, 0, WIDTH);
            System.out.println("Cloud adaptive/reference difference: " + adaptiveError);
            require(adaptiveError < .003, "Coarse marching changed visible cloud shape: " + adaptiveError);
            var emptyCoverage = new CumulonimbusVolume.Frame(pose, frame.settings().withCoverage(0), 0);
            require(difference(render(renderer, target, WIDTH, HEIGHT, camera, emptyCoverage, false, false), baseline, 0, WIDTH) < 1e-6,
                    "Coverage zero did not remove the cloud");
            var changedSeed = new CloudSettings(1, .035f, .55f, 1, 321, CloudSettings.Quality.HIGH);
            float[] variation = render(renderer, target, WIDTH, HEIGHT, camera, new CumulonimbusVolume.Frame(pose, changedSeed, 0), false, false);
            require(difference(cloud, variation, 0, WIDTH) > .0005, "Seed did not affect cloud erosion");
            float[] moving = render(renderer, target, WIDTH, HEIGHT, camera, new CumulonimbusVolume.Frame(pose, frame.settings(), 20), false, false);
            require(difference(cloud, moving, 0, WIDTH) > .0005, "Cloud noise did not develop over time");
            // Every octave is periodic across phase wraps; animation must not pop after a few minutes.
            double seam = (1 - ((frame.settings().seed() * 0x45d9f3b ^ (frame.settings().seed() * 0x45d9f3b) >>> 16) & 255) / 256.0) / .006;
            float[] beforeWrap = render(renderer, target, WIDTH, HEIGHT, camera, new CumulonimbusVolume.Frame(pose, frame.settings(), seam - .001), false, false);
            float[] afterWrap = render(renderer, target, WIDTH, HEIGHT, camera, new CumulonimbusVolume.Frame(pose, frame.settings(), seam + .001), false, false);
            require(difference(beforeWrap, afterWrap, 0, WIDTH) < .001, "Noise animation popped at a periodic wrap");
            float[] occluded = render(renderer, target, WIDTH, HEIGHT, camera, frame, false, true);
            require(difference(occluded, baseline, 0, WIDTH / 2) < 1e-6, "Cloud leaked through foreground depth");
            require(difference(occluded, baseline, WIDTH / 2, WIDTH) > .003, "Foreground clipped unoccluded cloud pixels");
            GlFixture.image(output.resolve("cumulonimbus-depth.png"), WIDTH, HEIGHT, occluded);
            float[] sceneDepth = GlFixture.read(WIDTH, HEIGHT, GL11.GL_DEPTH_COMPONENT, 1);
            GlFixture.expect(sceneDepth[HEIGHT / 2 * WIDTH + WIDTH / 4], .01, 1e-6, "Cloud modified foreground depth");
            GlFixture.expect(sceneDepth[HEIGHT / 2 * WIDTH + WIDTH * 3 / 4], 1, 1e-6, "Cloud wrote background depth");
            float[] inside = render(renderer, target, WIDTH, HEIGHT, new Vector3d(-20, 80, 0), frame, false, false);
            finite(inside); require(difference(inside, baseline, 0, WIDTH) > .05, "Camera inside the cloud produced no volume");
            GlFixture.image(output.resolve("cumulonimbus-inside.png"), WIDTH, HEIGHT, inside);
            var distant = new CumulonimbusVolume.Frame(pose.moveTo(0, 0, 800), frame.settings(), 0);
            require(difference(render(renderer, target, WIDTH, HEIGHT, camera, distant, false, false), baseline, 0, WIDTH) < 1e-6,
                    "Cloud behind the camera became visible");
            var balanced = new CumulonimbusVolume.Frame(pose, frame.settings().withQuality(CloudSettings.Quality.BALANCED), 0);
            float[] halfResolution = render(renderer, target, WIDTH, HEIGHT, camera, balanced, false, false);
            double upsampleError = difference(cloud, halfResolution, 0, WIDTH);
            System.out.println("Cloud half/full resolution difference: " + upsampleError);
            require(upsampleError < .008, "Depth-aware upsampling lost too much cloud detail: " + upsampleError);
            GlFixture.image(output.resolve("cumulonimbus-balanced.png"), WIDTH, HEIGHT, halfResolution);
            float[] halfOccluded = render(renderer, target, WIDTH, HEIGHT, camera, balanced, false, true);
            require(difference(halfOccluded, baseline, 0, WIDTH / 2 + 1) < 1e-6, "Half-resolution cloud leaked across an unaligned silhouette");
            var sky = new CumulonimbusVolume.Frame(CloudPose.at(0, 0, -2200), frame.settings(), 0);
            require(difference(render(renderer, target, WIDTH, HEIGHT, camera, sky, false, false), baseline, 0, WIDTH) > .0005,
                    "Terrain far plane removed distant sky clouds");
            stateRestoration(renderer, fixture, target, frame, camera);
            // Reuse the renderer after a viewport AND depth-format change, as detached cameras do.
            int smallColor = fixture.texture(GL11.GL_RGBA8, GL11.GL_RGBA, 96, 64, null);
            int smallDepth = fixture.texture(GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, 96, 64, null);
            int smallTarget = fixture.framebuffer(smallColor, smallDepth);
            finite(render(renderer, smallTarget, 96, 64, camera, frame, false, false));
            require(difference(render(renderer, target, WIDTH, HEIGHT, camera, frame, false, false), cloud, 0, WIDTH) < 1e-7,
                    "Switching cameras changed the cloud state");
            require(renderer.cachedViewCount() == 2, "Camera view targets were recreated instead of retained");
            // First upload must ignore host pixel-unpack layout/byte order, then restore it.
            GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_TRUE);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 7);
            try (var fresh = new CloudVolumeRenderer()) {
                require(difference(render(fresh, target, WIDTH, HEIGHT, camera, frame, false, false), cloud, 0, WIDTH) < 1e-7,
                        "Host unpack layout corrupted baked shape upload");
                require(GL11.glGetInteger(GL11.GL_UNPACK_SWAP_BYTES) == GL11.GL_TRUE
                        && GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH) == 7, "Host unpack state not restored");
            } finally {
                GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_FALSE); GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            }
        }
    }

    static float[] render(CloudVolumeRenderer renderer, int target, int width, int height, Vector3d camera,
                          CumulonimbusVolume.Frame cloud, boolean empty, boolean occluded) {
        draw(renderer, target, width, height, camera, cloud, empty, occluded);
        return GlFixture.read(width, height, GL11.GL_RGBA, 4);
    }

    private static void draw(CloudVolumeRenderer renderer, int target, int width, int height, Vector3d camera,
                             CumulonimbusVolume.Frame cloud, boolean empty, boolean occluded) {
        draw(renderer, target, width, height, camera, cloud, empty, occluded, 100);
    }

    private static void draw(CloudVolumeRenderer renderer, int target, int width, int height, Vector3d camera,
                             CumulonimbusVolume.Frame cloud, boolean empty, boolean occluded, float targetHeight) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target); GL11.glViewport(0, 0, width, height);
        GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glColorMask(true, true, true, true); GL11.glDepthMask(true);
        GL11.glClearColor(.22f, .42f, .67f, 1); GL11.glClearDepth(1); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        if (occluded) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(0, 0, width / 2 + 1, height); GL11.glClearDepth(.01);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT); GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glClearDepth(1);
        }
        if (!empty) {
            var view = view(camera, targetHeight);
            var projection = new Matrix4f().perspective((float) Math.toRadians(52), (float) width / height, .1f, 1500);
            try (var pass = renderer.begin(view, projection, camera, SUN, SUN_COLOR, AMBIENT)) { pass.draw(cloud); }
        }
    }

    static java.util.Map<String, Object> preview(StandaloneRenderHarness.Options options, long window) throws Exception {
        int width = options.width(), height = options.height(), frames = 0;
        double orbit = 0, radius = 2000;
        long started = System.nanoTime(), previous = started;
        try (var fixture = new GlFixture(); var renderer = new CloudVolumeRenderer()) {
            int target = fixture.framebuffer(fixture.texture(GL11.GL_RGBA8, GL11.GL_RGBA, width, height, null),
                    fixture.texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, width, height, null));
            while (!GLFW.glfwWindowShouldClose(window) && (options.frames() == 0 || frames < options.frames())) {
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) break;
                long now = System.nanoTime(); double dt = Math.min(.1, (now - previous) / 1e9); previous = now;
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS) orbit -= dt;
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS) orbit += dt;
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) radius = Math.max(30, radius - dt * 600);
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) radius = Math.min(4000, radius + dt * 600);
                var camera = new Vector3d(Math.sin(orbit) * radius, 420, Math.cos(orbit) * radius);
                var cloud = new CumulonimbusVolume.Frame(CloudPose.at(0, 0, 0), CloudSettings.CUMULONIMBUS,
                        (now - started) / 1e9);
                draw(renderer, target, width, height, camera, cloud, false, false, 520);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
                try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    var w = stack.mallocInt(1); var h = stack.mallocInt(1); GLFW.glfwGetFramebufferSize(window, w, h);
                    GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, w.get(0), h.get(0), GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
                }
                GLFW.glfwSwapBuffers(window); GLFW.glfwPollEvents(); frames++;
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target);
            GlFixture.image(options.report().getParent().resolve("cumulonimbus-preview.png"), width, height,
                    GlFixture.read(width, height, GL11.GL_RGBA, 4));
        }
        return java.util.Map.of("type", "cumulonimbus", "frames", frames, "elapsedSeconds", (System.nanoTime() - started) / 1e9,
                "controls", "left/right orbit, W/S approach/retreat, Escape close");
    }

    static java.util.Map<String, Object> benchmark(StandaloneRenderHarness.Options options) {
        if (!GL.getCapabilities().OpenGL33 && !GL.getCapabilities().GL_ARB_timer_query)
            throw new IllegalStateException("Cloud GPU benchmark requires timer queries");
        int width = options.width(), height = options.height();
        var results = new java.util.LinkedHashMap<String, Object>();
        try (var fixture = new GlFixture(); var renderer = new CloudVolumeRenderer()) {
            int target = fixture.framebuffer(fixture.texture(GL11.GL_RGBA8, GL11.GL_RGBA, width, height, null),
                    fixture.texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, width, height, null));
            // Retain the original pose/optical settings for before/after timings.
            var frame = new CumulonimbusVolume.Frame(new CloudPose(0, 0, 0, 240, 200, 180, 0),
                    new CloudSettings(1, .035f, .55f, .85f, 1, 124, CloudSettings.Quality.BALANCED), 0);
            int query = GL15.glGenQueries();
            try {
                for (String scenario : new String[]{"distant", "close", "animated-close", "default-storm"}) {
                    boolean storm = scenario.equals("default-storm");
                    var camera = storm ? new Vector3d(0, 420, 2000) : new Vector3d(0, 80, scenario.equals("distant") ? 360 : 120);
                    var cloud = storm ? new CumulonimbusVolume.Frame(CloudPose.at(0, 0, 0), CloudSettings.CUMULONIMBUS, 0) : frame;
                    float targetHeight = storm ? 600 : 100;
                    for (int i = 0; i < options.warmup(); i++) draw(renderer, target, width, height, camera, cloud, false, false, targetHeight);
                    GL11.glFinish();
                    double[] times = new double[Math.max(1, options.frames())];
                    for (int i = 0; i < times.length; i++) {
                        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
                        var sample = scenario.equals("animated-close") || storm
                                ? new CumulonimbusVolume.Frame(cloud.pose(), cloud.settings(), i / 60.0) : cloud;
                        draw(renderer, target, width, height, camera, sample, false, false, targetHeight);
                        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        times[i] = ARBTimerQuery.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT) / 1_000_000.0;
                    }
                    java.util.Arrays.sort(times);
                    results.put(scenario, java.util.Map.of("gpuP50Ms", times[times.length / 2],
                            "gpuP95Ms", times[Math.min(times.length - 1, (int) Math.ceil(times.length * .95) - 1)], "frames", times.length));
                    System.out.println("Cloud GPU " + scenario + ": " + results.get(scenario));
                }
            } finally { GL15.glDeleteQueries(query); }
        }
        return java.util.Map.of("type", "cumulonimbus-gpu", "width", width, "height", height,
                "measurement", "GL_TIME_ELAPSED including depth copy and composite; isolated context", "scenarios", results);
    }

    static Matrix4f view(Vector3d camera) {
        return view(camera, 100);
    }
    static Matrix4f view(Vector3d camera, float targetHeight) {
        // Matrices rotate camera-relative world coordinates, as the Minecraft adapter does.
        var direction = camera.lengthSquared() > 10 ? new Vector3f((float) -camera.x, (float) (targetHeight - camera.y), (float) -camera.z)
                : new Vector3f(0, 0, -1);
        return new Matrix4f().lookAt(new Vector3f(), direction, new Vector3f(0, 1, 0));
    }

    private static void stateRestoration(CloudVolumeRenderer renderer, GlFixture fixture, int target,
                                         CumulonimbusVolume.Frame frame, Vector3d camera) {
        int other = fixture.framebuffer(fixture.texture(GL11.GL_RGBA8, GL11.GL_RGBA, WIDTH, HEIGHT, null), 0);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, other); GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target);
        int oldProgram = fixture.program("#version 150\nout vec4 fragColor; void main(){fragColor=vec4(1);}");
        GL20.glUseProgram(oldProgram); GL13.glActiveTexture(GL13.GL_TEXTURE5);
        GL11.glEnable(GL11.GL_BLEND); GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(1, 2, 10, 11);
        GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glEnable(GL11.GL_CULL_FACE); GL11.glDepthMask(true);
        GL11.glColorMask(false, true, false, true); GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
        try (var pass = renderer.begin(view(camera), new Matrix4f().perspective(.9f, 1.5f, .1f, 1500), camera, SUN, SUN_COLOR, AMBIENT)) { pass.draw(frame); }
        require(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) == other && GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == target, "Read/draw FBOs not restored");
        require(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) == oldProgram && GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE5, "Program/texture unit not restored");
        require(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) && GL11.glIsEnabled(GL11.GL_DEPTH_TEST) && GL11.glIsEnabled(GL11.GL_CULL_FACE), "Host enables not restored");
        int[] scissor = new int[4]; GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
        require(java.util.Arrays.equals(scissor, new int[]{1, 2, 10, 11}), "Host scissor rectangle not restored");
        require(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) && GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB) == GL11.GL_SRC_ALPHA, "Host masks/blending not restored");
        GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_BLEND);
        GL11.glColorMask(true, true, true, true); GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static double difference(float[] left, float[] right, int xMin, int xMax) {
        double sum = 0;
        for (int y = 0; y < HEIGHT; y++) for (int x = xMin; x < xMax; x++) for (int c = 0; c < 3; c++)
            sum += Math.abs(left[(y * WIDTH + x) * 4 + c] - right[(y * WIDTH + x) * 4 + c]);
        return sum / (HEIGHT * (xMax - xMin) * 3);
    }
    private static void finite(float[] values) {
        for (float value : values) require(Float.isFinite(value) && value >= 0 && value <= 4, "Invalid volume output: " + value);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
