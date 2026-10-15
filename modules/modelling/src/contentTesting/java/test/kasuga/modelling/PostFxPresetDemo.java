package test.kasuga.modelling;

import com.mojang.blaze3d.pipeline.RenderTarget;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraph;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphPassDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphTarget;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import lib.kasuga.shader.FloatExpr;
import lib.kasuga.shader.ShaderParameter;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.Vec2Expr;
import lib.kasuga.shader.Vec3Expr;
import lib.kasuga.shader.Vec4Expr;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Concrete PostFX presets sharing one generated color-grading shader. The named looks demonstrate
 * that a preset can be only parameter data while the reload-safe shader and graph stay registered.
 */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class PostFxPresetDemo {
    private static final ResourceLocation GRAPH_ID = id("preset/postfx_graph");
    private static final ResourceLocation SCENE_TARGET_ID = id("preset/postfx_scene");
    private static final ResourceLocation CAPTURE_PASS_ID = id("preset/postfx_capture");
    private static final ResourceLocation FILTER_PASS_ID = id("preset/postfx_filter");
    private static final PostProcessGraphTarget MAIN = PostProcessGraphTarget.main();
    private static final PostProcessGraphTarget SCENE =
            PostProcessGraphTarget.managed(SCENE_TARGET_ID);

    private static final ShaderParameter INTENSITY = ShaderParameter.floatParameter(
            "PresetIntensity", "Blend amount between the original scene and the preset", 0.85f, 0.0f, 1.0f
    );
    private static final ShaderParameter EXPOSURE = ShaderParameter.floatParameter(
            "PresetExposure", "Linear exposure multiplier", 1.02f, 0.0f, 2.0f
    );
    private static final ShaderParameter SATURATION = ShaderParameter.floatParameter(
            "PresetSaturation", "Color saturation multiplier", 0.88f, 0.0f, 2.0f
    );
    private static final ShaderParameter WARMTH = ShaderParameter.floatParameter(
            "PresetWarmth", "Warm or cool color balance", 0.18f, -1.0f, 1.0f
    );
    private static final ShaderParameter VIGNETTE = ShaderParameter.floatParameter(
            "PresetVignette", "Strength of edge darkening", 0.28f, 0.0f, 1.0f
    );

    private static volatile boolean enabled;
    private static volatile Look activeLook = Look.OFF;
    private static RenderShaderHandle shader;

    private PostFxPresetDemo() {
    }

    @SubscribeEvent
    public static void registerPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipelineRegistrar registrar = event.registrar(id("preset/postfx"));
        shader = registrar.shader(program()).handle();
        registrar.graph(createGraph(shader), 900);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_postfx")
                .then(Commands.literal("cinematic").executes(context ->
                        apply(context.getSource()::sendSuccess, Look.CINEMATIC)))
                .then(Commands.literal("noir").executes(context ->
                        apply(context.getSource()::sendSuccess, Look.NOIR)))
                .then(Commands.literal("vivid").executes(context ->
                        apply(context.getSource()::sendSuccess, Look.VIVID)))
                .then(Commands.literal("off").executes(context -> {
                    enabled = false;
                    activeLook = Look.OFF;
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga PostFX preset disabled"), false
                    );
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    boolean ready = shader != null && shader.isReady();
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Kasuga PostFX: shader=" + (ready ? "ready" : "waiting")
                                    + ", preset=" + activeLook.commandName
                    ), false);
                    return ready ? 1 : 0;
                })));
    }

    private static int apply(SuccessSender sender, Look look) {
        if (shader == null) return 0;
        shader.parameters().setFloat(INTENSITY, look.intensity);
        shader.parameters().setFloat(EXPOSURE, look.exposure);
        shader.parameters().setFloat(SATURATION, look.saturation);
        shader.parameters().setFloat(WARMTH, look.warmth);
        shader.parameters().setFloat(VIGNETTE, look.vignette);
        activeLook = look;
        enabled = true;
        sender.send(
                () -> Component.literal("Kasuga PostFX preset: " + look.commandName),
                false
        );
        return 1;
    }

    private static PostProcessGraph createGraph(RenderShaderHandle handle) {
        PostProcessTargetDescriptor scene = PostProcessTargetDescriptor.builder(SCENE_TARGET_ID)
                .screenScale(1.0f)
                .filter(PostProcessTargetDescriptor.TextureFilter.LINEAR)
                .build();

        return PostProcessGraph.builder(GRAPH_ID)
                .target(scene)
                .prepare((context, frame) -> enabled && handle.isReady())
                .pass(PostProcessGraphPassDescriptor.builder(CAPTURE_PASS_ID, context ->
                                context.copyColor(MAIN, SCENE, true))
                        .reads(MAIN)
                        .writes(SCENE)
                        .build())
                .pass(PostProcessGraphPassDescriptor.builder(FILTER_PASS_ID, context -> {
                            RenderTarget sceneTarget = context.read(SCENE);
                            context.fullscreen(MAIN, handle)
                                    .colorSampler("SceneSampler", sceneTarget)
                                    .draw(false);
                        })
                        .reads(SCENE)
                        .writes(MAIN)
                        .build())
                .build();
    }

    private static ShaderProgram program() {
        return ShaderProgram.fullscreen("kasuga_demo:preset/postfx_color_grade", fragment -> {
            Vec2Expr uv = fragment.texCoord();
            Vec4Expr sample = fragment.sampler2D("SceneSampler").sample(uv);
            Vec3Expr original = sample.rgb();
            FloatExpr intensity = fragment.exposeFloat(INTENSITY);
            FloatExpr exposure = fragment.exposeFloat(EXPOSURE);
            FloatExpr saturation = fragment.exposeFloat(SATURATION);
            FloatExpr warmth = fragment.exposeFloat(WARMTH);
            FloatExpr vignette = fragment.exposeFloat(VIGNETTE);
            FloatExpr one = fragment.f32(1.0f);

            FloatExpr luminance = original.x().mul(0.2126f)
                    .add(original.y().mul(0.7152f))
                    .add(original.z().mul(0.0722f));
            Vec3Expr gray = fragment.vec3(luminance, luminance, luminance);
            Vec3Expr saturated = gray.mul(one.sub(saturation)).add(original.mul(saturation));
            Vec3Expr temperature = fragment.vec3(
                    one.add(warmth.mul(0.12f)),
                    one.add(warmth.mul(0.02f)),
                    one.sub(warmth.mul(0.10f))
            );
            Vec3Expr graded = saturated.mul(temperature).mul(exposure);

            FloatExpr edge = uv.sub(fragment.vec2(0.5f, 0.5f))
                    .length()
                    .mul(1.4142135f)
                    .smoothstep(fragment.f32(0.25f), fragment.f32(0.92f));
            FloatExpr vignetteFactor = one.sub(edge.mul(vignette));
            graded = graded.mul(vignetteFactor);

            Vec3Expr output = original.mul(one.sub(intensity)).add(graded.mul(intensity));
            fragment.fragmentColor(fragment.vec4(output, sample.a()));
        });
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_demo", path);
    }

    @FunctionalInterface
    private interface SuccessSender {
        void send(java.util.function.Supplier<Component> message, boolean broadcast);
    }

    private enum Look {
        OFF("off", 0.0f, 1.0f, 1.0f, 0.0f, 0.0f),
        CINEMATIC("cinematic", 0.85f, 1.02f, 0.88f, 0.18f, 0.28f),
        NOIR("noir", 1.0f, 1.08f, 0.0f, -0.08f, 0.42f),
        VIVID("vivid", 0.78f, 1.06f, 1.42f, 0.08f, 0.14f);

        private final String commandName;
        private final float intensity;
        private final float exposure;
        private final float saturation;
        private final float warmth;
        private final float vignette;

        Look(String commandName, float intensity, float exposure, float saturation,
             float warmth, float vignette) {
            this.commandName = commandName;
            this.intensity = intensity;
            this.exposure = exposure;
            this.saturation = saturation;
            this.warmth = warmth;
            this.vignette = vignette;
        }
    }
}
