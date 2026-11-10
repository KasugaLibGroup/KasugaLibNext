package lib.kasuga.rendering.models.mc.dynamic;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.*;
import lib.kasuga.rendering.models.mc.component.ModelComponents;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Instance-owned simulation with a weak world-render registry. Never retains a model globally. */
public final class ModelParticleRuntime {
    public static final String MODULE_ID = "kasuga:model_emitters";
    private static final double STEP = 1.0 / 120.0;
    private static final Map<EmitterModule, Boolean> MODULES = new WeakHashMap<>();
    private static final ResourceLocation DOT = ResourceLocation.parse("kasuga_lib:textures/particle/model_dot.png");
    private static final Map<ResourceLocation, ResourceLocation> TEXTURES = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean initialized;
    private static int lastVisibleCount;

    private ModelParticleRuntime() {}

    public static void install(ModelInstance instance, ModelComponents components) {
        if (components.emitters().isEmpty() || module(instance) != null) return;
        EmitterModule module = new EmitterModule(components.emitters());
        instance.getTickLoop().addPostPhysics(MODULE_ID, module);
        synchronized (MODULES) { MODULES.put(module, Boolean.TRUE); }
    }

    public static EmitterModule module(ModelInstance instance) {
        return instance.getTickLoop().module(MODULE_ID, EmitterModule.class);
    }

    public static void register(ModelInstance instance) {
        EmitterModule module = module(instance);
        if (module != null && !instance.isClosed()) module.enable();
    }

    public static void unregister(ModelInstance instance) {
        EmitterModule module = module(instance);
        if (module != null) module.disable();
    }

    /** Old-world instances must be explicitly registered again before emitting in another world. */
    public static void clearWorldState() {
        for (EmitterModule module : modules()) module.resetWorld();
    }

    public static void invalidateTextures() { TEXTURES.clear(); }
    public static int lastVisibleCount() { return lastVisibleCount; }

    private static List<EmitterModule> modules() {
        synchronized (MODULES) { return new ArrayList<>(MODULES.keySet()); }
    }

    public static synchronized void initialize(RenderPipelineRegistrar registrar) {
        if (initialized) return;
        registrar.world(RenderPipelineDescriptor.builder(ResourceLocation.parse("kasuga_lib:effects/model_emitters"), RenderPhase.AFTER_PARTICLES)
                .draw(draw -> draw.vertexFormat(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP)
                        .primitiveMode(VertexFormat.Mode.QUADS).bufferSize(32768).sortOnUpload(true)
                        .shaderState(RenderStateShard.POSITION_COLOR_TEX_LIGHTMAP_SHADER)
                        .blend(PipelineBlendMode.TRANSLUCENT).depthTest(PipelineDepthTest.LEQUAL)
                        .cull(PipelineCullMode.DISABLED).writeMask(PipelineWriteMask.COLOR).target(PipelineTarget.PARTICLES))
                .build(), ModelParticleRuntime::render);
        initialized = true;
    }

    public record ParticleView(Vector3d position, float size, Vector4f color, ResourceLocation texture) {
        public ParticleView { position = new Vector3d(position); color = new Vector4f(color); }
        @Override public Vector3d position() { return new Vector3d(position); }
        @Override public Vector4f color() { return new Vector4f(color); }
    }

    public static final class EmitterModule implements ModelTickLoopModule {
        private final List<EmitterState> emitters;
        private double accumulator;
        private boolean enabled, closed, autoActivationAllowed = true;

        private EmitterModule(List<ModelComponents.EmitterDefinition> definitions) {
            emitters = definitions.stream().map(EmitterState::new).toList();
        }
        public boolean enabled() { return enabled && !closed; }
        public boolean closed() { return closed; }
        public int particleCount() { return emitters.stream().mapToInt(e -> e.particles.size()).sum(); }
        public List<ParticleView> particles() {
            List<ParticleView> result = new ArrayList<>(particleCount());
            for (EmitterState emitter : emitters) for (Particle particle : emitter.particles) {
                Vector3d position = particle.position;
                float size = emitter.definition.sizePixels() / 16f;
                if (emitter.definition.localSpace()) {
                    Vector3f point = new Vector3f((float)position.x, (float)position.y, (float)position.z);
                    emitter.frame.apply(point);
                    position = new Vector3d(emitter.origin).add(point.x, point.y, point.z);
                    Vector3f scale = emitter.frame.transform().getScale(new Vector3f());
                    size *= Math.max(Math.abs(scale.x), Math.max(Math.abs(scale.y), Math.abs(scale.z)));
                }
                result.add(new ParticleView(position, size, emitter.definition.color(), emitter.texture));
            }
            return List.copyOf(result);
        }
        public void enable() { if (!closed) { enabled = true; autoActivationAllowed = true; } }
        public void disable() { enabled = false; autoActivationAllowed = false; clear(); }
        private void resetWorld() { enabled = false; autoActivationAllowed = true; clear(); }
        private void clear() { accumulator = 0; for (EmitterState e : emitters) { e.particles.clear(); e.carry = 0; } }

