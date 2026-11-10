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
            validateOptics(renderer, fixture, target, camera, baseline, output);
            validateHistory(renderer, fixture, target, camera, baseline, output);
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
            var owned = renderer.resources();
            require(owned.textures().size() > 20 && owned.framebuffers().size() > 2, "Cloud ownership snapshot is incomplete");
            renderer.close();
            for (int id : owned.textures()) require(!GL11.glIsTexture(id), "Cloud texture survived close: " + id);
            for (int id : owned.framebuffers()) require(!GL30.glIsFramebuffer(id), "Cloud framebuffer survived close: " + id);
            for (int id : owned.programs()) require(!GL20.glIsProgram(id), "Cloud program survived close: " + id);
            require(!GL30.glIsVertexArray(owned.vertexArray()), "Cloud vertex array survived close");
            require(GL30.glIsFramebuffer(target), "Closing clouds deleted the host target");
            System.out.println("Sky field: " + frame.weatherCellCount() + " weather cells/window, recenter MAD=" + seam);
            GlFixture.noError("complete sky field");
        }
    }
    private static void validateOptics(CloudVolumeRenderer renderer, GlFixture fixture, int target, Vector3d camera,
                                       float[] baseline, Path output) throws Exception {
        var settings = SkyCloudSettings.DEFAULT;
        var optics = settings.rendering();
        var zero = new SkyCloudField(settings.withRendering(optics.withDensityDetail(0, .12f, 4))).sample(0, 64, 0, 1, 0);
        require(difference(baseline, render(renderer, target, W, H, camera, zero, 0, .2f, false), W, H, 0, W) < 1e-7,
                "Zero extinction still modifies the scene");

        // Noninteger detail scale must remain world anchored when weather textures recenter.
        var fractional = new SkyCloudField(settings.withRendering(optics.withDensityDetail(.009f, .25f, 3.7f)));
        var a = fractional.sample(0, 64, 0, 1, 0);
        var shift = fractional.sample(settings.cellSize(), 64, 0, 1, 0);
        var b = new SkyCloudField.Frame(shift.settings(), shift.window(), shift.timeSeconds(),
                shift.originX() + settings.cellSize(), shift.baseY(), shift.originZ(), shift.coverage());
        float[] first = render(renderer, target, W, H, camera, a, 0, .2f, false);
        double detailSeam = difference(first, render(renderer, target, W, H, camera, b, 0, .2f, false), W, H, 0, W);
        require(detailSeam < .00001, "Fractional detail noise jumps on recenter: " + detailSeam);
        var moved = new Vector3d(.1, 64, .1);
        double movement = difference(first, render(renderer, target, W, H, moved,
                fractional.sample(moved.x, moved.y, moved.z, 1, 0), .0001f, .2001f, false), W, H, 0, W);
        require(movement < .003, "Small camera motion caused an unstable cloud frame: " + movement);

        var field = new SkyCloudField(settings);
        var frame = field.sample(0, 64, 0, 1, 0);
        float[] previous = null;
        for (float elevation : new float[]{-.0001f, 0, .0001f}) {
            float[] horizon = render(renderer, target, W, H, camera, frame, 0, elevation, false);
            finite(horizon);
            if (previous != null) require(difference(previous, horizon, W, H, 0, W) < .003, "Discontinuous horizon intersection");
            previous = horizon;
        }
        GlFixture.image(output.resolve("sky-horizon.png"), W, H, previous);
        float[] full = null;
        for (int divisor : new int[]{1, 2, 3}) {
            var spatial = settings.withRendering(optics.withSampling(160, 4, divisor, true));
            float[] result = render(renderer, target, W, H, camera, new SkyCloudField(spatial).sample(0, 64, 0, 1, 0), 0, .2f, true);
            if (full == null) full = result;
            double error = difference(full, result, W, H, 0, W);
            require(error < .015, "Depth-aware spatial reconstruction error divisor=" + divisor + " error=" + error);
        }
        float[] noon = render(renderer, target, W, H, camera, frame, 0, .2f, false);
        render(renderer, target, W, H, camera, new SkyCloudField(settings.withCoverage(0)).sample(0, 64, 0, 1, 0), 0, .2f, false);
        var infiniteProjection = new Matrix4f().perspective((float) Math.toRadians(90), (float) W / H, .1f, Float.POSITIVE_INFINITY);
        try (var pass = renderer.begin(view(0, .2f), infiniteProjection, camera, SUN, DIRECT, AMBIENT)) { pass.drawSky(frame); }
        var infinite = GlFixture.read(W, H, GL11.GL_RGBA, 4);
        finite(infinite);
        require(difference(noon, infinite, W, H, 0, W) < .00001, "Infinite far plane changed the cloud volume");
        for (String time : new String[]{"sunrise", "sunset", "night", "overcast"}) {
            var sun = new Vector3f(time.equals("sunset") ? 1 : -1, time.equals("night") ? -1 : .06f, 0).normalize();
            var direct = time.equals("night") ? new Vector3f() : new Vector3f(.98f, .42f, .19f).mul(.6f);
            var ambient = time.equals("night") ? new Vector3f(.025f, .035f, .06f) : new Vector3f(.20f, .23f, .29f);
            var atmosphere = time.equals("night") ? new Vector3f(.015f, .025f, .05f) : new Vector3f(.30f, .25f, .24f);
            var weatherFrame = time.equals("overcast")
                    ? new SkyCloudField(SkyCloudSettings.OVERCAST).sample(0, 64, 0, 1, 1) : frame;
            if (time.equals("overcast")) { sun.set(SUN); direct.set(.16f); ambient.set(.4f); atmosphere.set(.38f, .42f, .48f); }
            draw(renderer, target, W, H, camera, weatherFrame, 0, .2f, false, sun, direct, ambient, atmosphere);
            float[] result = GlFixture.read(W, H, GL11.GL_RGBA, 4);
            finite(result);
            require(difference(noon, result, W, H, 0, W) > .015, "Dynamic cloud lighting did not affect pixels: " + time);
            GlFixture.image(output.resolve("sky-" + time + ".png"), W, H, result);
        }
        // HDR composition must retain radiance above one and cannot invent energy
        // when the sun, sky and atmosphere are all black.
        int hdr = fixture.framebuffer(fixture.texture(GL30.GL_RGBA16F, GL11.GL_RGBA, W, H, null),
                fixture.texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, W, H, null));
        var linear = settings.withRendering(optics.withColorSpace(SkyCloudRendering.ColorSpace.LINEAR_HDR));
        var linearFrame = new SkyCloudField(linear).sample(0, 64, 0, 1, 0);
        draw(renderer, hdr, W, H, camera, linearFrame, 0, .2f, false, SUN, new Vector3f(8), new Vector3f(2), new Vector3f(2));
        float[] hdrImage = GlFixture.read(W, H, GL11.GL_RGBA, 4);
        for (int i = 0; i < hdrImage.length; i++) require(Float.isFinite(hdrImage[i]) && hdrImage[i] >= 0
                && ((i & 3) != 3 || Math.abs(hdrImage[i] - 1) < .001), "Invalid HDR radiance or alpha");
        float maximum = 0;
        for (int i = 0; i < hdrImage.length; i++) if ((i & 3) != 3) maximum = Math.max(maximum, hdrImage[i]);
        require(maximum > 1.5, "Linear HDR cloud radiance was clipped or tone mapped: " + maximum);
        draw(renderer, hdr, W, H, camera, linearFrame, 0, .2f, false,
                SUN, new Vector3f(), new Vector3f(), new Vector3f());
        float[] black = GlFixture.read(W, H, GL11.GL_RGBA, 4);
        for (int i = 0; i < black.length; i++) require(Float.isFinite(black[i])
                && ((i & 3) == 3 ? black[i] == 1 : black[i] == 0), "Clouds invented energy in a zero-source scene");
        // Sparse cover includes optically thin and empty rays, where stored
        // radiance and the final background composite are distinguishable.
        var diagnosticSettings = settings.withCoverage(.22f);
        float[] diagnosticReference = render(renderer, target, W, H, camera,
                new SkyCloudField(diagnosticSettings).sample(0, 64, 0, 1, 0), 0, .2f, false);
        for (var debug : SkyCloudRendering.DebugView.values()) {
            var diagnosed = diagnosticSettings.withRendering(optics.withDebug(debug));
            float[] result = render(renderer, target, W, H, camera,
                    new SkyCloudField(diagnosed).sample(0, 64, 0, 1, 0), 0, .2f, false);
            finite(result);
            if (debug != SkyCloudRendering.DebugView.NONE)
                require(difference(diagnosticReference, result, W, H, 0, W) > .02, "Debug view did not change output: " + debug);
            GlFixture.image(output.resolve("sky-debug-" + debug.name().toLowerCase(Locale.ROOT) + ".png"), W, H, result);
        }
        System.out.println("Sky optics: fractional recenter=" + detailSeam + " movement=" + movement + " HDR maximum=" + maximum);
    }
    private static int target(GlFixture fixture, int w, int h) {
        return fixture.framebuffer(fixture.texture(GL11.GL_RGBA8, GL11.GL_RGBA, w, h, null),
                fixture.texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, w, h, null));
    }
    private static void validateHistory(CloudVolumeRenderer renderer, GlFixture fixture, int target, Vector3d camera,
                                        float[] baseline, Path output) throws Exception {
        var settings = SkyCloudSettings.DEFAULT.withRendering(SkyCloudRendering.DEFAULT.withDebug(SkyCloudRendering.DebugView.HISTORY_VALIDITY));
        var field = new SkyCloudField(settings);
        float[] first = temporal(renderer, target, W, H, camera, field.sample(0, 64, 0, 1, 0), "a", false);
        require(historyGreen(first) == 0, "First view frame reused undefined history");
        field.tick(1.0 / 60);
        float[] stable = temporal(renderer, target, W, H, camera, field.sample(0, 64, 0, 1, 0), "a", false);
        require(historyGreen(stable) > 500, "No real cloud samples accepted temporal history");
        GlFixture.image(output.resolve("sky-history-valid.png"), W, H, stable);
        float[] other = temporal(renderer, target, W, H, camera, field.sample(0, 64, 0, 1, 0), "b", false);
        require(historyGreen(other) == 0 && renderer.cachedHistoryCount() == 2, "Same-size views shared history");
        field.tick(1.0 / 60);
        require(historyGreen(temporal(renderer, target, W, H, camera, field.sample(0, 64, 0, 1, 0), "a", false)) > 500,
                "Rendering a second view invalidated the first view's history");
        var teleport = new Vector3d(64, 64, 0);
        require(historyGreen(temporal(renderer, target, W, H, teleport, field.sample(64, 64, 0, 1, 0), "a", false)) == 0,
                "Camera teleport retained cloud history");
        field.tick(.5);
        require(historyGreen(temporal(renderer, target, W, H, teleport, field.sample(64, 64, 0, 1, 0), "a", false)) == 0,
                "Expired cloud history was reused");
        field.settings(settings.withCoverage(.9f));
        require(historyGreen(temporal(renderer, target, W, H, teleport, field.sample(64, 64, 0, 1, 0), "a", false)) == 0,
                "Weather/settings change retained cloud history");
        field.tick(1.0 / 60);
        require(historyGreen(temporal(renderer, target, W, H, teleport, field.sample(64, 64, 0, 1, 0), "a", false)) > 500,
                "Cloud history did not recover after reset");
        draw(renderer, target, W, H, teleport, field.sample(64, 64, 0, 1, 0), 0, .2f, false,
                SUN, new Vector3f(.02f), AMBIENT, new Vector3f(.22f, .42f, .67f), "a");
        require(historyGreen(GlFixture.read(W, H, GL11.GL_RGBA, 4)) == 0, "Lighting discontinuity retained history");

        var beforeResize = renderer.resources();
        int resized = target(fixture, W - 12, H - 8);
        require(historyGreen(temporal(renderer, resized, W - 12, H - 8, camera,
                field.sample(0, 64, 0, 1, 0), "a", false)) == 0, "Resized view retained history");
        var afterResize = renderer.resources();
        int retiredTextures = 0;
        for (int id : beforeResize.textures()) if (!afterResize.textures().contains(id)) {
            require(!GL11.glIsTexture(id), "Resize left an old history texture alive"); retiredTextures++;
        }
        require(retiredTextures >= 4, "Resize did not retire both history metadata/color pairs");

        // Disocclusion: a new opaque half-screen must reject the old cloud.
        var normal = new SkyCloudField(SkyCloudSettings.DEFAULT);
        temporal(renderer, target, W, H, camera, normal.sample(0, 64, 0, 1, 0), "motion", false);
        normal.tick(1.0 / 60);
        float[] occluded = temporal(renderer, target, W, H, camera, normal.sample(0, 64, 0, 1, 0), "motion", true);
        require(difference(occluded, baseline, W, H, 0, W / 2 + 1) < 1e-6, "Temporal history leaked across a new opaque silhouette");
        float[] previous = null;
        double largest = 0;
        for (int i = 0; i < 16; i++) {
            normal.tick(1.0 / 60);
            var observer = new Vector3d(i * .1, 64, 0);
            float[] picture = temporal(renderer, target, W, H, observer, normal.sample(observer.x, 64, 0, 1, 0), "motion", false);
            finite(picture);
            if (previous != null) largest = Math.max(largest, difference(previous, picture, W, H, 0, W));
            previous = picture;
        }
        require(largest < .015, "Cloud history produced unstable movement frames: " + largest);
        GlFixture.image(output.resolve("sky-history-motion.png"), W, H, previous);
        for (int i = 0; i < 12; i++) temporal(renderer, target, W, H, camera,
                normal.sample(0, 64, 0, 1, 0), "bounded-" + i, false);
        require(renderer.cachedHistoryCount() == 8, "View history cache is unbounded");
        var beforeClear = renderer.resources();
        renderer.clearHistory();
        var afterClear = renderer.resources();
        int removed = 0;
        for (int id : beforeClear.textures()) if (!afterClear.textures().contains(id)) {
            require(!GL11.glIsTexture(id), "clearHistory left a history texture alive"); removed++;
        }
        require(removed == 32 && renderer.cachedHistoryCount() == 0, "clearHistory did not release all eight views");
        var off = SkyCloudSettings.DEFAULT.withRendering(SkyCloudRendering.DEFAULT.withTemporal(SkyCloudTemporal.OFF));
        temporal(renderer, target, W, H, camera, new SkyCloudField(off).sample(0, 64, 0, 1, 0), "off", false);
        require(renderer.cachedHistoryCount() == 0, "Disabling accumulation still allocates history");
        System.out.println("Sky temporal: valid pixels=" + historyGreen(stable) + " movement MAD=" + largest
                + " retiredOnResize=" + retiredTextures + " releasedHistoryTextures=" + removed);
    }
    private static int historyGreen(float[] image) {
        int count = 0;
        for (int i = 0; i < image.length; i += 4) if (image[i] < .05 && image[i + 1] > .95 && image[i + 2] < .05) count++;
        return count;
    }
    private static float[] temporal(CloudVolumeRenderer renderer, int target, int w, int h, Vector3d camera,
                                     SkyCloudField.Frame frame, String id, boolean occluded) {
        draw(renderer, target, w, h, camera, frame, 0, .2f, occluded, SUN, DIRECT, AMBIENT, new Vector3f(.22f, .42f, .67f), id);
        return GlFixture.read(w, h, GL11.GL_RGBA, 4);
    }
    private static Matrix4f view(float yaw, float elevation) {
        return new Matrix4f().lookAt(new Vector3f(), new Vector3f((float) Math.sin(yaw) * (float) Math.cos(elevation),
                (float) Math.sin(elevation), -(float) Math.cos(yaw) * (float) Math.cos(elevation)), new Vector3f(0, 1, 0));
    }
    private static void draw(CloudVolumeRenderer renderer, int target, int w, int h, Vector3d camera,
                             SkyCloudField.Frame frame, float yaw, float elevation, boolean occluded) {
        draw(renderer, target, w, h, camera, frame, yaw, elevation, occluded, SUN, DIRECT, AMBIENT, new Vector3f(.22f, .42f, .67f));
    }
    private static void draw(CloudVolumeRenderer renderer, int target, int w, int h, Vector3d camera,
                             SkyCloudField.Frame frame, float yaw, float elevation, boolean occluded,
                             Vector3f sun, Vector3f direct, Vector3f ambient, Vector3f atmosphere) {
        draw(renderer, target, w, h, camera, frame, yaw, elevation, occluded, sun, direct, ambient, atmosphere, null);
    }
    private static void draw(CloudVolumeRenderer renderer, int target, int w, int h, Vector3d camera,
                             SkyCloudField.Frame frame, float yaw, float elevation, boolean occluded,
                             Vector3f sun, Vector3f direct, Vector3f ambient, Vector3f atmosphere, String id) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target); GL11.glViewport(0, 0, w, h);
        GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glColorMask(true, true, true, true); GL11.glDepthMask(true);
        boolean blackScene = direct.lengthSquared() + ambient.lengthSquared() + atmosphere.lengthSquared() == 0;
        GL11.glClearColor(blackScene ? 0 : .22f, blackScene ? 0 : .42f, blackScene ? 0 : .67f, 1);
        GL11.glClearDepth(1); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        if (occluded) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(0, 0, w / 2 + 1, h); GL11.glClearDepth(.01);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT); GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glClearDepth(1);
        }
        var projection = new Matrix4f().perspective((float) Math.toRadians(90), (float) w / h, .1f, 512);
        try (var pass = renderer.begin(view(yaw, elevation), projection, camera, sun, direct, ambient, id)) {
            pass.atmosphereColor(atmosphere); pass.drawSky(frame);
        }
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
                for (var quality : SkyCloudSettings.Quality.values()) for (String name : new String[]{"ground", "sparse", "overcast", "inside", "rain", "travel", "temporal"}) {
                    var preset = name.equals("sparse") ? SkyCloudSettings.SCATTERED
                            : name.equals("overcast") ? SkyCloudSettings.OVERCAST : SkyCloudSettings.DEFAULT;
                    var settings = preset.withQuality(quality);
                    var field = new SkyCloudField(settings);
                    var camera = new Vector3d(name.equals("travel") ? 40000 : 0, name.equals("inside") ? 1800 : 64, 0);
                    var frame = field.sample(camera.x, camera.y, camera.z, 1, name.equals("rain") ? 1 : 0);
                    for (int i = 0; i < options.warmup(); i++) {
                        field.tick(1.0 / 60);
                        frame = field.sample(camera.x, camera.y, camera.z, 1, name.equals("rain") ? 1 : 0);
                        draw(renderer, target, options.width(), options.height(), camera, frame, 0, .2f, false,
                                SUN, DIRECT, AMBIENT, new Vector3f(.22f, .42f, .67f), name.equals("temporal") ? quality.name() : null);
                    }
                    GL11.glFinish(); double[] times = new double[Math.max(1, options.frames())];
                    for (int i = 0; i < times.length; i++) {
                        field.tick(1.0 / 60); frame = field.sample(camera.x, camera.y, camera.z, 1, name.equals("rain") ? 1 : 0);
                        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
                        draw(renderer, target, options.width(), options.height(), camera, frame, 0, .2f, false,
                                SUN, DIRECT, AMBIENT, new Vector3f(.22f, .42f, .67f), name.equals("temporal") ? quality.name() : null);
                        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        times[i] = ARBTimerQuery.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT) / 1e6;
                    }
                    Arrays.sort(times);
                    String key = quality.name().toLowerCase(Locale.ROOT) + "/" + name;
                    scenarios.put(key, Map.of("gpuP50Ms", times[times.length / 2], "gpuP95Ms", times[Math.min(times.length - 1, (int) Math.ceil(times.length * .95) - 1)],
                            "frames", times.length, "warmup", options.warmup(), "weatherCellsInWindow", frame.weatherCellCount(), "layers", settings.layers().size(),
                            "resolutionDivisor", quality.resolutionDivisor, "viewSteps", quality.sampleBudget, "lightSteps", quality.lightSteps));
                    System.out.println("Sky GPU " + key + ": " + scenarios.get(key));
                }
            } finally { GL15.glDeleteQueries(query); }
        }
        return Map.of("type", "sky-field-gpu", "width", options.width(), "height", options.height(), "scenarios", scenarios,
                "measurement", "GL_TIME_ELAPSED including depth/color copies, density/lighting, metadata, optional temporal resolve and composite; isolated context");
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
