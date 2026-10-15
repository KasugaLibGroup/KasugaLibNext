package lib.kasuga.rendering.models.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.KasugaLib;
import lib.kasuga.client.loading.LoadingIndicator;
import lib.kasuga.mixins.client.AccessorOnRegisterRenderTypesEvent;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.WorldRenderPipelineDispatcher;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.RenderPipelineScope;
import lib.kasuga.rendering.effect.builtin.BillboardEffects;
import lib.kasuga.rendering.effect.builtin.blackhole.BlackHoleEffects;
import lib.kasuga.rendering.effect.debug.EffectDiagnosticsScreen;
import lib.kasuga.rendering.effect.debug.ShaderParameterCommands;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderRegistry;
import lib.kasuga.rendering.effect.shader.ShaderPreparationScheduler;
import lib.kasuga.rendering.effect.shader.ShaderParameterPersistence;
import lib.kasuga.rendering.models.mc.backend.*;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaShaderInstance;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaGlobalBatchShaderInstance;
import lib.kasuga.rendering.models.mc.backend.data_type.OitCompositeShaderInstance;
import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.mc.source.model.*;
import lib.kasuga.rendering.models.mc.source.texture.BufferedImageTextureSource;
import lib.kasuga.rendering.models.mc.source.texture.CombinedTextureManager;
import lib.kasuga.rendering.models.mc.source.texture.FileTextureSource;
import lib.kasuga.rendering.models.mc.source.texture.JarTextureSource;
import lib.kasuga.rendering.models.mc.source.texture.bake.PbrBakeCoordinator;
import lib.kasuga.rendering.models.mc.source.texture.bake.PbrBakeState;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.*;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.RenderTypeGroup;
import net.neoforged.neoforge.client.event.*;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static lib.kasuga.rendering.models.mc.backend.RenderState.UML_VERTEX_FORMAT;

@EventBusSubscriber(value = Dist.CLIENT)
public class Constants {

