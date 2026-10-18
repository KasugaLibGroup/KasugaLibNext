package test.kasuga.modelling;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.particle.ParticleInstance;
import lib.kasuga.rendering.effect.particle.ParticleInstanceBuffer;
import lib.kasuga.rendering.effect.particle.ParticleOperator;
import lib.kasuga.rendering.effect.particle.ParticleOperators;
import lib.kasuga.rendering.effect.particle.ParticleRenderPipeline;
import lib.kasuga.rendering.effect.particle.ParticleSource;
import lib.kasuga.rendering.effect.particle.fluid.FluidConstraints3D;
import lib.kasuga.rendering.effect.particle.fluid.FluidEnvironment3D;
import lib.kasuga.rendering.effect.particle.fluid.minecraft.MinecraftBlockFluidConstraint;
import lib.kasuga.rendering.effect.particle.instance.ParticleInstanceMesh;
import lib.kasuga.rendering.effect.particle.instance.ParticleInstanceShaderPrograms;
import lib.kasuga.rendering.effect.particle.instance.ParticleInstancedBatchRenderer;
import lib.kasuga.rendering.effect.particle.instance.opengl.OpenGlParticleInstanceBackend;
import lib.kasuga.rendering.effect.particle.preset.BoidsPreset;
import lib.kasuga.rendering.effect.particle.preset.GasSmokePreset;
import lib.kasuga.rendering.effect.particle.preset.LiquidFlowPreset;
import lib.kasuga.rendering.effect.particle.preset.RainFieldPreset;
import lib.kasuga.rendering.effect.pipeline.PipelineBlendMode;
import lib.kasuga.rendering.effect.pipeline.PipelineCullMode;
import lib.kasuga.rendering.effect.pipeline.PipelineDepthTest;
import lib.kasuga.rendering.effect.pipeline.PipelineTarget;
import lib.kasuga.rendering.effect.pipeline.PipelineWriteMask;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.shader.ShaderProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Random;

