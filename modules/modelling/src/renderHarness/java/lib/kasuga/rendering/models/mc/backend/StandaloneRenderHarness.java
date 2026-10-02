package lib.kasuga.rendering.models.mc.backend;

import com.google.gson.GsonBuilder;
import org.lwjgl.Version;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain JVM entry point: no Minecraft singleton, mod loader, world, or mixin bootstrap. */
public final class StandaloneRenderHarness {
    private StandaloneRenderHarness() {}

    public static void main(String[] arguments) throws Exception {
        var options = Options.parse(arguments);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", options.mode);
        report.put("os", System.getProperty("os.name"));
        report.put("arch", System.getProperty("os.arch"));
        report.put("java", System.getProperty("java.version"));
        report.put("lwjgl", Version.getVersion());
        report.put("commit", System.getProperty("kasuga.render.commit", "unknown"));
        report.put("workingTreeDirty", System.getProperty("kasuga.render.dirty", "unknown"));
        report.put("fixtureRevision", "peel-alpha-depth-skinning-upload-ring-v2");
        report.put("minecraftStarted", false);
        report.put("variant", "common-gl");
        report.put("width", options.width);
        report.put("height", options.height);
        report.put("shaderHashes", GlFixture.SHADER_HASHES);
        List<Map<String, Object>> results = new ArrayList<>();
        report.put("tests", results);
        long window = 0;
        boolean initialized = false;
        Throwable failure = null;
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err);
        errors.set();
        try {
            initialized = GLFW.glfwInit();
            if (!initialized) throw new IllegalStateException("GLFW unavailable: a working display/context is required");
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, options.mode.equals("preview") ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(options.width, options.height, "Kasuga standalone render tests", 0, 0);
            if (window == 0) throw new IllegalStateException("Cannot create OpenGL 3.2 core context");
            GLFW.glfwMakeContextCurrent(window);
            GLCapabilities caps = GL.createCapabilities();
            if (!caps.OpenGL32) throw new IllegalStateException("OpenGL 3.2 core unavailable");
            report.put("vendor", GL11.glGetString(GL11.GL_VENDOR));
            report.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
            report.put("glVersion", GL11.glGetString(GL11.GL_VERSION));
            var extensions = new ArrayList<String>();
            for (int i = 0; i < GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS); i++) {
                extensions.add(GL30.glGetStringi(GL11.GL_EXTENSIONS, i));
            }
            report.put("extensions", extensions);
            System.out.println("Standalone GL: " + report.get("renderer") + " / " + report.get("glVersion"));
            var suite = new RenderRegressionSuite(options.report.getParent());
            run(results, "depth-precision", suite::depthPrecision);
            run(results, "depth-handoff", suite::depthHandoff);
            run(results, "conditional-peel-loop", suite::conditionalPeel);
            run(results, "production-program-links", suite::productionPrograms);
            run(results, "production-alpha-passes", suite::alphaPasses);
            run(results, "target-resize-and-failure", suite::targetLifecycle);
            run(results, "production-transform-feedback", SkinningRegression::run);
            run(results, "fenced-upload-ring", UploadRingRegression::run);
            run(results, "completed-frame-output", FrameOutputRegression::run);
            run(results, "shared-preview-windows", lib.kasuga.rendering.output.gl.PreviewWindowRegression::run);
            if (results.stream().anyMatch(result -> !Boolean.TRUE.equals(result.get("passed")))) {
                throw new AssertionError("Standalone GPU regression failed; inspect report.json");
            }
            if (!options.mode.equals("test")) {
                report.put("scene", suite.measure(options, window));
            }
            if (options.mode.equals("bench")) {
                report.put("uploadRingBenchmark", UploadRingBenchmark.run(options.warmup, options.frames));
            }
            GlFixture.noError("harness completion");
            report.put("status", "passed");
        } catch (Throwable exception) {
            failure = exception;
            report.put("status", initialized && window != 0 ? "failed" : "context-unavailable");
            report.put("error", exception.toString());
        } finally {
            if (window != 0) {
                GLFW.glfwMakeContextCurrent(0);
                GL.setCapabilities(null);
                GLFW.glfwDestroyWindow(window);
            }
            if (initialized) GLFW.glfwTerminate();
            GLFW.glfwSetErrorCallback(null);
            errors.free();
            Files.createDirectories(options.report.toAbsolutePath().getParent());
            Files.writeString(options.report, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            System.out.println("Render report: " + options.report);
        }
        if (failure != null) throw new IllegalStateException("Standalone rendering did not pass", failure);
    }

    private static void run(List<Map<String, Object>> results, String name, CheckedRunnable test) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        try {
            test.run();
            GlFixture.noError(name);
            result.put("passed", true);
            System.out.println("PASS " + name);
        } catch (Throwable exception) {
            result.put("passed", false);
            result.put("error", exception.toString());
            System.err.println("FAIL " + name + ": " + exception);
            exception.printStackTrace(System.err);
            // Preserve the initial error in the report, then isolate the next fixture.
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { }
        }
        results.add(result);
    }

    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }

    record Options(String mode, Path report, int width, int height, int frames, int warmup) {
        static Options parse(String[] arguments) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < arguments.length; i += 2) {
                if (i + 1 >= arguments.length || !arguments[i].startsWith("--")) {
                    throw new IllegalArgumentException("Expected --option value pairs");
                }
                String key = arguments[i].substring(2);
                if (!List.of("mode", "report", "width", "height", "frames", "warmup").contains(key)) {
                    throw new IllegalArgumentException("Unknown option " + key);
                }
                values.put(key, arguments[i + 1]);
            }
            String mode = values.getOrDefault("mode", "test");
            if (!List.of("test", "bench", "preview").contains(mode)) throw new IllegalArgumentException("Unknown mode " + mode);
            int width = Integer.parseInt(values.getOrDefault("width", "64"));
            int height = Integer.parseInt(values.getOrDefault("height", "64"));
            int frames = Integer.parseInt(values.getOrDefault("frames", "120"));
            int warmup = Integer.parseInt(values.getOrDefault("warmup", "30"));
            if (width < 8 || width > 4096 || height < 1 || height > 4096 || frames < 0 || warmup < 0
                    || (!mode.equals("preview") && frames == 0)) throw new IllegalArgumentException("Invalid frame/viewport limits");
            return new Options(mode, Path.of(values.getOrDefault("report", "build/reports/renderHarness/report.json")),
                    width, height, frames, warmup);
        }
    }
}