        @Override public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
            if (closed) return;
            ModelInstance instance = loop.getInstance();
            if (!enabled && autoActivationAllowed && MinecraftModelRuntime.isRegistered(instance)) enable();
            if (!enabled) return;
            var skeleton = instance.getSkeletonInstance();
            Vector3d origin = skeleton.getWorldOrigin();
            for (EmitterState emitter : emitters) {
                Transform frame = skeleton.anchorTransformRelative(emitter.definition.anchorName(), origin);
                emitter.valid = frame != null && frame.transform().isFinite();
                if (emitter.valid) { emitter.frame.set(frame); emitter.origin.set(origin); }
            }
            if (!(deltaTime > 0) || !Float.isFinite(deltaTime)) return;
            accumulator = Math.min(accumulator + deltaTime, 0.1);
            int steps = Math.min(12, (int)Math.floor((accumulator + 1e-8) / STEP));
            accumulator = Math.max(0, accumulator - steps * STEP);
            for (int i = 0; i < steps; i++) for (EmitterState emitter : emitters) emitter.step();
        }
        @Override public void destroy(Model model) { closed = true; disable(); synchronized (MODULES) { MODULES.remove(this); } }
    }

    private static final class Particle {
        final Vector3d position;
        final Vector3d velocity;
        double age;
        Particle(Vector3d position, Vector3d velocity) { this.position = position; this.velocity = velocity; }
    }

    private static final class EmitterState {
        final ModelComponents.EmitterDefinition definition;
        final List<Particle> particles = new ArrayList<>();
        final Transform frame = new Transform();
        final Vector3d origin = new Vector3d();
        final Vector3d gravity;
        final ResourceLocation texture;
        double carry;
        boolean valid;
        EmitterState(ModelComponents.EmitterDefinition definition) {
            this.definition = definition;
            Vector3f acceleration = definition.gravityPixelsPerSecondSquared().div(16f);
            gravity = new Vector3d(acceleration.x, acceleration.y, acceleration.z);
            texture = textureLocation(definition.texture());
        }
        void step() {
            for (Iterator<Particle> it = particles.iterator(); it.hasNext();) {
                Particle p = it.next(); p.age += STEP;
                if (p.age + 1e-8 >= definition.lifetimeSeconds()) { it.remove(); continue; }
                p.velocity.fma(STEP, gravity); p.position.fma(STEP, p.velocity);
            }
            if (!valid || !definition.enabled()) return;
            carry += definition.rate() * STEP;
            int due = (int)Math.floor(carry + 1e-8);
            carry -= due; // discard emissions above budget, never defer a burst
            int count = Math.min(due, definition.maxParticles() - particles.size());
            for (int i = 0; i < count; i++) {
                double phi = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
                double cosine = 1 - ThreadLocalRandom.current().nextDouble() * (1 - Math.cos(Math.toRadians(definition.spreadDegrees())));
                double sine = Math.sqrt(Math.max(0, 1 - cosine * cosine));
                Vector3f direction = new Vector3f((float)(sine*Math.cos(phi)), (float)cosine, (float)(sine*Math.sin(phi)));
                Vector3d position;
                if (definition.localSpace()) position = new Vector3d();
                else {
                    frame.applyDirection(direction);
                    if (!direction.isFinite() || direction.lengthSquared() < 1e-12f) continue;
                    direction.normalize();
                    Vector3f point = frame.getPosition();
                    position = new Vector3d(origin).add(point.x, point.y, point.z);
                }
                particles.add(new Particle(position, new Vector3d(direction.x,direction.y,direction.z).mul(definition.speedPixelsPerSecond()/16.0)));
            }
        }
    }

    public static ResourceLocation textureLocation(String value) {
        if (value == null || value.isBlank()) return DOT;
        ResourceLocation id = ResourceLocation.parse(value);
        String path = id.getPath();
        if (!path.startsWith("textures/")) path = "textures/" + path;
        if (!path.endsWith(".png")) path += ".png";
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
    }

    private static void render(WorldRenderPipelineContext context) {
        List<ParticleView> particles = new ArrayList<>();
        for (EmitterModule module : modules()) if (module.enabled()) particles.addAll(module.particles());
        var camera = context.camera().getPosition();
        particles.sort(Comparator.comparingDouble((ParticleView p) -> p.position.distanceSquared(camera.x,camera.y,camera.z)).reversed());
        Set<RenderType> used = new HashSet<>();
        lastVisibleCount = 0;
        try {
            for (ParticleView p : particles) {
                double radius = p.size;
                if (context.frustum() != null && !context.frustum().isVisible(new net.minecraft.world.phys.AABB(
                        p.position.x-radius,p.position.y-radius,p.position.z-radius,p.position.x+radius,p.position.y+radius,p.position.z+radius))) continue;
                ResourceLocation texture = TEXTURES.computeIfAbsent(p.texture, id -> context.minecraft().getResourceManager().getResource(id).isPresent() ? id : DOT);
                RenderType type = context.pipeline().renderType(texture, false, false); used.add(type);
                VertexConsumer buffer = context.bufferSource().getBuffer(type);
                var pose = context.poseStack(); pose.pushPose();
                try {
                    pose.translate(p.position.x-camera.x,p.position.y-camera.y,p.position.z-camera.z);
                    pose.mulPose(context.camera().rotation());
                    Matrix4f matrix = pose.last().pose(); float half = p.size*0.5f;
                    vertex(buffer,matrix,-half,-half,0,1,p.color); vertex(buffer,matrix,half,-half,1,1,p.color);
                    vertex(buffer,matrix,half,half,1,0,p.color); vertex(buffer,matrix,-half,half,0,0,p.color);
                } finally { pose.popPose(); }
                lastVisibleCount++;
            }
        } finally { for (RenderType type : used) context.bufferSource().endBatch(type); }
    }
    private static void vertex(VertexConsumer buffer, Matrix4f pose, float x, float y, float u, float v, Vector4f color) {
        buffer.addVertex(pose,x,y,0).setColor(color.x,color.y,color.z,color.w).setUv(u,v).setLight(LightTexture.FULL_BRIGHT);
    }
}