    public static CombinedTextureManager TEXTURE_BASIC;
    public static SourceType TEXTURE_TYPE, MODEL_TYPE;
    public static UIBackend UI_BACKEND;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RenderPipelineScope CLIENT_RENDER_PIPELINES = RenderPipelineScope.create(
            ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "client_render_lifetime")
    );
    private static boolean renderPipelineRegistrationPosted;

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ShaderPreparationScheduler.configureFromSystemProperty();
        ShaderPreparationScheduler.prestartWorkers();
        ShaderParameterPersistence.initialize();
        if (UI_BACKEND == null) {
            UI_BACKEND = new UIBackend();
        }
        if (!renderPipelineRegistrationPosted) {
            renderPipelineRegistrationPosted = true;
            ModLoader.postEvent(new RegisterRenderPipelinesEvent(CLIENT_RENDER_PIPELINES));
        }
    }

    @SubscribeEvent
    public static void onRegisterRenderPipelines(RegisterRenderPipelinesEvent event) {
        RenderPipelineRegistrar pipelines = event.registrar(
                ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "builtin_rendering")
        );
        BillboardEffects.initialize(pipelines);
        BlackHoleEffects.initialize(pipelines);
        lib.kasuga.rendering.effect.builtin.cloud.CloudEffects.initialize(pipelines);
        RenderPipelineDescriptor opaque = RenderPipelineDescriptor.builder(
                        ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "models_opaque"),
                        RenderPhase.AFTER_ENTITIES)
                .priority(0)
                .build();
        RenderPipelineDescriptor mask = RenderPipelineDescriptor.builder(
                        ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "models_mask"),
                        RenderPhase.AFTER_ENTITIES)
                .priority(1)
                .build();
        RenderPipelineDescriptor translucent = RenderPipelineDescriptor.builder(
                        ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "models_translucent"),
                        RenderPhase.AFTER_TRANSLUCENT_BLOCKS)
                .priority(0)
                .build();
        pipelines.world(opaque, context -> renderModels(context, ModelRenderPass.OPAQUE, true, false));
        pipelines.world(mask, context -> renderModels(context, ModelRenderPass.MASK, false, false));
        pipelines.world(translucent, context -> renderModels(context, ModelRenderPass.TRANSLUCENT, false, true));
    }

    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public static void onReloadListenerRegister(RegisterClientReloadListenersEvent event) {
        IrisCompat.onStart();
        TEXTURE_TYPE = new SourceType("texture");
        MODEL_TYPE = new SourceType("model");

        CombinedTextureManager basic = new CombinedTextureManager(
                TEXTURE_TYPE, "mc_layer_0",
                Minecraft.getInstance().getTextureManager(),
                RenderState.KSG_LAYER_0, null,
                RenderState.KSG_NORMAL_MAP, rl -> RenderState.createDefaultSprite(rl,
                () -> RenderState.getSpecularMapDefaultImage(16 ,16)),
                (rl, w, h) -> RenderState.createDefaultSprite(rl,
                        () -> RenderState.getNormalMapDefaultImage(w, h)),
                RenderState.KSG_METALLIC_MAP, rl -> RenderState.createDefaultSprite(rl,
                        () -> RenderState.getSpecularMapDefaultImage(16 ,16)),
                (rl, w, h) -> RenderState.createDefaultSprite(rl,
                        () -> RenderState.getSpecularMapDefaultImage(w, h))
        );

        FileTextureSource fileTextureSource = new FileTextureSource("file");
        JarTextureSource jarTextureSource = new JarTextureSource("jar");
        BufferedImageTextureSource bufferedImageTextureSource = new BufferedImageTextureSource("buffered_image");

        basic.registerSource(fileTextureSource);
        basic.registerSource(jarTextureSource);
        basic.registerSource(bufferedImageTextureSource);

        TEXTURE_BASIC = basic;

        PipelineRegistry.registerBuiltins(basic);

        KasugaPipeLineRouter router = new KasugaPipeLineRouter();
        PipelineRegistry.registerDefaultRoutes(router);

        KasugaModelManager modelManager = new KasugaModelManager(List.of(basic));
        modelManager.registerRouter(router);
        modelManager.registerModelScanner(new KasugaModelScanner());

        event.registerReloadListener(modelManager);
    }

    @SubscribeEvent
    public static void onShaderRegister(RegisterShadersEvent event) {
        ResourceProvider provider = event.getResourceProvider();
        try {
            ShaderInstance shaderInstance = new KasugaShaderInstance(
                    provider, ResourceLocation.tryBuild("kasuga_lib", "ksglib_main"),
                    UML_VERTEX_FORMAT
            );
            event.registerShader(shaderInstance, instance -> RenderState.UML_SHADER_INSTANCE = instance);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shader 'ksglib_main'", e);
        }
        try {
            ShaderInstance shaderInstance = new KasugaGlobalBatchShaderInstance(
                    provider, ResourceLocation.tryBuild("kasuga_lib", "ksglib_global_batch"),
                    UML_VERTEX_FORMAT
            );
            event.registerShader(shaderInstance,
                    instance -> RenderState.GLOBAL_BATCH_SHADER_INSTANCE = instance);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shader 'ksglib_global_batch'", e);
        }
        try {
            // Iris normally suppresses unknown core shaders while a shader
            // pack owns world rendering. Register this one narrow exception:
            // the OIT resolve explicitly targets Iris' active world buffer.
            IrisCompat.allowOitCompositeShader();
            ShaderInstance shaderInstance = new OitCompositeShaderInstance(
                    provider, ResourceLocation.tryBuild("kasuga_lib", "ksglib_oit_composite"),
                    DefaultVertexFormat.BLIT_SCREEN
            );
            event.registerShader(shaderInstance,
                    instance -> RenderState.OIT_COMPOSITE_SHADER_INSTANCE = instance);
        } catch (IOException e) {
            // OIT is an optional acceleration/quality path. Keep the ordinary
            // sorted translucent renderer usable when a resource pack or
            // platform rejects the resolve shader.
            LOGGER.warn("Weighted OIT composite shader is unavailable; using sorted translucent fallback", e);
        }
        RenderShaderRegistry.registerShaders(event);
    }

    @SubscribeEvent
    public static void onRegisterRenderBuffers(RegisterRenderBuffersEvent event) {
        RenderState.OPAQUE_RENDER_TYPE = createModelRenderType(
                "kasuga_lib:uml_opaque_render_type", RenderState.UML_SHADER, false);
        RenderState.CUTOUT_RENDER_TYPE = createModelRenderType(
                "kasuga_lib:uml_cutout_render_type", RenderState.UML_SHADER, false);
        RenderState.TRANSLUCENT_RENDER_TYPE = createModelRenderType(
                "kasuga_lib:uml_translucent_render_type", RenderState.UML_SHADER, true);

        RenderState.GLOBAL_OPAQUE_RENDER_TYPE = createModelRenderType(
                "kasuga_lib:uml_global_opaque_render_type", RenderState.GLOBAL_BATCH_SHADER, false);
        RenderState.GLOBAL_CUTOUT_RENDER_TYPE = createModelRenderType(
                "kasuga_lib:uml_global_cutout_render_type", RenderState.GLOBAL_BATCH_SHADER, false);
        RenderState.GLOBAL_TRANSLUCENT_RENDER_TYPE = createModelRenderType(
                "kasuga_lib:uml_global_translucent_render_type", RenderState.GLOBAL_BATCH_SHADER, true);

        RenderState.IRIS_OPAQUE_RENDER_TYPE = createIrisRenderType(
                "kasuga_lib:iris_opaque_render_type", RenderStateShard.RENDERTYPE_ENTITY_SOLID_SHADER, false);
        RenderState.IRIS_CUTOUT_RENDER_TYPE = createIrisRenderType(
                "kasuga_lib:iris_cutout_render_type", RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_SHADER, false);
        RenderState.IRIS_TRANSLUCENT_RENDER_TYPE = createIrisRenderType(
                "kasuga_lib:iris_translucent_render_type", RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER, true);

        RenderState.OIT_ACCUMULATION_RENDER_TYPE = createOitRenderType(
                "kasuga_lib:uml_oit_accumulation_render_type", RenderState.OIT_ACCUMULATION_TRANSPARENCY);
        RenderState.OIT_REVEALAGE_RENDER_TYPE = createOitRenderType(
                "kasuga_lib:uml_oit_revealage_render_type", RenderState.OIT_REVEALAGE_TRANSPARENCY);
        RenderState.IRIS_OIT_ACCUMULATION_RENDER_TYPE = createIrisOitRenderType(
                "kasuga_lib:iris_oit_accumulation_render_type", RenderState.OIT_ACCUMULATION_TRANSPARENCY);
        RenderState.IRIS_OIT_REVEALAGE_RENDER_TYPE = createIrisOitRenderType(
                "kasuga_lib:iris_oit_revealage_render_type", RenderState.OIT_REVEALAGE_TRANSPARENCY);

        // Compatibility aliases for integrations that still request the old
        // single render type. New model rendering uses pass-specific lookups.
        RenderState.RENDER_TYPE = RenderState.TRANSLUCENT_RENDER_TYPE;
        RenderState.GLOBAL_BATCH_RENDER_TYPE = RenderState.GLOBAL_TRANSLUCENT_RENDER_TYPE;
        RenderState.IRIS_COMPAT_RENDER_TYPE = RenderState.IRIS_TRANSLUCENT_RENDER_TYPE;

        event.registerRenderBuffer(RenderState.OPAQUE_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.CUTOUT_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.TRANSLUCENT_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.GLOBAL_OPAQUE_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.GLOBAL_CUTOUT_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.GLOBAL_TRANSLUCENT_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.IRIS_OPAQUE_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.IRIS_CUTOUT_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.IRIS_TRANSLUCENT_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.OIT_ACCUMULATION_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.OIT_REVEALAGE_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.IRIS_OIT_ACCUMULATION_RENDER_TYPE);
        event.registerRenderBuffer(RenderState.IRIS_OIT_REVEALAGE_RENDER_TYPE);
    }

    private static RenderType createModelRenderType(String name,
                                                     RenderStateShard.ShaderStateShard shader,
                                                     boolean translucent) {
        return RenderType.create(name, UML_VERTEX_FORMAT, VertexFormat.Mode.QUADS,
                64 * 1024 * 1024, true, translucent,
                RenderType.CompositeState.builder()
                        .setTextureState(RenderState.UML_TEXTURE_STATE)
                        .setShaderState(shader)
                        .setTransparencyState(translucent
                                ? RenderStateShard.TRANSLUCENT_TRANSPARENCY
                                : RenderStateShard.NO_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setCullState(RenderStateShard.CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .setTexturingState(RenderStateShard.DEFAULT_TEXTURING)
                        .setWriteMaskState(translucent
                                ? RenderStateShard.COLOR_WRITE
                                : RenderStateShard.COLOR_DEPTH_WRITE)
                        .setLineState(RenderStateShard.DEFAULT_LINE)
                        .setColorLogicState(RenderStateShard.NO_COLOR_LOGIC)
                        .createCompositeState(false));
    }

    private static RenderType createIrisRenderType(String name,
                                                    RenderStateShard.ShaderStateShard shader,
                                                    boolean translucent) {
        return RenderType.create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                64 * 1024 * 1024, true, translucent,
                RenderType.CompositeState.builder()
                        .setTextureState(RenderState.UML_TEXTURE_STATE)
                        .setShaderState(shader)
                        .setTransparencyState(translucent
                                ? RenderStateShard.TRANSLUCENT_TRANSPARENCY
                                : RenderStateShard.NO_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setCullState(RenderStateShard.CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .setTexturingState(RenderStateShard.DEFAULT_TEXTURING)
                        .setWriteMaskState(translucent
                                ? RenderStateShard.COLOR_WRITE
                                : RenderStateShard.COLOR_DEPTH_WRITE)
                        .setLineState(RenderStateShard.DEFAULT_LINE)
                        .setColorLogicState(RenderStateShard.NO_COLOR_LOGIC)
                        .createCompositeState(false));
    }

    private static RenderType createOitRenderType(
            String name, RenderStateShard.TransparencyStateShard transparency) {
        return RenderType.create(name, UML_VERTEX_FORMAT, VertexFormat.Mode.QUADS,
                64 * 1024 * 1024, true, false,
                RenderType.CompositeState.builder()
                        .setTextureState(RenderState.UML_TEXTURE_STATE)
                        .setShaderState(RenderState.UML_SHADER)
                        .setTransparencyState(transparency)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setCullState(RenderStateShard.CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setOutputState(RenderState.OIT_TARGET)
                        .setTexturingState(RenderStateShard.DEFAULT_TEXTURING)
                        // The copied scene depth is read-only for both OIT
                        // geometry passes; the color attachment remains writable.
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setLineState(RenderStateShard.DEFAULT_LINE)
                        .setColorLogicState(RenderStateShard.NO_COLOR_LOGIC)
                        .createCompositeState(false));
    }

    private static RenderType createIrisOitRenderType(
            String name, RenderStateShard.TransparencyStateShard transparency) {
        return RenderType.create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                64 * 1024 * 1024, true, false,
                RenderType.CompositeState.builder()
                        .setTextureState(RenderState.UML_TEXTURE_STATE)
                        .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTransparencyState(transparency)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setCullState(RenderStateShard.CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setOutputState(RenderState.OIT_TARGET)
                        .setTexturingState(RenderStateShard.DEFAULT_TEXTURING)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setLineState(RenderStateShard.DEFAULT_LINE)
                        .setColorLogicState(RenderStateShard.NO_COLOR_LOGIC)
                        .createCompositeState(false));
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_effects")
                .executes(context -> {
                    Minecraft.getInstance().execute(EffectDiagnosticsScreen::open);
                    return 1;
                })
                .then(Commands.literal("preload").executes(context -> {
                    int queued = RenderShaderRegistry.preloadPending();
                    context.getSource().sendSuccess(
                            () -> Component.literal("Queued " + queued + " Kasuga shaders for preload"),
                            false
                    );
                    return queued;
                }))
                .then(Commands.literal("workers")
                        .then(Commands.argument("count", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    int requested = IntegerArgumentType.getInteger(context, "count");
                                    ShaderPreparationScheduler.configureWorkers(requested);
                                    int workers = ShaderPreparationScheduler.workerCount();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Shader preparation workers: " + workers
                                                    + (requested == 0 ? " (automatic)" : " (requested "
                                                    + requested + ")")),
                                            false
                                    );
                                    return workers;
                                })))
                .then(ShaderParameterCommands.command()));
        event.getDispatcher().register(
                lib.kasuga.rendering.models.mc.dynamic.physics.PhysicsCommands.physicsCommand());
        event.getDispatcher().register(
                lib.kasuga.rendering.models.mc.dynamic.physics.PhysicsCommands.ragdollCommand());
        event.getDispatcher().register(Commands.literal("kasuga_pbr")
                .then(Commands.literal("status").executes(context -> {
                    Map<String, PbrBakeState> states = PbrBakeCoordinator.getInstance().states();
                    long ready = states.values().stream().filter(state -> state == PbrBakeState.READY).count();
                    long failed = states.values().stream().filter(state -> state == PbrBakeState.FAILED).count();
                    PbrBakeCoordinator.PbrBakeStats stats = PbrBakeCoordinator.getInstance().stats();
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Kasuga PBR: " + ready + " ready, " + failed + " failed, " + states.size()
                                    + " total; GPU=" + stats.gpuBakes() + ", CPU=" + stats.cpuBakes()
                                    + ", disk-cache=" + stats.cacheHits() + ", memory-cache=" + stats.memoryHits()
                                    + ", failed=" + stats.failures() + ", requests=" + stats.requests()
                                    + "; key=" + formatPbrMillis(stats.cacheKeyNanos())
                                    + ", read=" + formatPbrMillis(stats.diskReadNanos())
                                    + ", GPU-time=" + formatPbrMillis(stats.gpuBakeNanos())
                                    + ", CPU-time=" + formatPbrMillis(stats.cpuBakeNanos())
                                    + ", write=" + formatPbrMillis(stats.diskWriteNanos())
                    ), false);
                    return 1;
                }))
                .then(Commands.literal("rebake").executes(context -> {
                    try {
                        PbrBakeCoordinator.getInstance().clearCache();
                    } catch (IOException exception) {
                        context.getSource().sendFailure(Component.literal(
                                "Failed to clear Kasuga PBR cache: " + exception.getMessage()
                        ));
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Kasuga PBR cache cleared; reloading resources"
                    ), false);
                    Minecraft.getInstance().execute(() -> Minecraft.getInstance().reloadResourcePacks());
                    return 1;
                }))
                .then(Commands.literal("reload_config").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Reloading Kasuga PBR conversion config and resources"
                    ), false);
                    Minecraft.getInstance().execute(() -> Minecraft.getInstance().reloadResourcePacks());
                    return 1;
                })));
    }

    private static String formatPbrMillis(long nanos) {
        return String.format(Locale.ROOT, "%.1fms", nanos / 1_000_000.0);
    }

    @SubscribeEvent
    public static void onRenderTypeRegister(RegisterNamedRenderTypesEvent event) {
        AccessorOnRegisterRenderTypesEvent accessor = (AccessorOnRegisterRenderTypesEvent) event;
        accessor.getRenderTypes().put(
                RenderState.KSG_RENDER_TYPE, new RenderTypeGroup(
                        RenderState.OPAQUE_RENDER_TYPE,
                        RenderState.CUTOUT_RENDER_TYPE,
                        RenderState.TRANSLUCENT_RENDER_TYPE
                )
        );
        accessor.getRenderTypes().put(
                RenderState.KSG_IRIS_RENDER_TYPE, new RenderTypeGroup(
                        RenderState.IRIS_OPAQUE_RENDER_TYPE,
                        RenderState.IRIS_CUTOUT_RENDER_TYPE,
                        RenderState.IRIS_TRANSLUCENT_RENDER_TYPE
                )
        );

//        event.register(RenderState.KSG_RENDER_TYPE, RenderState.RENDER_TYPE, RenderState.RENDER_TYPE);
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        WorldRenderPipelineDispatcher.dispatch(event);
    }

    private static void renderModels(WorldRenderPipelineContext pipelineContext,
                                     ModelRenderPass pass, boolean frameStart, boolean frameEnd) {
        // Resource reload builds models before their new atlas sprites exist.
        // Keep the currently published generation untouched and skip custom
        // rendering until textures and models are atomically swapped.
        if (LoadingIndicator.snapshot().active()) return;
        MCBackend mcBackend = PipelineRegistry.backend();
        if (mcBackend == null) return;

        PoseStack poseStack = pipelineContext.poseStack();
        RenderBuffers renderBuffers = pipelineContext.renderBuffers();
        MultiBufferSource.BufferSource source = pipelineContext.bufferSource();
        RenderType renderType = RenderState.getRenderType(pass);
        VertexConsumer consumer = source.getBuffer(renderType);
        MCBackendContext context = new MCBackendContext(
                consumer, poseStack, renderBuffers,
                source, pipelineContext.camera(), pipelineContext.frustum(), pipelineContext.modelViewMatrix(),
                pipelineContext.projectionMatrix(), pipelineContext.renderTick(), pipelineContext.partialTick(),
                pipelineContext.level()
        );
        try {
            mcBackend.renderAllObjects(context, pass, frameStart, frameEnd);
        } finally {
            source.endBatch(renderType);
        }
    }

    public static void testUI() {
        if (UI_BACKEND.getRenderables().isEmpty()) {
            Screen screen = new AlertScreen(() -> {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("触发了按钮"), true
                );
            }, Component.literal("Test"), Component.literal("This is a test"));
            screen.init(Minecraft.getInstance(), Minecraft.getInstance().getWindow().getGuiScaledWidth(), Minecraft.getInstance().getWindow().getGuiScaledHeight());
            UI_BACKEND.addRenderable(screen);
        }
        GuiGraphics guiGraphics = UIBackend.constructGuiGraphics();
        UI_BACKEND.renderAllUis(guiGraphics, 0);
    }

}
