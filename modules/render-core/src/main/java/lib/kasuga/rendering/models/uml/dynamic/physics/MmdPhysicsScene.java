package lib.kasuga.rendering.models.uml.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.BallJoint;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.PhysicsJoint;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.RigidBodyWorld;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.SimBody;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One origin-local Box3D scene shared by several independently skinned models.
 * Bodies from different ragdolls collide in the same native world and may be
 * connected by generic Box3D joints.
 */
public final class MmdPhysicsScene implements AutoCloseable {
    public static final int DEFAULT_SUBSTEP_COUNT = 8;

    private final RigidBodyWorld world;
    private final List<MmdRagdoll> ragdolls = new ArrayList<>();
    private final Map<SimBody, MmdRagdoll> ownerByBody = new IdentityHashMap<>();
    private int nextSelfCollisionGroup = -1;
    private boolean closed;
    private boolean stepping;

    private final RigidBodyWorld.KinematicDriver driver = new RigidBodyWorld.KinematicDriver() {
        @Override public void beginStep(RigidBodyWorld simulatedWorld) {}

        @Override
        public Frames.Pose kinematicTarget(SimBody body) {
            MmdRagdoll owner = ownerByBody.get(body);
            return owner == null ? new Frames.Pose(body.positionRef(), body.rotationRef())
                    : owner.sharedKinematicTarget(body);
        }
    };

    public MmdPhysicsScene(Vector3dc worldOrigin) {
        this(worldOrigin, DEFAULT_SUBSTEP_COUNT);
    }

    public MmdPhysicsScene(Vector3dc worldOrigin, int substepCount) {
        world = new RigidBodyWorld(List.of(), List.of(), substepCount);
        world.setWorldOrigin(Objects.requireNonNull(worldOrigin, "worldOrigin"));
        // Each ragdoll receives a distinct negative Box3D group, disabling
        // self-contact without suppressing contact with other models.
        world.setSelfCollisionsEnabled(true);
    }

    public Vector3d worldOrigin() { return world.worldOrigin(); }
    public RigidBodyWorld world() { return world; }
    public List<MmdRagdoll> ragdolls() { return Collections.unmodifiableList(ragdolls); }
    public boolean closed() { return closed; }

    public MmdRagdoll attach(ModelInstance instance, MmdRagdoll.Profile profile) {
        ensureOpen();
        return Objects.requireNonNull(instance, "instance").enablePhysics(this, profile);
    }

    public MmdRagdoll attach(ModelInstance instance) {
        ensureOpen();
        return Objects.requireNonNull(instance, "instance").enablePhysics(this, null);
    }

    int allocateSelfCollisionGroup() {
        ensureOpen();
        if (nextSelfCollisionGroup == Integer.MIN_VALUE) {
            throw new IllegalStateException("shared scene exhausted Box3D self-collision groups");
        }
        return nextSelfCollisionGroup--;
    }

    void register(MmdRagdoll ragdoll) {
        ensureOpen();
        if (ragdolls.contains(ragdoll)) return;
        ragdolls.add(ragdoll);
        for (MmdRagdoll.Body body : ragdoll.allBodies()) {
            ownerByBody.put(body, ragdoll);
            world.add(body);
        }
        for (MmdRagdoll.Joint joint : ragdoll.allJoints()) world.add(joint);
    }

    void detach(MmdRagdoll ragdoll) {
        if (!ragdolls.remove(ragdoll)) return;
        for (MmdRagdoll.Body body : ragdoll.allBodies()) {
            ownerByBody.remove(body);
            world.remove(body);
        }
    }

    public void addJoint(PhysicsJoint joint) {
        ensureSharedBodies(joint.bodyA(), joint.bodyB());
        world.add(joint);
    }

    public void removeJoint(PhysicsJoint joint) {
        world.remove(Objects.requireNonNull(joint, "joint"));
    }

    public void addJoint(BallJoint joint) {
        ensureSharedBodies(joint.bodyA(), joint.bodyB());
        world.add(joint);
    }

    public void removeJoint(BallJoint joint) {
        world.remove(Objects.requireNonNull(joint, "joint"));
    }

    private void ensureSharedBodies(SimBody a, SimBody b) {
        ensureOpen();
        MmdRagdoll ownerA = ownerByBody.get(Objects.requireNonNull(a, "bodyA"));
        MmdRagdoll ownerB = ownerByBody.get(Objects.requireNonNull(b, "bodyB"));
        if (ownerA == null || ownerB == null) {
            throw new IllegalArgumentException("joint bodies must belong to this shared scene");
        }
    }

    /** Advances the native world once, then writes every model's pose back. */
    public void step(float deltaSeconds) {
        ensureOpen();
        if (stepping) throw new IllegalStateException("shared physics scene cannot step recursively");
        stepping = true;
        try {
            for (MmdRagdoll ragdoll : ragdolls) {
                if (!ragdoll.enabled()) continue;
                ModelInstance instance = ragdoll.modelInstance();
                instance.getTickLoop().tickBeforeSharedPhysics(deltaSeconds);
                instance.getMorph().update();
                ragdoll.prepareSharedStep();
            }
            world.step(deltaSeconds, driver);
            float alpha = world.interpolationAlpha();
            for (MmdRagdoll ragdoll : ragdolls) {
                if (!ragdoll.enabled()) continue;
                ragdoll.finishSharedStep(alpha);
                ragdoll.modelInstance().getTickLoop().tickAfterSharedPhysics(deltaSeconds);
            }
        } finally {
            stepping = false;
        }
    }

    /** Samples every participant's animation, then performs one shared native step. */
    public void evaluateFrame(float partialTick, float deltaSeconds) {
        for (MmdRagdoll ragdoll : ragdolls) {
            if (ragdoll.enabled()) ragdoll.modelInstance().prepareSharedPhysicsFrame(partialTick);
        }
        step(deltaSeconds);
    }

    @Override
    public void close() {
        if (closed) return;
        for (MmdRagdoll ragdoll : List.copyOf(ragdolls)) ragdoll.onSharedSceneClosed();
        ragdolls.clear();
        ownerByBody.clear();
        world.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("shared physics scene is closed");
    }
}