/** Runtime showcase for simulated gas/liquid, group-controlled rain and optional Boids behavior. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ParticlePresetDemo {
    private static final float[][] CUBE_FACES = {
            {-0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f,
                    0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f},
            {0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f,
                    -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f},
            {0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f,
                    0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f},
            {-0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f,
                    -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, -0.5f},
            {-0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f,
                    0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f},
            {-0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f,
                    0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f}
    };
    private static final float[] CUBE_SHADES = {1.0f, 0.55f, 0.82f, 0.68f, 1.1f, 0.4f};
    private static final float[][] BOID_FACES = {
            {0, 0.35f, 0.5f, -0.45f, -0.2f, -0.5f, 0.45f, -0.2f, -0.5f},
            {0, 0.35f, 0.5f, 0.45f, -0.2f, -0.5f, 0, 0.1f, -0.75f},
            {0, 0.35f, 0.5f, 0, 0.1f, -0.75f, -0.45f, -0.2f, -0.5f},
            {-0.45f, -0.2f, -0.5f, 0, 0.1f, -0.75f, 0.45f, -0.2f, -0.5f}
    };
    private static final float[] BOID_SHADES = {1.0f, 0.72f, 0.58f, 0.42f};
    private static final ParticleInstanceMesh PARTICLE_CUBE_MESH = instanceCube();
    private static final GasSmokePreset SMOKE =
            new GasSmokePreset(GasSmokePreset.Settings.defaults());
    private static final LiquidFlowPreset LIQUID =
            new LiquidFlowPreset(LiquidFlowPreset.Settings.defaults());
    private static final MinecraftBlockFluidConstraint LIQUID_BLOCKS =
            new MinecraftBlockFluidConstraint(new Vector3f(6, 4, 6), 4);
    private static final RainFieldPreset RAIN =
            new RainFieldPreset(RainFieldPreset.Settings.defaults());
    private static final BoidsPreset BOIDS =
            new BoidsPreset(BoidsPreset.Settings.defaults());
    private static final Random RANDOM = new Random(0x5041525449434c45L);

    private static ParticleRenderPipeline smokePipeline;
    private static ParticleRenderPipeline cubeSmokePipeline;
    private static ParticleRenderPipeline rainPipeline;
    private static ParticleRenderPipeline boidsPipeline;
    private static ParticleRenderPipeline liquidPipeline;
    private static RenderShaderHandle smokeInstanceShader;
    private static RenderShaderHandle liquidInstanceShader;
    private static ParticleSource cubeSmokeSource;
    private static ClientLevel activeLevel;
    private static boolean smokeEnabled;
    private static boolean rainEnabled;
    private static boolean boidsEnabled;
    private static boolean liquidEnabled;
    private static int smokeDelay;
    private static int liquidDelay;
    private static final Vector3f smokeSource = new Vector3f();
    private static final Vector3f liquidSource = new Vector3f();
    private static final Vector3f liquidDirection = new Vector3f(0.12f, 0, 0);

    private ParticlePresetDemo() {
    }

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        var registrar = event.registrar(id("preset/particles"));
        smokeInstanceShader = registrar.shader(RenderShaderDescriptor.generated(
                ParticleInstanceShaderPrograms.volumetricSmoke(
                        "kasuga_demo:particle_smoke_volume_instance"
                ),
                DefaultVertexFormat.POSITION
        )).handle();
        liquidInstanceShader = registrar.shader(RenderShaderDescriptor.generated(
                liquidInstanceProgram(), DefaultVertexFormat.POSITION
        )).handle();
        smokePipeline = registrar.particles(
                instancedDescriptor("smoke", 300, smokeInstanceShader),
                new ParticleInstancedBatchRenderer(
                        PARTICLE_CUBE_MESH,
                        new OpenGlParticleInstanceBackend(smokeInstanceShader)
                )
        );
        smokePipeline.sortBackToFront(true);
        smokePipeline.bufferController(SMOKE.bufferController());

        cubeSmokePipeline = registrar.particles(
                descriptor(
                        "cube_smoke", 302, VertexFormat.Mode.QUADS,
                        PipelineBlendMode.TRANSLUCENT, PipelineCullMode.ENABLED
                ),
                ParticlePresetDemo::renderSolidInstances
        );
        cubeSmokePipeline.sortBackToFront(true);

        liquidPipeline = registrar.particles(
                instancedDescriptor("liquid", 305, liquidInstanceShader),
                new ParticleInstancedBatchRenderer(
                        PARTICLE_CUBE_MESH,
                        new OpenGlParticleInstanceBackend(liquidInstanceShader)
                )
        );
        liquidPipeline.sortBackToFront(true);
        liquidPipeline.bufferController(LIQUID.bufferController());

        rainPipeline = registrar.particles(
                descriptor("rain", 310, VertexFormat.Mode.QUADS, PipelineBlendMode.TRANSLUCENT),
                ParticlePresetDemo::renderSolidInstances
        );
        rainPipeline.sortBackToFront(true);
        rainPipeline.controller(RAIN.controller());

        boidsPipeline = registrar.particles(
                descriptor("boids", 320, VertexFormat.Mode.TRIANGLES, PipelineBlendMode.TRANSLUCENT),
                ParticlePresetDemo::renderBoids
        );
        boidsPipeline.sortBackToFront(true);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_particle_preset")
                .then(Commands.literal("smoke").executes(context -> {
                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player == null) {
                        context.getSource().sendFailure(Component.literal("Join a world first"));
                        return 0;
                    }
                    startSmoke(player);
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga gas-smoke preset enabled"), false
                    );
                    return 1;
                }))
                .then(Commands.literal("liquid").executes(context -> {
                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player == null) {
                        context.getSource().sendFailure(Component.literal("Join a world first"));
                        return 0;
                    }
                    startLiquid(player);
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga simplified Navier-Stokes liquid enabled"),
                            false
                    );
                    return 1;
                }))
                .then(Commands.literal("cube_smoke").executes(context -> {
                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player == null) {
                        context.getSource().sendFailure(Component.literal("Join a world first"));
                        return 0;
                    }
                    startCubeSmoke(player);
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga cube-smoke source enabled"), false
                    );
                    return 1;
                }))
                .then(Commands.literal("rain").executes(context -> {
                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player == null) {
                        context.getSource().sendFailure(Component.literal("Join a world first"));
                        return 0;
                    }
                    rainEnabled = true;
                    seedRain(player);
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga rain field preset enabled"), false
                    );
                    return 1;
                }))
                .then(Commands.literal("boids").executes(context -> {
                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player == null) {
                        context.getSource().sendFailure(Component.literal("Join a world first"));
                        return 0;
                    }
                    boidsEnabled = true;
                    seedBoids(player);
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga Boids preset enabled"), false
                    );
                    return 1;
                }))
                .then(Commands.literal("all").executes(context -> {
                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player == null) {
                        context.getSource().sendFailure(Component.literal("Join a world first"));
                        return 0;
                    }
                    startSmoke(player);
                    startCubeSmoke(player);
                    startLiquid(player);
                    rainEnabled = boidsEnabled = true;
                    seedRain(player);
                    seedBoids(player);
                    context.getSource().sendSuccess(
                            () -> Component.literal("All Kasuga particle presets enabled"), false
                    );
                    return 1;
                }))
                .then(Commands.literal("clear").executes(context -> {
                    clear();
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga particle presets cleared"), false
                    );
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Kasuga particles: smoke=" + count(smokePipeline)
                                    + ", cubeSmoke=" + count(cubeSmokePipeline)
                                    + ", liquid=" + count(liquidPipeline)
                                    + " (blockCells=" + LIQUID_BLOCKS.cachedSolidCellCount() + ")"
                                    + ", rain=" + count(rainPipeline)
                                    + ", boids=" + count(boidsPipeline)
                    ), false);
                    return 1;
                })));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != activeLevel) {
            clear();
            activeLevel = level;
            return;
        }
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || minecraft.isPaused()) return;

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        if (smokeEnabled && smokeDelay-- <= 0) {
            smokeDelay = 3;
            emitSmoke();
        }
        if (liquidEnabled && liquidDelay-- <= 0) {
            liquidDelay = 1;
            emitLiquid();
        }
        if (rainEnabled) {
            RAIN.center(vector(eye));
            if (rainPipeline.activeCount() < 420) seedRain(player);
        }
        if (boidsEnabled) {
            BOIDS.center(vector(eye.add(look.scale(8.0))));
            if (boidsPipeline.activeCount() < 72) seedBoids(player);
        }
    }

    private static void startSmoke(LocalPlayer player) {
        smokeEnabled = true;
        smokeSource.set(vector(player.getEyePosition().add(player.getLookAngle().normalize().scale(8.0))));
        SMOKE.center(new Vector3f(smokeSource).add(0, 4.5f, 0));
        SMOKE.clearFluid();
        if (smokePipeline != null) smokePipeline.clear();
        smokeDelay = 0;
    }

    private static void startCubeSmoke(LocalPlayer player) {
        if (cubeSmokePipeline == null) return;
        cubeSmokePipeline.clearSources();
        cubeSmokePipeline.clear();
        Vector3f position = vector(player.getEyePosition().add(
                player.getLookAngle().normalize().scale(8.0)
        ));
        ParticleOperator cubeSmoke = ParticleOperators
                .physics(new Vector3f(0, 0.0035f, 0), 0.985f)
                .then(ParticleOperators.scale(1.012f))
                .then(ParticleOperators.rotate(new Vector3f(0.006f, 0.018f, 0.004f)))
                .then(ParticleOperators.fade(0.975f));
        cubeSmokeSource = cubeSmokePipeline.source(ParticleSource.Settings.builder()
                .position(position)
                .emissionRate(0.75f)
                .particleType(spawn -> {
                    float angle = unitNoise(spawn.sequence(), 0) * (float) (Math.PI * 2);
                    float radius = 0.18f * (float) Math.sqrt(unitNoise(spawn.sequence(), 1));
                    Transform transform = spawn.transform().translateWorld(new Vector3f(
                            (float) Math.cos(angle) * radius,
                            unitNoise(spawn.sequence(), 2) * 0.08f,
                            (float) Math.sin(angle) * radius
                    ));
                    Vector3f velocity = spawn.velocity().add(
                            signedNoise(spawn.sequence(), 3) * 0.012f,
                            unitNoise(spawn.sequence(), 4) * 0.018f,
                            signedNoise(spawn.sequence(), 5) * 0.012f
                    );
                    float shade = 0.88f + unitNoise(spawn.sequence(), 6) * 0.12f;
                    Vector4f color = spawn.color();
                    color.x *= shade;
                    color.y *= shade;
                    color.z *= shade;
                    return ParticleInstance.builder(transform)
                            .velocity(velocity)
                            .color(color)
                            .attributes(1)
                            .build();
                })
                .initialVelocity(new Vector3f(0, 0.035f, 0))
                .affectedByGravity(false)
                .size(0.35f)
                .rotation(new Vector3f(0, 0.25f, 0))
                .color(new Vector4f(0.62f, 0.62f, 0.62f, 0.34f))
                .lifetimeTicks(100)
                .operator(cubeSmoke)
                .build());
    }

    private static void emitSmoke() {
        SMOKE.inject(
                smokeSource,
                4.5f,
                new Vector3f(signed(0.025f), 0.18f, signed(0.025f))
        );
        for (int index = 0; index < 3; index++) {
            Vector3f position = new Vector3f(smokeSource).add(
                    signed(0.35f), RANDOM.nextFloat() * 0.15f, signed(0.35f)
            );
            float shade = 0.55f + RANDOM.nextFloat() * 0.2f;
            smokePipeline.add(SMOKE.createTracer(
                    position,
                    0.45f + RANDOM.nextFloat() * 0.35f,
                    new Vector4f(shade, shade * 0.96f, shade * 0.92f, 0.46f)
            ));
        }
    }

    private static void startLiquid(LocalPlayer player) {
        liquidEnabled = true;
        Vec3 look = player.getLookAngle().normalize();
        liquidSource.set(vector(player.getEyePosition().add(look.scale(8.0)).add(0, 2.0, 0)));
        liquidDirection.set((float) look.x, -0.08f, (float) look.z).normalize(0.32f);
        Vector3f liquidCenter = new Vector3f(liquidSource).add(
                liquidDirection.x * 4.0f, -2.0f, liquidDirection.z * 4.0f
        );
        LIQUID.center(liquidCenter);
        LIQUID_BLOCKS.level(Minecraft.getInstance().level);
        LIQUID_BLOCKS.center(liquidCenter);
        LIQUID.tracerCollision(LIQUID_BLOCKS);
        LIQUID.environment(FluidEnvironment3D.builder()
                .add(LIQUID_BLOCKS)
                .add(FluidConstraints3D.directionalForce(
                        new Vector3f(0.12f, 0.12f, 0.12f),
                        new Vector3f(0.88f, 0.88f, 0.88f),
                        new Vector3f(0, -0.08f, 0)
                ))
                .add(FluidConstraints3D.drain(
                        new Vector3f(0, 0.06f, 0),
                        new Vector3f(1, 0.12f, 1),
                        0.82f,
                        new Vector3f(0, -0.12f, 0)
                ))
                .build());
        LIQUID.clearFluid();
        if (liquidPipeline != null) liquidPipeline.clear();
        liquidDelay = 0;
    }

    private static void emitLiquid() {
        LIQUID.inject(liquidSource, 5.0f, liquidDirection);
        if (liquidPipeline.activeCount() >= 700) return;
        for (int index = 0; index < 6; index++) {
            Vector3f position = new Vector3f(liquidSource).add(
                    signed(0.22f), signed(0.22f), signed(0.22f)
            );
            liquidPipeline.add(LIQUID.createTracer(
                    position,
                    0.09f + RANDOM.nextFloat() * 0.045f,
                    new Vector4f(0.08f, 0.38f, 0.95f, 0.72f)
            ));
        }
    }

    private static void seedRain(LocalPlayer player) {
        RAIN.center(vector(player.getEyePosition()));
        while (rainPipeline.activeCount() < 420) {
            rainPipeline.add(RAIN.create(
                    RANDOM, new Vector4f(0.55f, 0.72f, 1.0f, 0.58f)
            ));
        }
    }

    private static void seedBoids(LocalPlayer player) {
        Vector3f center = vector(player.getEyePosition().add(player.getLookAngle().scale(8.0)));
        BOIDS.center(center);
        while (boidsPipeline.activeCount() < 72) {
            Vector3f position = new Vector3f(center).add(
                    signed(5.0f), signed(3.0f), signed(5.0f)
            );
            Vector3f velocity = randomDirection().mul(0.08f + RANDOM.nextFloat() * 0.07f);
            boidsPipeline.add(BOIDS.create(
                    position, velocity, 0.12f + RANDOM.nextFloat() * 0.08f,
                    new Vector4f(
                            0.25f + RANDOM.nextFloat() * 0.3f,
                            0.65f + RANDOM.nextFloat() * 0.3f,
                            1.0f,
                            0.9f
                    )
            ));
        }
    }

    private static RenderPipelineDescriptor descriptor(
            String name, int priority, VertexFormat.Mode mode, PipelineBlendMode blend
    ) {
        return descriptor(name, priority, mode, blend, PipelineCullMode.DISABLED);
    }

    private static RenderPipelineDescriptor descriptor(
            String name,
            int priority,
            VertexFormat.Mode mode,
            PipelineBlendMode blend,
            PipelineCullMode cull
    ) {
        return RenderPipelineDescriptor.builder(id("preset/particle_" + name), RenderPhase.AFTER_PARTICLES)
                .priority(priority)
                .draw(draw -> draw
                        .vertexFormat(DefaultVertexFormat.POSITION_COLOR)
                        .primitiveMode(mode)
                        .bufferSize(32768)
                        .sortOnUpload(
                                mode == VertexFormat.Mode.QUADS
                                        && blend == PipelineBlendMode.TRANSLUCENT
                        )
                        .shaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .blend(blend)
                        .depthTest(PipelineDepthTest.LEQUAL)
                        .cull(cull)
                        .writeMask(PipelineWriteMask.COLOR)
                        .target(PipelineTarget.PARTICLES))
                .build();
    }

    private static RenderPipelineDescriptor instancedDescriptor(
            String name,
            int priority,
            RenderShaderHandle shader
    ) {
        return RenderPipelineDescriptor.builder(id("preset/particle_" + name), RenderPhase.AFTER_PARTICLES)
                .priority(priority)
                .draw(draw -> draw
                        .vertexFormat(DefaultVertexFormat.POSITION)
                        .primitiveMode(VertexFormat.Mode.TRIANGLES)
                        .bufferSize(4096)
                        .sortOnUpload(false)
                        .shader(shader)
                        .blend(PipelineBlendMode.TRANSLUCENT)
                        .depthTest(PipelineDepthTest.LEQUAL)
                        .cull(PipelineCullMode.ENABLED)
                        .writeMask(PipelineWriteMask.COLOR)
                        .target(PipelineTarget.PARTICLES))
                .build();
    }

    private static ShaderProgram liquidInstanceProgram() {
        return ParticleInstanceShaderPrograms.colored("kasuga_demo:particle_liquid_instance");
    }

    private static ParticleInstanceMesh instanceCube() {
        float[] positions = new float[CUBE_FACES.length * 6 * 3];
        int output = 0;
        for (float[] face : CUBE_FACES) {
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int vertex : order) {
                int offset = vertex * 3;
                positions[output++] = face[offset];
                positions[output++] = face[offset + 1];
                positions[output++] = face[offset + 2];
            }
        }
        return new ParticleInstanceMesh(ParticleInstanceMesh.Topology.TRIANGLES, positions);
    }

    private static void renderSolidInstances(ParticleInstanceBuffer instances,
                                             WorldRenderPipelineContext context) {
        RenderType renderType = context.pipeline().renderType();
        VertexConsumer consumer = context.bufferSource().getBuffer(renderType);
        Matrix4f base = cameraRelativeBase(context);
        Matrix4f transform = new Matrix4f();
        Matrix4f pose = new Matrix4f();
        Vector4f color = new Vector4f();
        for (int index = 0; index < instances.size(); index++) {
            instances.matrix(index, transform);
            instances.color(index, color);
            pose.set(base).mul(transform);
            cuboid(consumer, pose, color);
        }
        context.bufferSource().endBatch(renderType);
    }

    private static void renderBoids(ParticleInstanceBuffer instances,
                                    WorldRenderPipelineContext context) {
        RenderType renderType = context.pipeline().renderType();
        VertexConsumer consumer = context.bufferSource().getBuffer(renderType);
        Matrix4f base = cameraRelativeBase(context);
        Matrix4f transform = new Matrix4f();
        Matrix4f pose = new Matrix4f();
        Vector4f color = new Vector4f();
        for (int index = 0; index < instances.size(); index++) {
            instances.matrix(index, transform);
            instances.color(index, color);
            pose.set(base).mul(transform);
            for (int face = 0; face < BOID_FACES.length; face++) {
                triangle(consumer, pose, color, BOID_SHADES[face], BOID_FACES[face]);
            }
        }
        context.bufferSource().endBatch(renderType);
    }

    private static Matrix4f cameraRelativeBase(WorldRenderPipelineContext context) {
        Vec3 camera = context.camera().getPosition();
        return new Matrix4f(context.poseStack().last().pose())
                .translate((float) -camera.x, (float) -camera.y, (float) -camera.z);
    }

    private static void cuboid(VertexConsumer consumer, Matrix4f pose, Vector4f color) {
        for (int face = 0; face < CUBE_FACES.length; face++) {
            quad(consumer, pose, color, CUBE_SHADES[face], CUBE_FACES[face]);
        }
    }

    private static void quad(VertexConsumer consumer, Matrix4f pose, Vector4f color, float shade,
                             float... coordinates) {
        for (int index = 0; index < 4; index++) {
            int offset = index * 3;
            vertex(consumer, pose, color, shade,
                    coordinates[offset], coordinates[offset + 1], coordinates[offset + 2]);
        }
    }

    private static void triangle(VertexConsumer consumer, Matrix4f pose, Vector4f color,
                                 float shade, float... coordinates) {
        for (int index = 0; index < 3; index++) {
            int offset = index * 3;
            vertex(consumer, pose, color, shade,
                    coordinates[offset], coordinates[offset + 1], coordinates[offset + 2]);
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, Vector4f color, float shade,
                               float x, float y, float z) {
        consumer.addVertex(pose, x, y, z).setColor(
                Math.min(1.0f, color.x * shade),
                Math.min(1.0f, color.y * shade),
                Math.min(1.0f, color.z * shade),
                color.w
        );
    }

    private static int count(ParticleRenderPipeline pipeline) {
        return pipeline == null ? 0 : pipeline.activeCount();
    }

    private static void clear() {
        smokeEnabled = rainEnabled = boidsEnabled = liquidEnabled = false;
        smokeDelay = 0;
        liquidDelay = 0;
        if (cubeSmokeSource != null) cubeSmokeSource.close();
        if (smokePipeline != null) smokePipeline.clear();
        if (cubeSmokePipeline != null) {
            cubeSmokePipeline.clearSources();
            cubeSmokePipeline.clear();
        }
        if (liquidPipeline != null) liquidPipeline.clear();
        if (rainPipeline != null) rainPipeline.clear();
        if (boidsPipeline != null) boidsPipeline.clear();
        cubeSmokeSource = null;
        SMOKE.clearFluid();
        LIQUID.clearFluid();
        LIQUID_BLOCKS.level(null);
    }

    private static float signed(float radius) {
        return (RANDOM.nextFloat() * 2.0f - 1.0f) * radius;
    }

    private static float signedNoise(long sequence, int salt) {
        return unitNoise(sequence, salt) * 2 - 1;
    }

    private static float unitNoise(long sequence, int salt) {
        long value = sequence + 0x9e3779b97f4a7c15L * (salt + 1L);
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 40) * (1.0f / (1 << 24));
    }

    private static Vector3f randomDirection() {
        Vector3f result;
        do {
            result = new Vector3f(signed(1), signed(1), signed(1));
        } while (result.lengthSquared() < 1.0e-5f || result.lengthSquared() > 1.0f);
        return result.normalize();
    }

    private static Vector3f vector(Vec3 value) {
        return new Vector3f((float) value.x, (float) value.y, (float) value.z);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_demo", path);
    }
}
