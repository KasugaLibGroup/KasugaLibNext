package lib.kasuga.compat.iris;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Isolates Iris camera hooks behind bytecode checks for the Iris and Sodium ABIs. */
public final class IrisCameraMixinPlugin implements IMixinConfigPlugin {
    private static final String IRIS = "net/irisshaders/iris/";
    private static final String CAPTURED = IRIS + "uniforms/CapturedRenderingState.class";
    private static final String TIME = IRIS + "uniforms/SystemTimeUniforms.class";
    private static final String COUNTER = IRIS + "uniforms/SystemTimeUniforms$FrameCounter.class";
    private static final String SHADOW = IRIS + "shadows/ShadowRenderer.class";
    private static final String IMMEDIATE = IRIS + "vertices/ImmediateState.class";
    private static final String SODIUM_CHUNK = "net/caffeinemc/mods/sodium/client/render/chunk/";
    private static volatile Boolean cameraSupport;

    /** True only when every target and member required by the camera hooks is present. */
    public static boolean supportsCamera() {
        Boolean known = cameraSupport;
        if (known != null) return known;
        synchronized (IrisCameraMixinPlugin.class) {
            if (cameraSupport == null) cameraSupport = inspectCameraAbi();
            return cameraSupport;
        }
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override public List<String> getMixins() {
        if (MixinEnvironment.getCurrentEnvironment().getSide() != MixinEnvironment.Side.CLIENT) return List.of();
        if (!supportsCamera()) return List.of();
        List<String> mixins = new ArrayList<>(List.of("WorldViewIrisMixin", "WorldViewIrisStateAccessor",
                "WorldViewIrisTimeAccessor", "WorldViewIrisCounterAccessor", "WorldViewIrisMaterialsMixin",
                "WorldViewIrisChunkJobMixin"));
        if (hasCloudCameraAbi()) mixins.add("WorldViewIrisCloudMixin");
        return mixins;
    }

    private static boolean inspectCameraAbi() {
        ClassNode iris = readOptionalClass(IRIS + "Iris.class");
        if (iris == null) return false;
        ClassNode captured = readOptionalClass(CAPTURED);
        ClassNode time = readOptionalClass(TIME);
        ClassNode counter = readOptionalClass(COUNTER);
        ClassNode shadow = readOptionalClass(SHADOW);
        ClassNode immediate = readOptionalClass(IMMEDIATE);
        if (captured == null || time == null || counter == null || shadow == null || immediate == null) return false;
        if (!hasMethod(iris, "getPipelineManager", "()Lnet/irisshaders/iris/pipeline/PipelineManager;", Opcodes.ACC_STATIC)
                || !hasMethod(iris, "getCurrentPack", "()Ljava/util/Optional;", Opcodes.ACC_STATIC)
                || !hasMethod(iris, "getCurrentPackName", "()Ljava/lang/String;", Opcodes.ACC_STATIC)
                || !hasMethod(iris, "isFallback", "()Z", Opcodes.ACC_STATIC)) return false;
        if (!hasField(captured, "INSTANCE", "Lnet/irisshaders/iris/uniforms/CapturedRenderingState;", Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
                || !hasMethod(captured, "<init>", "()V", 0)) return false;
        if (!hasField(time, "TIMER", "Lnet/irisshaders/iris/uniforms/SystemTimeUniforms$Timer;", Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
                || !hasField(time, "COUNTER", "Lnet/irisshaders/iris/uniforms/SystemTimeUniforms$FrameCounter;", Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
                || !hasMethod(counter, "<init>", "()V", 0)) return false;
        if (!hasField(shadow, "ACTIVE", "Z", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                || !hasField(shadow, "visibleBlockEntities", "Ljava/util/List;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                || !hasField(shadow, "renderDistance", "I", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                || !hasField(shadow, "MODELVIEW", "Lorg/joml/Matrix4f;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                || !hasField(shadow, "PROJECTION", "Lorg/joml/Matrix4f;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                || !hasField(shadow, "FRUSTUM", "Lnet/minecraft/client/renderer/culling/Frustum;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) return false;
        return hasField(immediate, "isRenderingLevel", "Z", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                && hasField(immediate, "usingTessellation", "Z", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                && hasField(immediate, "renderWithExtendedVertexFormat", "Z", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                && hasField(immediate, "bypass", "Z", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                && hasField(immediate, "mergeRendering", "Z", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                && hasMaterialRoutingAbi()
                && hasSessionAbi();
    }

    private static boolean hasMaterialRoutingAbi() {
        ClassNode settings = readOptionalClass(IRIS + "shaderpack/materialmap/WorldRenderingSettings.class");
        ClassNode job = readOptionalClass(SODIUM_CHUNK + "compile/executor/ChunkJobTyped.class");
        if (settings == null || job == null
                || !hasMethod(job, "execute", "(L" + SODIUM_CHUNK + "compile/ChunkBuildContext;)V", Opcodes.ACC_PUBLIC)) {
            return false;
        }
        String ids = "Lit/unimi/dsi/fastutil/objects/Object2IntFunction;";
        return hasMethod(settings, "<init>", "()V", Opcodes.ACC_PUBLIC)
                && hasProperty(settings, "getBlockStateIds", "setBlockStateIds", "Lit/unimi/dsi/fastutil/objects/Object2IntMap;")
                && hasProperty(settings, "getBlockTypeIds", "setBlockTypeIds", "Ljava/util/Map;")
                && hasProperty(settings, "getEntityIds", "setEntityIds", ids)
                && hasProperty(settings, "getItemIds", "setItemIds", ids)
                && hasProperty(settings, "getAmbientOcclusionLevel", "setAmbientOcclusionLevel", "F")
                && hasProperty(settings, "shouldDisableDirectionalShading", "setDisableDirectionalShading", "Z")
                && hasProperty(settings, "shouldUseSeparateAo", "setUseSeparateAo", "Z")
                && hasProperty(settings, "getVertexFormat", "setVertexFormat", "L" + SODIUM_CHUNK + "vertex/format/ChunkVertexType;")
                && hasProperty(settings, "shouldVoxelizeLightBlocks", "setVoxelizeLightBlocks", "Z")
                && hasProperty(settings, "shouldSeparateEntityDraws", "setSeparateEntityDraws", "Z")
                && hasMethod(settings, "hasVillagerConversionId", "()Z", Opcodes.ACC_PUBLIC)
                && hasMethod(settings, "isReloadRequired", "()Z", Opcodes.ACC_PUBLIC)
                && hasMethod(settings, "clearReloadRequired", "()V", Opcodes.ACC_PUBLIC);
    }

    private static boolean hasProperty(ClassNode node, String getter, String setter, String descriptor) {
        return hasMethod(node, getter, "()" + descriptor, Opcodes.ACC_PUBLIC)
                && hasMethod(node, setter, "(" + descriptor + ")V", Opcodes.ACC_PUBLIC);
    }

    private static boolean hasSessionAbi() {
        ClassNode pipelines = readOptionalClass(IRIS + "pipeline/PipelineManager.class");
        ClassNode world = readOptionalClass(IRIS + "pipeline/WorldRenderingPipeline.class");
        ClassNode irisPipeline = readOptionalClass(IRIS + "pipeline/IrisRenderingPipeline.class");
        ClassNode vanillaPipeline = readOptionalClass(IRIS + "pipeline/VanillaRenderingPipeline.class");
        ClassNode pack = readOptionalClass(IRIS + "shaderpack/ShaderPack.class");
        ClassNode macros = readOptionalClass(IRIS + "gl/shader/StandardMacros.class");
        ClassNode uniforms = readOptionalClass(IRIS + "gl/program/ProgramUniforms.class");
        ClassNode timer = readOptionalClass(IRIS + "uniforms/SystemTimeUniforms$Timer.class");
        ClassNode counter = readOptionalClass(COUNTER);
        String programSet = "L" + IRIS + "shaderpack/programs/ProgramSet;";
        int publicStatic = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        return hasMethod(pipelines, "<init>", "(Ljava/util/function/Function;)V", Opcodes.ACC_PUBLIC)
                && hasMethod(pipelines, "getPipeline", "()Ljava/util/Optional;", Opcodes.ACC_PUBLIC)
                && hasMethod(pipelines, "destroyPipeline", "()V", Opcodes.ACC_PUBLIC)
                && hasMethod(world, "finalizeGameRendering", "()V", Opcodes.ACC_PUBLIC)
                && hasMethod(irisPipeline, "<init>", "(" + programSet + ")V", Opcodes.ACC_PUBLIC)
                && hasMethod(vanillaPipeline, "<init>", "()V", Opcodes.ACC_PUBLIC)
                && hasMethod(pack, "<init>", "(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V",
                        Opcodes.ACC_PUBLIC)
                && hasMethod(pack, "getProgramSet", "(L" + IRIS + "shaderpack/materialmap/NamespacedId;)" + programSet,
                        Opcodes.ACC_PUBLIC)
                && hasMethod(macros, "createStandardEnvironmentDefines", "()Lcom/google/common/collect/ImmutableList;", publicStatic)
                && hasMethod(uniforms, "clearActiveUniforms", "()V", publicStatic)
                && hasMethod(timer, "<init>", "()V", Opcodes.ACC_PUBLIC)
                && hasMethod(timer, "beginFrame", "(J)V", Opcodes.ACC_PUBLIC)
                && hasMethod(counter, "beginFrame", "()V", Opcodes.ACC_PUBLIC);
    }

    /** Keep the legacy Iris 1.8.12 cloud hook independent of the camera ABI gate. */
    private static boolean hasCloudCameraAbi() {
        ClassNode irisCloud = readOptionalClass(IRIS + "compat/sodium/mixin/MixinCloudRenderer.class");
        ClassNode sodiumCloud = readOptionalClass("net/caffeinemc/mods/sodium/client/render/immediate/CloudRenderer.class");
        if (irisCloud == null || sodiumCloud == null) return false;
        boolean flag = false;
        for (FieldNode field : irisCloud.fields) {
            if (field.name.equals("hadShadersOn") && field.desc.equals("Z") && (field.access & Opcodes.ACC_STATIC) != 0) flag = true;
        }
        return flag && hasMethod(sodiumCloud, "render", "(Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;"
                + "Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", 0);
    }

    private static boolean hasMethod(ClassNode node, String name, String descriptor, int requiredAccess) {
        if (node == null) return false;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)
                    && (method.access & requiredAccess) == requiredAccess) return true;
        }
        return false;
    }

    private static boolean hasField(ClassNode node, String name, String descriptor, int requiredAccess) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(descriptor)
                    && (field.access & requiredAccess) == requiredAccess) return true;
        }
        return false;
    }

    private static ClassNode readOptionalClass(String resourceName) {
        try (InputStream resource = MixinService.getService().getResourceAsStream(resourceName)) {
            if (resource == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(resource).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException | IllegalArgumentException unreadable) {
            return null;
        }
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
