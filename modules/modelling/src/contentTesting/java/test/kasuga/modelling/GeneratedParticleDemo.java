package test.kasuga.modelling;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.EffectRenderPipeline;
import lib.kasuga.rendering.effect.EffectRenderer;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.RenderEffect;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.PipelineBlendMode;
import lib.kasuga.rendering.effect.pipeline.PipelineCullMode;
import lib.kasuga.rendering.effect.pipeline.PipelineDepthTest;
import lib.kasuga.rendering.effect.pipeline.PipelineTarget;
import lib.kasuga.rendering.effect.pipeline.PipelineWriteMask;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import lib.kasuga.rendering.effect.shader.ShaderPreloadPolicy;
import lib.kasuga.shader.FloatExpr;
import lib.kasuga.shader.Mat4Expr;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.Vec2Expr;
import lib.kasuga.shader.Vec3Expr;
import lib.kasuga.shader.Vec4Expr;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.joml.Matrix4f;

import java.util.Random;

/** Development demo proving generated vertex/fragment graphics shaders in a world effect pipeline. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class GeneratedParticleDemo {
    private static final ResourceLocation PIPELINE_ID = id("generated_particles");
    private static final String SHADER_ID = "kasuga_demo:generated_particle";

    private static RenderShaderHandle shader;
    private static EffectRenderPipeline<DemoParticle> pipeline;
    private static ClientLevel activeLevel;
    private static int autoSpawnDelay;
    private static boolean autoSpawnHandled;

    /** Auto-spawn on world join is DISABLED by default — trigger manually via {@code /kasuga_particles burst}. */
    private static final boolean AUTO_SPAWN = false;

    private GeneratedParticleDemo() {
    }

    @SubscribeEvent
    public static void registerPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipelineRegistrar pipelines = event.registrar(id("generated_particle_demo"));
        shader = pipelines.shader(RenderShaderDescriptor.generated(
                particleProgram(), DefaultVertexFormat.POSITION_TEX_COLOR
        ).withPreload(ShaderPreloadPolicy.DEFERRED, 100)).handle();
        RenderPipelineDescriptor descriptor = RenderPipelineDescriptor.builder(
                        PIPELINE_ID, RenderPhase.AFTER_PARTICLES
                )
                .priority(200)
                .draw(draw -> draw
                        .vertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR)
                        .primitiveMode(VertexFormat.Mode.QUADS)
                        .bufferSize(4096)
                        .shader(shader)
                        .blend(PipelineBlendMode.ADDITIVE)
                        .depthTest(PipelineDepthTest.LEQUAL)
                        .cull(PipelineCullMode.DISABLED)
                        .writeMask(PipelineWriteMask.COLOR)
                        .target(PipelineTarget.PARTICLES))
                .build();
        pipeline = pipelines.effects(descriptor, false, new DemoParticleRenderer(shader));
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_particles")
                .then(Commands.literal("burst").executes(context -> {
                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player == null) {
                        context.getSource().sendFailure(Component.literal("Join a world before spawning particles"));
                        return 0;
                    }
                    if (shader == null || !shader.isReady()) {
                        context.getSource().sendFailure(Component.literal("Generated particle shader is not ready"));
                        return 0;
                    }
                    spawnBurst(player);
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga generated particle burst spawned"), false
                    );
                    return 1;
                }))
                .then(Commands.literal("clear").executes(context -> {
                    if (pipeline != null) pipeline.clear();
                    autoSpawnHandled = true;
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga generated particles cleared"), false
                    );
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    boolean ready = shader != null && shader.isReady();
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Kasuga particles: shader=" + (ready ? "ready" : "waiting")
                                    + ", generation=" + (shader == null ? 0 : shader.generation())
                                    + ", active=" + (pipeline == null ? 0 : pipeline.activeCount())
                    ), false);
                    return ready ? 1 : 0;
                })));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != activeLevel) {
            activeLevel = level;
            autoSpawnDelay = 45;
            autoSpawnHandled = false;
            return;
        }
        if (level == null || minecraft.player == null || minecraft.isPaused() || autoSpawnHandled) return;
        if (!AUTO_SPAWN) {
            autoSpawnHandled = true;
            return;
        }
        if (autoSpawnDelay-- > 0 || shader == null || !shader.isReady() || pipeline == null) return;
        autoSpawnHandled = true;
        spawnBurst(minecraft.player);
        minecraft.player.displayClientMessage(Component.literal(
                "Kasuga Java graphics-shader particles active; use /kasuga_particles burst or clear"
        ), false);
    }

    private static ShaderProgram particleProgram() {
        return ShaderProgram.graphics(
                SHADER_ID,
                vertex -> {
                    Vec3Expr position = vertex.inputVec3("Position");
                    Vec2Expr uv = vertex.inputVec2("UV0");
                    Vec4Expr color = vertex.inputVec4("Color");
                    Mat4Expr modelView = vertex.uniformMat4("ModelViewMat");
                    Mat4Expr projection = vertex.uniformMat4("ProjMat");
                    vertex.outputVec2("particleUv", uv);
                    vertex.outputVec4("particleColor", color);
                    vertex.position(projection.transform(modelView.transform(
                            vertex.vec4(position, vertex.f32(1))
                    )));
                },
                fragment -> {
                    Vec2Expr uv = fragment.inputVec2("particleUv");
                    Vec4Expr color = fragment.inputVec4("particleColor");
                    FloatExpr time = fragment.uniformFloat("Time", 0);
                    FloatExpr radius = uv.sub(fragment.vec2(0.5f, 0.5f)).length().mul(2);
                    FloatExpr falloff = fragment.f32(1).sub(
                            radius.smoothstep(fragment.f32(0.05f), fragment.f32(1))
                    );
                    FloatExpr pulse = time.mul(5).sin().mul(0.08f).add(0.92f);
                    FloatExpr intensity = falloff.mul(pulse);
                    fragment.fragmentColor(fragment.vec4(
                            color.rgb().mul(intensity), color.a().mul(intensity)
                    ));
                }
        );
    }

    private static void spawnBurst(LocalPlayer player) {
        if (pipeline == null) return;
        Vec3 forward = player.getLookAngle().normalize();
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(forward).normalize();
        Vec3 center = player.getEyePosition().add(forward.scale(8));
        Random random = new Random(0x4b41535547414cL ^ player.level().getGameTime());

        for (int index = 0; index < 96; index++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double ring = 0.4 + random.nextDouble() * 1.4;
            Vec3 radial = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            Vec3 position = center.add(radial.scale(ring)).add(forward.scale(random.nextGaussian() * 0.2));
            Vec3 velocity = radial.scale(0.018 + random.nextDouble() * 0.025)
                    .add(up.scale(0.006 + random.nextDouble() * 0.018))
                    .add(forward.scale(random.nextGaussian() * 0.006));
            float colorMix = index / 95.0f;
            pipeline.spawn(new DemoParticle(
                    position,
                    velocity,
                    35 + random.nextInt(35),
                    0.12f + random.nextFloat() * 0.22f,
                    new ParticleColor(
                            Mth.lerp(colorMix, 0.12f, 1.0f),
                            Mth.lerp(colorMix, 0.45f, 0.18f),
                            Mth.lerp(colorMix, 1.0f, 0.04f)
                    ),
                    random.nextFloat() * Mth.TWO_PI,
                    (random.nextFloat() - 0.5f) * 0.12f
            ));
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_demo", path);
    }

    private record ParticleColor(float red, float green, float blue) {
    }

    private static final class DemoParticle implements RenderEffect {
        private final int lifetime;
        private final float size;
        private final ParticleColor color;
        private final float rotation;
        private final float rotationSpeed;
        private Vec3 previousPosition;
        private Vec3 position;
        private Vec3 velocity;
        private int age;

        private DemoParticle(Vec3 position, Vec3 velocity, int lifetime, float size,
                             ParticleColor color, float rotation, float rotationSpeed) {
            this.previousPosition = position;
            this.position = position;
            this.velocity = velocity;
            this.lifetime = lifetime;
            this.size = size;
            this.color = color;
            this.rotation = rotation;
            this.rotationSpeed = rotationSpeed;
        }

        @Override
        public void tick(ClientLevel level) {
            previousPosition = position;
            position = position.add(velocity);
            velocity = velocity.scale(0.975).add(0, -0.0008, 0);
            age++;
        }

        @Override
        public boolean isAlive() {
            return age < lifetime;
        }

        @Override
        public Vec3 position(float partialTick) {
            return previousPosition.lerp(position, Mth.clamp(partialTick, 0, 1));
        }

        @Override
        public AABB bounds(float partialTick) {
            return new AABB(position(partialTick), position(partialTick)).inflate(size);
        }

        private float alpha(float partialTick) {
            float progress = Mth.clamp((age + partialTick) / lifetime, 0, 1);
            return Mth.sin((1 - progress) * Mth.PI * 0.5f);
        }

        private float rotation(float partialTick) {
            return rotation + (age + partialTick) * rotationSpeed;
        }
    }

    private static final class DemoParticleRenderer implements EffectRenderer<DemoParticle> {
        private final RenderShaderHandle shader;
        private boolean used;

        private DemoParticleRenderer(RenderShaderHandle shader) {
            this.shader = shader;
        }

        @Override
        public void begin(WorldRenderPipelineContext context) {
            used = false;
            ShaderInstance instance = shader.get();
            if (instance != null) {
                float partialTick = context.partialTick().getGameTimeDeltaPartialTick(false);
                instance.safeGetUniform("Time").set((context.level().getGameTime() + partialTick) / 20.0f);
            }
        }

        @Override
        public void render(DemoParticle effect, WorldRenderPipelineContext context) {
            if (!shader.isReady()) return;
            used = true;
            RenderType renderType = context.pipeline().renderType();
            VertexConsumer consumer = context.bufferSource().getBuffer(renderType);
            float partialTick = context.partialTick().getGameTimeDeltaPartialTick(false);
            Vec3 camera = context.camera().getPosition();
            Vec3 position = effect.position(partialTick);
            float halfSize = effect.size * 0.5f;
            float alpha = effect.alpha(partialTick);

            PoseStack poseStack = context.poseStack();
            poseStack.pushPose();
            try {
                poseStack.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
                poseStack.mulPose(context.camera().rotation());
                poseStack.mulPose(Axis.ZP.rotation(effect.rotation(partialTick)));
                Matrix4f pose = poseStack.last().pose();
                vertex(consumer, pose, -halfSize, -halfSize, 0, 1, effect.color, alpha);
                vertex(consumer, pose, halfSize, -halfSize, 1, 1, effect.color, alpha);
                vertex(consumer, pose, halfSize, halfSize, 1, 0, effect.color, alpha);
                vertex(consumer, pose, -halfSize, halfSize, 0, 0, effect.color, alpha);
            } finally {
                poseStack.popPose();
            }
        }

        @Override
        public void end(WorldRenderPipelineContext context) {
            if (used) context.bufferSource().endBatch(context.pipeline().renderType());
        }

        private static void vertex(VertexConsumer consumer, Matrix4f pose, float x, float y,
                                   float u, float v, ParticleColor color, float alpha) {
            consumer.addVertex(pose, x, y, 0)
                    .setUv(u, v)
                    .setColor(color.red, color.green, color.blue, alpha);
        }
    }
}
