package lib.kasuga.rendering.models.mc.registry;

import com.google.gson.JsonObject;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.backend.BackendInstance;
import lib.kasuga.rendering.models.mc.backend.MCBackend;
import lib.kasuga.rendering.models.mc.backend.MCBridge;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be.BEModelLoader;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je.JEModelLoader;
import lib.kasuga.rendering.models.mc.source.model.KasugaPipeLineRouter;
import lib.kasuga.rendering.models.mc.source.model.json.FileJsonModelSource;
import lib.kasuga.rendering.models.mc.source.model.json.JarJsonModelSource;
import lib.kasuga.rendering.models.mc.source.model.json.JsonModelSourceManager;
import lib.kasuga.rendering.models.mc.source.model.str.FileStrModelSource;
import lib.kasuga.rendering.models.mc.source.model.str.JarStrModelSource;
import lib.kasuga.rendering.models.mc.source.model.str.StrModelSourceManager;
import lib.kasuga.rendering.models.mc.source.model.zip.FileZipModelSource;
import lib.kasuga.rendering.models.mc.source.model.zip.JarZipModelSource;
import lib.kasuga.rendering.models.mc.source.model.zip.ZipModelSourceManager;
import lib.kasuga.rendering.models.mc.source.model.binary.BinaryModelSourceManager;
import lib.kasuga.rendering.models.mc.source.model.binary.FileBinaryModelSource;
import lib.kasuga.rendering.models.mc.source.model.binary.JarBinaryModelSource;
import lib.kasuga.rendering.models.mc.source.texture.CombinedTextureManager;
import lib.kasuga.rendering.models.mc.typo.KsgObjLoader;
import lib.kasuga.rendering.models.mc.typo.KsgPmxLoader;
import lib.kasuga.rendering.models.mc.typo.KsgGltfLoader;
import lib.kasuga.rendering.models.mc.typo.bbmodel.KsgBbModelLoader;
import lib.kasuga.rendering.models.mc.typo.fmt.FmtArchiveLoader;
import lib.kasuga.rendering.models.mc.typo.fmt.FmtBinaryLoader;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipResource;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class PipelineRegistry {

    public static final String BE = "be";
    public static final String JE = "je";
    public static final String OBJ = "obj";
    public static final String PMX = "pmx";
    public static final String GLTF = "gltf";
    public static final String BBMODEL = "bbmodel";
    public static final String FMT_ARCHIVE = "fmt_archive";
    public static final String FMT_BINARY = "fmt_binary";

    private static final Map<String, ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?>> PIPELINES =
            new ConcurrentHashMap<>();

    private static final Map<String, String> BUILTIN_ROUTES = new LinkedHashMap<>();
    static {
        BUILTIN_ROUTES.put(".geo.json", BE);
        BUILTIN_ROUTES.put(".obj", OBJ);
        BUILTIN_ROUTES.put(".mmd.zip", PMX);
        BUILTIN_ROUTES.put(".glb", GLTF);
        BUILTIN_ROUTES.put(".gltf", GLTF);
        BUILTIN_ROUTES.put(".json", JE);
        BUILTIN_ROUTES.put(".bbmodel", BBMODEL);
        BUILTIN_ROUTES.put(".mtb", FMT_ARCHIVE);
        BUILTIN_ROUTES.put(".fmtb", FMT_ARCHIVE);
        BUILTIN_ROUTES.put(".bob", FMT_BINARY);
        BUILTIN_ROUTES.put(".beo", FMT_BINARY);
        BUILTIN_ROUTES.put(".fmf", FMT_BINARY);
    }

    private static KasugaPipeLineRouter router;

    private static MCBackend backend;
    private static KsgPmxLoader pmxLoader;
    private static KsgGltfLoader gltfLoader;

    private static ModelPipeLine<JsonObject, BackendInstance, ResourceLocation, ResourceLocation, String> bePipeline;
    private static ModelPipeLine<JsonObject, BackendInstance, ResourceLocation, ResourceLocation, String> jePipeline;
    private static ModelPipeLine<String, BackendInstance, ResourceLocation, ResourceLocation, String> objPipeline;
    private static ModelPipeLine<ZipHelper, BackendInstance, ResourceLocation, ResourceLocation, ZipResource> pmxPipeline;
    private static ModelPipeLine<byte[], BackendInstance, ResourceLocation, ResourceLocation, Object> gltfPipeline;
    private static ModelPipeLine<String, BackendInstance, ResourceLocation, ResourceLocation, Integer> bbmodelPipeline;
    private static ModelPipeLine<ZipHelper, BackendInstance, ResourceLocation, ResourceLocation, Integer> fmtArchivePipeline;
    private static ModelPipeLine<byte[], BackendInstance, ResourceLocation, ResourceLocation, Integer> fmtBinaryPipeline;

    private PipelineRegistry() {
    }

    public static void registerBuiltins(CombinedTextureManager textures) {
        JsonModelSourceManager jsonSource = new JsonModelSourceManager("json");
        StrModelSourceManager strSource = new StrModelSourceManager("str");
        ZipModelSourceManager zipSource = new ZipModelSourceManager("zip");
        BinaryModelSourceManager binarySource = new BinaryModelSourceManager("binary");

        jsonSource.registerSource(new FileJsonModelSource("file_json"));
        jsonSource.registerSource(new JarJsonModelSource("jar_json"));
        strSource.registerSource(new FileStrModelSource("file_str"));
        strSource.registerSource(new JarStrModelSource("jar_str"));
        zipSource.registerSource(new FileZipModelSource("file_zip"));
        zipSource.registerSource(new JarZipModelSource("jar_zip"));
        binarySource.registerSource(new FileBinaryModelSource("file_binary"));
        binarySource.registerSource(new JarBinaryModelSource("jar_binary"));

        MCBridge bridge = new MCBridge();
        backend = new MCBackend();

        bePipeline = new ModelPipeLine.Builder<JsonObject, BackendInstance, ResourceLocation,
                ResourceLocation, String>()
                .withModelSource(jsonSource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(new BEModelLoader("be_model", KasugaLib.MODID))
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(BE, bePipeline);

        jePipeline = new ModelPipeLine.Builder<JsonObject, BackendInstance, ResourceLocation,
                ResourceLocation, String>()
                .withModelSource(jsonSource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(new JEModelLoader("je_model"))
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(JE, jePipeline);

        objPipeline = new ModelPipeLine.Builder<String, BackendInstance, ResourceLocation,
                ResourceLocation, String>()
                .withModelSource(strSource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(new KsgObjLoader("obj_model"))
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(OBJ, objPipeline);

        pmxLoader = new KsgPmxLoader("pmx_model");
        pmxPipeline = new ModelPipeLine.Builder<ZipHelper, BackendInstance, ResourceLocation,
                ResourceLocation, ZipResource>()
                .withModelSource(zipSource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(pmxLoader)
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(PMX, pmxPipeline);

        gltfLoader = new KsgGltfLoader("gltf_model");
        gltfPipeline = new ModelPipeLine.Builder<byte[], BackendInstance, ResourceLocation,
                ResourceLocation, Object>()
                .withModelSource(binarySource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(gltfLoader)
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(GLTF, gltfPipeline);

        bbmodelPipeline = new ModelPipeLine.Builder<String, BackendInstance, ResourceLocation,
                ResourceLocation, Integer>()
                .withModelSource(strSource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(new KsgBbModelLoader("bbmodel"))
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(BBMODEL, bbmodelPipeline);

        fmtArchivePipeline = new ModelPipeLine.Builder<ZipHelper, BackendInstance, ResourceLocation,
                ResourceLocation, Integer>()
                .withModelSource(zipSource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(new FmtArchiveLoader("fmt_archive"))
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(FMT_ARCHIVE, fmtArchivePipeline);

        fmtBinaryPipeline = new ModelPipeLine.Builder<byte[], BackendInstance, ResourceLocation,
                ResourceLocation, Integer>()
                .withModelSource(binarySource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(new FmtBinaryLoader("fmt_binary"))
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(FMT_BINARY, fmtBinaryPipeline);
    }

    public static ModelPipeLine<JsonObject, BackendInstance, ResourceLocation, ResourceLocation, String> be() {
        return bePipeline;
    }

    public static ModelPipeLine<JsonObject, BackendInstance, ResourceLocation, ResourceLocation, String> je() {
        return jePipeline;
    }

    public static ModelPipeLine<String, BackendInstance, ResourceLocation, ResourceLocation, String> obj() {
        return objPipeline;
    }

    public static ModelPipeLine<ZipHelper, BackendInstance, ResourceLocation, ResourceLocation, ZipResource> pmx() {
        return pmxPipeline;
    }

    public static ModelPipeLine<byte[], BackendInstance, ResourceLocation, ResourceLocation, Object> gltf() {
        return gltfPipeline;
    }

    public static ModelPipeLine<String, BackendInstance, ResourceLocation, ResourceLocation, Integer> bbmodel() {
        return bbmodelPipeline;
    }

    public static ModelPipeLine<ZipHelper, BackendInstance, ResourceLocation, ResourceLocation, Integer> fmtArchive() {
        return fmtArchivePipeline;
    }

    public static ModelPipeLine<byte[], BackendInstance, ResourceLocation, ResourceLocation, Integer> fmtBinary() {
        return fmtBinaryPipeline;
    }

    public static MCBackend backend() {
        return backend;
    }

    public static KsgPmxLoader pmxLoader() {
        return pmxLoader;
    }

    public static KsgGltfLoader gltfLoader() {
        return gltfLoader;
    }

    public static void register(String id, ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pipeline, "pipeline");
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> previous = PIPELINES.putIfAbsent(id, pipeline);
        if (previous != null && previous != pipeline) {
            throw new IllegalStateException("Pipeline '" + id + "' is already registered to a different pipeline");
        }
    }

    @Nullable
    public static ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> get(String id) {
        if (id == null) {
            return null;
        }
        return PIPELINES.get(id);
    }

    public static boolean has(String id) {
        return id != null && PIPELINES.containsKey(id);
    }

    public static void registerDefaultRoutes(KasugaPipeLineRouter router) {
        PipelineRegistry.router = router;
        BUILTIN_ROUTES.forEach((extension, id) ->
                router.registerByExtension(extension, () -> get(id)));
    }

    public static void registerRoute(KasugaPipeLineRouter router, String extension, String id) {
        router.registerByExtension(extension, () -> get(id));
    }

    @Nullable
    public static ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> resolve(ResourceLocation modelKey) {
        if (router == null) {
            throw new IllegalStateException("KasugaPipeLineRouter is not initialized yet (registerDefaultRoutes)");
        }
        return router.resolve(modelKey);
    }
}
