package lib.kasuga.rendering.models.uml.loaders.assembly;

import com.google.gson.GsonBuilder;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import org.joml.Vector3f;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.function.Supplier;
import org.joml.Matrix4f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Optional local-asset probe. Input assets and generated reports are never source fixtures. */
public final class ModelAssemblyProbe {
    private static boolean cachedFirst;
    private record Asset(Path path, Model model) {}
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Set -PkasugaAssemblyFixtures to a directory of extracted local test models");
        Path report = Path.of(args[1]).toAbsolutePath();
        cachedFirst = args.length > 2 && args[2].equals("cached-first");
        var assets = new ArrayList<Asset>();
        var inventory = new ArrayList<Map<String, Object>>();
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".pmx")).sorted().toList();
        }
        for (Path file : paths) {
            var item = new LinkedHashMap<String, Object>(); item.put("path", root.relativize(file).toString());
            try {
                long start = System.nanoTime();
                Model model = PmxOverlayFixtures.read(file);
                item.put("loadMillis", (System.nanoTime() - start) / 1e6);
                item.put("vertices", model.getVertices().length); item.put("faces", model.getMeshes().length);
                item.put("bones", model.getBones().length); item.put("materials", model.getMaterialSet().getMaterials().length);
                assets.add(new Asset(file, model));
                System.out.println("LOADED " + item.get("path") + " vertices=" + model.getVertices().length + " bones=" + model.getBones().length);
            } catch (Exception failure) { item.put("error", failure.toString()); System.out.println("LOAD_FAILED " + item.get("path") + " " + failure); }
            inventory.add(item);
        }
        var bodies = assets.stream().filter(a -> a.path.toString().contains("pack_6") || a.path.toString().contains("pack_7")).toList();
        var parts = assets.stream().filter(a -> !bodies.contains(a)).toList();
        var compatibility = new ArrayList<Map<String, Object>>();
        for (Asset body : bodies) for (Asset part : parts) {
            var item = new LinkedHashMap<String, Object>();
            item.put("body", root.relativize(body.path).toString()); item.put("part", root.relativize(part.path).toString());
            int matched = 0, compatible = 0; float maximum = 0;
            for (Bone bone : part.model.getBones()) {
                Bone target = body.model.getSkeleton().getBoneMap().get(bone.getName());
                if (target == null) continue;
                matched++;
                float delta = difference(part.model.getSkeleton().getBindingAbsolute(bone).transform(),
                        body.model.getSkeleton().getBindingAbsolute(target).transform());
                maximum = Math.max(maximum, delta);
                if (delta <= 1e-4f) compatible++;
            }
            item.put("sameNameBones", matched); item.put("compatibleBones", compatible); item.put("maximumBindDelta", maximum);
            item.put("strictBindCompatible", matched == compatible);
            compatibility.add(item);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("os", System.getProperty("os.name")); result.put("arch", System.getProperty("os.arch"));
        result.put("order", cachedFirst ? "cached-first" : "uncached-first");
        result.put("geometryOnly", true); result.put("inventory", inventory); result.put("compatibility", compatibility);
        result.put("loaded", assets.size()); result.put("failed", paths.size() - assets.size());
        var benchmarks = new ArrayList<Map<String, Object>>();
        if (!bodies.isEmpty() && !parts.isEmpty()) {
            Asset body = bodies.stream().filter(a -> a.path.toString().contains("pack_7")).findFirst().orElse(bodies.getFirst());
            Asset dress = parts.stream().filter(a -> a.path.toString().contains("pack_3")).findFirst().orElse(parts.getFirst());
            try {
                Supplier<ModelAssembly.Request> requests = () -> new ModelAssemblyBuilder("body", body.model, 1)
                        .part("garment", dress.model, 1, config -> config.includeDynamics(false)).build();
                benchmarks.add(benchmark("compatible_body_and_dress", requests, body, dress, false, 1));
            } catch (IllegalArgumentException failure) {
                benchmarks.add(Map.of("case", "compatible_body_and_dress", "error", failure.toString()));
            }
            Asset small = parts.stream().min(Comparator.comparingInt(a -> a.model.getVertices().length)).orElseThrow();
            for (int count : new int[]{1, 10, 100}) {
                Supplier<ModelAssembly.Request> requests = () -> stressRequest(body.model, small.model, count);
                benchmarks.add(benchmark("compatible_bones_only_" + count + "_parts", requests, body, small, true, count));
            }
        }
        result.put("benchmarks", benchmarks);
        Files.createDirectories(report.getParent());
        Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println("MODEL_ASSEMBLY_PROBE inventory=" + assets.size() + " pairs=" + compatibility.size() + " report=" + report);
        if (assets.isEmpty()) throw new IllegalStateException("No local PMX assets loaded; inspect the geometry report");
    }
    private static ModelAssembly.Request stressRequest(Model body, Model part, int count) {
        var builder = new ModelAssemblyBuilder("body", body, 1);
        for (int i = 0; i < count; i++) builder.part("part_" + i, part, 1, config -> {
            config.matchBodyBones(false).includeDynamics(false);
            for (Bone bone : part.getBones()) {
                Bone target = body.getSkeleton().getBoneMap().get(bone.getName());
                if (target != null && difference(part.getSkeleton().getBindingAbsolute(bone).transform(),
                        body.getSkeleton().getBindingAbsolute(target).transform()) <= 1e-4f)
                    config.mapBone(bone, target.getName());
            }
        });
        return builder.build();
    }
    private static volatile Object consumed;
    private static Map<String, Object> benchmark(String name, Supplier<ModelAssembly.Request> requests,
                                               Asset body, Asset part, boolean partialMapping, int count) {
        var result = new LinkedHashMap<String, Object>();
        result.put("case", name); result.put("body", body.path.toString()); result.put("part", part.path.toString());
        result.put("partCount", count); result.put("partialMapping", partialMapping); result.put("parsingExcluded", true);
        var request = requests.get();
        var assembler = ModelAssembler.standard();
        var cache = new ModelAssemblyCache(8, 2_000_000);
        long first = System.nanoTime(); var merged = cache.getOrAssemble(request);
        result.put("firstAssemblyMillis", (System.nanoTime() - first) / 1e6);
        result.put("vertices", merged.model().getVertices().length); result.put("faces", merged.model().getMeshes().length);
        result.put("bones", merged.model().getBones().length); result.put("sourceBoneTotal", body.model.getBones().length + count * part.model.getBones().length);
        checkGeometry(merged);
        for (int i = 0; i < 4; i++) consumed = assembler.assemble(request);
        for (int i = 0; i < 500; i++) consumed = cache.getOrAssemble(request);
        Runnable cached = () -> {
            result.put("cachedRequest", measure(12, 2000, () -> cache.getOrAssemble(request)));
            result.put("buildRequestAndCache", measure(8, count >= 100 ? 25 : 100, () -> cache.getOrAssemble(requests.get())));
        };
        if (cachedFirst) cached.run();
        result.put("uncachedAssembly", measure(8, 1, () -> assembler.assemble(request)));
        if (!cachedFirst) cached.run();
        if (cache.stats().builds() != 1 || cache.getOrAssemble(request).model() != merged.model())
            throw new IllegalStateException("Cache did not share its model");
        result.put("cacheBeforeInvalidation", cache.stats());
        if (cache.invalidate(part.model) != 1) throw new IllegalStateException("Source invalidation did not evict the outfit");
        if (cache.getOrAssemble(request).model() == merged.model()) throw new IllegalStateException("Source invalidation reused stale geometry");
        result.put("cacheAfterInvalidation", cache.stats());
        consumed = null;
        System.out.println("BENCHMARK " + name + " vertices=" + merged.model().getVertices().length + " builds=" + cache.stats().builds());
        return result;
    }
    private static Map<String, Object> measure(int batches, int iterations, Supplier<?> operation) {
        ThreadMXBean allocation = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean mx && mx.isThreadAllocatedMemorySupported() ? mx : null;
        if (allocation != null && !allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        double[] times = new double[batches]; long bytes = 0;
        for (int i = 0; i < batches; i++) {
            long before = allocation == null ? 0 : allocation.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            for (int j = 0; j < iterations; j++) consumed = operation.get();
            times[i] = (System.nanoTime() - start) / (double) iterations / 1000;
            if (allocation != null) bytes += allocation.getThreadAllocatedBytes(thread) - before;
        }
        Arrays.sort(times);
        var stats = new LinkedHashMap<String, Object>();
        stats.put("batchMeanP50Micros", times[batches / 2]); stats.put("batchMeanP95Micros", times[Math.min(batches - 1, (int) Math.ceil(batches * .95) - 1)]);
        stats.put("samples", batches * iterations);
        if (allocation != null) stats.put("allocatedBytesPerOp", bytes / (double) (batches * iterations));
        return stats;
    }
    private static void checkGeometry(ModelAssembly merged) {
        ModelInstance output = new ModelInstance(merged.model(), null, null, null, null, null); output.update();
        int checked = 0;
        for (var mapping : merged.parts().values()) {
            ModelInstance source = new ModelInstance(mapping.source(), null, null, null, null, null); source.update();
            int stride = Math.max(1, mapping.source().getVertices().length / 128);
            for (int i = 0; i < mapping.source().getVertices().length; i += stride) {
                var vertex = mapping.source().getVertices()[i]; var mapped = mapping.vertex(vertex);
                Vector3f a = skin(source, vertex);
                Vector3f b = skin(output, mapped);
                if (a.distance(b) > 1e-4f) throw new IllegalStateException("Assembly changed neutral skinned position: " + mapping.id());
                checked++;
            }
            source.close();
        }
        output.close();
        if (checked == 0) throw new IllegalStateException("No assembled vertices verified");
    }
    private static Vector3f skin(ModelInstance instance, lib.kasuga.rendering.models.uml.structure.basic.Vertex vertex) {
        List<BoneContext> contexts = new ArrayList<>();
        instance.getSkeletonInstance().collectBoneContexts(contexts, vertex);
        return vertex.getBinding().getFunc().apply(vertex, contexts).getPosition();
    }
    static float difference(Matrix4f a, Matrix4f b) {
        float maximum = 0;
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) maximum = Math.max(maximum, Math.abs(a.get(c, r) - b.get(c, r)));
        return maximum;
    }
    public static Model loadCompatibleOutfit(Path root) throws Exception {
        Path body, dress;
        try (var paths = Files.walk(root.resolve("pack_7"))) { body = paths.filter(p -> p.toString().endsWith(".pmx")).sorted().findFirst().orElseThrow(); }
        try (var paths = Files.walk(root.resolve("pack_3"))) { dress = paths.filter(p -> p.toString().endsWith(".pmx")).sorted().findFirst().orElseThrow(); }
        return new ModelAssemblyBuilder("body", PmxOverlayFixtures.read(body), 1)
                .part("garment", PmxOverlayFixtures.read(dress), 1, part -> part.includeDynamics(false)).assemble().model();
    }
}
