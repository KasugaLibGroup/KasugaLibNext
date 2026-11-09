package lib.kasuga.scene;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.uml.dynamic.physics.box3d.NativeBox3D;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.GenericRigidBody;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.RigidBodyWorld;
import lib.kasuga.rendering.models.mc.dynamic.physics.PhysicsBlockRenderer;
import lib.kasuga.rendering.models.mc.dynamic.physics.PhysicsLightSampler;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftBlockPhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Scoped vanilla block/item props, optionally using the built-in rigid-body world and soft grab. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class SceneProps implements AutoCloseable {
    private static final Set<SceneProps> SCENES = new LinkedHashSet<>();
    private final ClientLevel level;
    private final List<Prop> props = new ArrayList<>();
    private RigidBodyWorld physics;
    private Prop held;
    private float holdDistance;
    private boolean closed;

    private SceneProps(ClientLevel level) { this.level = level; SCENES.add(this); }
    public static SceneProps open(ClientLevel level) { return new SceneProps(java.util.Objects.requireNonNull(level)); }
    public static boolean physicsAvailable() { return NativeBox3D.availableOrWarn(); }
    public static int activeScenes() { return SCENES.size(); }
    public static void initialize(RenderPipelineRegistrar registrar) {
        MinecraftBlockPhysics.initialize(registrar);
        registrar.world(RenderPipelineDescriptor.builder(ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID,
                "scene/vanilla_props"), RenderPhase.AFTER_BLOCK_ENTITIES).priority(100).build(), SceneProps::render);
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Prop scene is closed"); }
    private RigidBodyWorld physics() {
        requireOpen();
        if (physics == null) physics = new RigidBodyWorld(List.of(), List.of(), RigidBodyWorld.DEFAULT_SUBSTEP_COUNT);
        return physics;
    }
    public SceneProps ground(float y) { physics().addGroundPlane(y, .6f, .1f); return this; }
    public SceneProps collider(Vec3 minimum, Vec3 maximum) {
        physics().addStaticBoxCollider(vector(minimum), vector(maximum), .6f, .05f); return this;
    }
    public Prop block(BlockState block, Vec3 center) { return add(new Prop(block, null, center)); }
    public Prop item(ItemStack item, Vec3 center) { return add(new Prop(null, item.copy(), center)); }
    public Prop physicalItem(ItemStack item, Vec3 center, Vector3f halfExtents, float mass) {
        var prop = item(item, center);
        prop.body = GenericRigidBody.box(halfExtents, mass).at(vector(center)).damping(.8f, 3).friction(.6f);
        prop.body.wireWake(physics()::wake);
        if (!physics.add(prop.body)) { props.remove(prop); throw new IllegalStateException("Physics item could not be attached"); }
        return prop;
    }
    private Prop add(Prop prop) {
        requireOpen();
        if (props.size() >= 1024) throw new IllegalStateException("A scene may own at most 1024 props");
        props.add(prop); return prop;
    }

    /** Picks the nearest body in this scene. Terrain occlusion is supplied by the host's max distance. */
    public Optional<Prop> grab(Vec3 eye, Vec3 direction, float maximumDistance) {
        if (closed || physics == null || held != null) return Optional.empty();
        var hit = physics.raycast(vector(eye), vector(direction), maximumDistance).orElse(null);
        if (hit == null || !physics.beginDrag(hit)) return Optional.empty();
        held = props.stream().filter(prop -> prop.body == hit.body()).findFirst().orElse(null);
        if (held == null) { physics.endDrag(); return Optional.empty(); }
        holdDistance = Math.clamp(hit.distance(), 1.2f, 4);
        physics.setGravityScale(held.body, 0);
        return Optional.of(held);
    }
    public boolean holding() { return held != null; }
    public void holdDistance(float distance) { holdDistance = Math.clamp(distance, 1.2f, 4); }
    public float holdDistance() { return holdDistance; }
    public void drag(Vec3 eye, Vec3 direction, float seconds) {
        if (held != null) physics.updateDragTarget(vector(eye.add(direction.normalize().scale(holdDistance))), seconds);
    }
    public void release() {
        if (held == null) return;
        physics.endDrag(); physics.setGravityScale(held.body, 1); held = null;
    }
    /** Hands the prop's pose back to animation, preserving its current position. */
    public void detachPhysics(Prop prop) {
        if (prop.body == null) return;
        prop.position = prop.position(); prop.rotation.set(prop.body.rotationRef());
        if (held == prop) release();
        physics.remove(prop.body); prop.body = null;
    }
    public void remove(Prop prop) { detachPhysics(prop); props.remove(prop); }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        for (var scene : List.copyOf(SCENES)) if (Minecraft.getInstance().level != scene.level) scene.close();
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.isPaused() || mc.screen != null) return;
        float seconds = Math.min(.1f, event.getPartialTick().getRealtimeDeltaTicks() / 20);
        for (var scene : List.copyOf(SCENES)) if (scene.physics != null) {
            scene.physics.step(seconds, RigidBodyWorld.KinematicDriver.none());
        }
    }
    private static void render(WorldRenderPipelineContext context) {
        var camera = context.camera().getPosition();
        var poses = context.poseStack();
        for (var scene : SCENES) {
            if (scene.level != context.level()) continue;
            for (var prop : scene.props) {
                if (!prop.visible) continue;
                var pos = prop.position();
                poses.pushPose();
                poses.translate(pos.x - camera.x, pos.y - camera.y, pos.z - camera.z);
                poses.mulPose(prop.body == null ? prop.rotation : prop.body.interpolationRotationRef());
                poses.scale(prop.scale.x, prop.scale.y, prop.scale.z);
                if (prop.block != null) {
                    poses.translate(-.5, -.5, -.5);
                    PhysicsBlockRenderer.renderTerrain(context.minecraft().getBlockRenderer(), prop.block, poses,
                            context.bufferSource(), prop.light(scene.level), OverlayTexture.NO_OVERLAY,
                            scene.level, pos, prop.body == null ? prop.rotation : prop.body.interpolationRotationRef(), prop.lightAnchor);
                } else context.minecraft().getItemRenderer().renderStatic(prop.item, ItemDisplayContext.FIXED,
                        LevelRenderer.getLightColor(scene.level, BlockPos.containing(pos)), OverlayTexture.NO_OVERLAY,
                        poses, context.bufferSource(), scene.level, 0);
                poses.popPose();
            }
        }
        MinecraftBlockPhysics.renderInto(context);
        context.bufferSource().endBatch();
    }
    @Override public void close() {
        if (closed) return;
        release(); closed = true;
        if (physics != null) physics.close();
        props.clear(); SCENES.remove(this);
    }
    private static Vector3f vector(Vec3 value) { return new Vector3f((float) value.x, (float) value.y, (float) value.z); }

    public static final class Prop {
        private final BlockState block;
        private final ItemStack item;
        private Vec3 position;
        private final Quaternionf rotation = new Quaternionf();
        private final Vector3f scale = new Vector3f(1);
        private final BlockPos lightAnchor;
        private int lastLight;
        private final PhysicsLightSampler.Tracker lighting = new PhysicsLightSampler.Tracker();
        private GenericRigidBody body;
        private boolean visible = true;
        private Prop(BlockState block, ItemStack item, Vec3 position) {
            this.block = block; this.item = item; this.position = position;
            this.lightAnchor = block == null ? null : BlockPos.containing(position.x - .5, position.y - .5, position.z - .5);
        }
        public Vec3 position() {
            if (body == null) return position;
            var p = body.positionRef(); return new Vec3(p.x, p.y, p.z);
        }
        public Prop position(Vec3 value) { position = value; if (body != null) body.teleport(vector(value), rotation); return this; }
        public Prop rotation(float xDegrees, float yDegrees, float zDegrees) {
            rotation.rotationXYZ((float) Math.toRadians(xDegrees), (float) Math.toRadians(yDegrees), (float) Math.toRadians(zDegrees));
            if (body != null) body.teleport(vector(position), rotation);
            return this;
        }
        public Prop scale(float x, float y, float z) {
            if (!(x > 0 && y > 0 && z > 0) || !Float.isFinite(x + y + z)) throw new IllegalArgumentException("Prop scale must be finite and positive");
            scale.set(x, y, z); return this;
        }
        public Prop visible(boolean value) { visible = value; return this; }
        public boolean visible() { return visible; }

        private PhysicsLightSampler.Sample light(ClientLevel level) {
            Vec3 pos = position();
            Vector3f extent = new Vector3f(
                    Math.min(.75f, scale.x * .5f), Math.min(.75f, scale.y * .5f), Math.min(.75f, scale.z * .5f));
            PhysicsLightSampler.Sample sample = lighting.sample(level, pos,
                    new Vec3(extent.x, extent.y, extent.z), lightAnchor, lastLight,
                    body == null ? rotation : body.rotationRef());
            if (!sample.fallback() && sample.packedLight() != 0) lastLight = sample.packedLight();
            return sample;
        }
    }
}
