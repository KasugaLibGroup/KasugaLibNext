package lib.kasuga.rendering.models.uml.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.BallJoint;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.CollisionEnvironment;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.PhysicsJoint;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.SimBody;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

import java.util.*;

/**
 * Manages spatial clustering and dynamic batching of physics entities into
 * origin-localized {@link MmdPhysicsScene} instances.
 */
public final class MmdPhysicsClusterManager implements AutoCloseable {
    public static final double DEFAULT_CLUSTER_RADIUS = 24.0;
    public static final double DEFAULT_SPLIT_HYSTERESIS = 1.35;
    public static final double DEFAULT_VELOCITY_LOOKAHEAD_SECONDS = 0.25;
    public static final double DEFAULT_SPLIT_GRACE_SECONDS = 0.5;
    public static final int DEFAULT_SUBSTEP_COUNT = MmdPhysicsScene.DEFAULT_SUBSTEP_COUNT;

    public interface EnvironmentFactory {
        CollisionEnvironment create(MmdPhysicsScene scene, Vector3dc worldOrigin);
    }

    private static final class RegisteredInstance {
        final ModelInstance instance;
        final MmdRagdoll.Profile profile;
        final Vector3d lastPosition = new Vector3d();
        final Vector3f estimatedVelocity = new Vector3f();
        boolean positionInitialized;

        RegisteredInstance(ModelInstance instance, MmdRagdoll.Profile profile) {
            this.instance = instance;
            this.profile = profile;
        }

        void updateVelocity(Vector3d currentPosition, float dt) {
            if (positionInitialized && dt > 1e-5f) {
                Vector3f vFromBodies = maxBodyVelocity(instance);
                if (vFromBodies != null && vFromBodies.lengthSquared() > 1e-4f) {
                    estimatedVelocity.set(vFromBodies);
                } else {
                    estimatedVelocity.set(
                            (float) ((currentPosition.x - lastPosition.x) / dt),
                            (float) ((currentPosition.y - lastPosition.y) / dt),
                            (float) ((currentPosition.z - lastPosition.z) / dt)
                    );
                }
            } else {
                Vector3f vFromBodies = maxBodyVelocity(instance);
                if (vFromBodies != null) estimatedVelocity.set(vFromBodies);
            }
            lastPosition.set(currentPosition);
            positionInitialized = true;
        }

        private static Vector3f maxBodyVelocity(ModelInstance instance) {
            MmdRagdoll ragdoll = instance.getRagdoll();
            if (ragdoll == null || ragdoll.bodies().isEmpty()) return null;
            Vector3f maxV = new Vector3f();
            float maxLenSq = 0f;
            for (MmdRagdoll.Body body : ragdoll.bodies()) {
                Vector3f v = body.linearVelocityRef();
                float lenSq = v.lengthSquared();
                if (lenSq > maxLenSq) {
                    maxLenSq = lenSq;
                    maxV.set(v);
                }
            }
            return maxV;
        }
    }

    private static final class Cluster {
        final MmdPhysicsScene scene;
        final Set<ModelInstance> members = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<PhysicsJoint> joints = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<BallJoint> ballJoints = Collections.newSetFromMap(new IdentityHashMap<>());
        CollisionEnvironment environment;

        Cluster(MmdPhysicsScene scene) {
            this.scene = scene;
        }

        void close() {
            scene.close();
        }
    }

    private double clusterRadius;
    private double splitRadius;
    private double velocityLookaheadSeconds = DEFAULT_VELOCITY_LOOKAHEAD_SECONDS;
    private double splitGraceSeconds = DEFAULT_SPLIT_GRACE_SECONDS;
    private int substepCount;
    private EnvironmentFactory environmentFactory;
    private boolean closed;

    private final Map<ModelInstance, RegisteredInstance> registeredInstances = new IdentityHashMap<>();
    private final Map<ModelInstance, Cluster> clusterByInstance = new IdentityHashMap<>();
    private final List<Cluster> activeClusters = new ArrayList<>();
    private final Set<PhysicsJoint> registeredJoints = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BallJoint> registeredBallJoints = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<PairKey, Double> pairSeparationTimers = new HashMap<>();

    public MmdPhysicsClusterManager() {
        this(DEFAULT_CLUSTER_RADIUS, DEFAULT_CLUSTER_RADIUS * DEFAULT_SPLIT_HYSTERESIS, DEFAULT_SUBSTEP_COUNT);
    }

    public MmdPhysicsClusterManager(double clusterRadius) {
        this(clusterRadius, clusterRadius * DEFAULT_SPLIT_HYSTERESIS, DEFAULT_SUBSTEP_COUNT);
    }

    public MmdPhysicsClusterManager(double clusterRadius, double splitRadius, int substepCount) {
        setRadii(clusterRadius, splitRadius);
        setSubstepCount(substepCount);
    }

    public double clusterRadius() {
        return clusterRadius;
    }

    public void setClusterRadius(double radius) {
        if (!Double.isFinite(radius) || radius <= 0.0) {
            throw new IllegalArgumentException("clusterRadius must be finite and positive");
        }
        this.clusterRadius = radius;
        if (splitRadius < radius) {
            this.splitRadius = radius * DEFAULT_SPLIT_HYSTERESIS;
        }
    }

    public double splitRadius() {
        return splitRadius;
    }

    public void setSplitRadius(double radius) {
        if (!Double.isFinite(radius) || radius < clusterRadius) {
            throw new IllegalArgumentException("splitRadius must be finite and >= clusterRadius");
        }
        this.splitRadius = radius;
    }

    public void setRadii(double clusterRadius, double splitRadius) {
        if (!Double.isFinite(clusterRadius) || clusterRadius <= 0.0) {
            throw new IllegalArgumentException("clusterRadius must be finite and positive");
        }
        if (!Double.isFinite(splitRadius) || splitRadius < clusterRadius) {
            throw new IllegalArgumentException("splitRadius must be finite and >= clusterRadius");
        }
        this.clusterRadius = clusterRadius;
        this.splitRadius = splitRadius;
    }

    public double velocityLookaheadSeconds() {
        return velocityLookaheadSeconds;
    }

    public void setVelocityLookaheadSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0.0) {
            throw new IllegalArgumentException("velocityLookaheadSeconds must be finite and non-negative");
        }
        this.velocityLookaheadSeconds = seconds;
    }

    public double splitGraceSeconds() {
        return splitGraceSeconds;
    }

    public void setSplitGraceSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0.0) {
            throw new IllegalArgumentException("splitGraceSeconds must be finite and non-negative");
        }
        this.splitGraceSeconds = seconds;
    }

    public int substepCount() {
        return substepCount;
    }

    public void setSubstepCount(int substepCount) {
        if (substepCount < 1 || substepCount > 50) {
            throw new IllegalArgumentException("substepCount must be within [1, 50]");
        }
        this.substepCount = substepCount;
    }

    public EnvironmentFactory environmentFactory() {
        return environmentFactory;
    }

    public void setEnvironmentFactory(EnvironmentFactory environmentFactory) {
        this.environmentFactory = environmentFactory;
    }

    public MmdRagdoll attach(ModelInstance instance) {
        return attach(instance, null);
    }

    public synchronized MmdRagdoll attach(ModelInstance instance, MmdRagdoll.Profile profile) {
        ensureOpen();
        Objects.requireNonNull(instance, "instance");
        registeredInstances.put(instance, new RegisteredInstance(instance, profile));
        recluster(0f);
        return instance.getRagdoll();
    }

    public synchronized boolean detach(ModelInstance instance) {
        if (instance == null) return false;
        RegisteredInstance removed = registeredInstances.remove(instance);
        if (removed == null) return false;

        Cluster cluster = clusterByInstance.remove(instance);
        if (cluster != null) {
            cluster.members.remove(instance);
        }
        Set<SimBody> removedBodies = Collections.newSetFromMap(new IdentityHashMap<>());
        MmdRagdoll ragdoll = instance.getRagdoll();
        if (ragdoll != null) removedBodies.addAll(ragdoll.allBodies());
        registeredJoints.removeIf(j -> removedBodies.contains(j.bodyA()) || removedBodies.contains(j.bodyB()));
        registeredBallJoints.removeIf(j -> removedBodies.contains(j.bodyA()) || removedBodies.contains(j.bodyB()));
        for (Cluster active : activeClusters) {
            active.joints.removeIf(j -> removedBodies.contains(j.bodyA()) || removedBodies.contains(j.bodyB()));
            active.ballJoints.removeIf(j -> removedBodies.contains(j.bodyA()) || removedBodies.contains(j.bodyB()));
        }
        instance.detachPhysics();
        pairSeparationTimers.keySet().removeIf(pair -> pair.contains(instance));

        recluster(0f);
        return true;
    }

    public synchronized void addJoint(PhysicsJoint joint) {
        ensureOpen();
        Objects.requireNonNull(joint, "joint");
        registeredJoints.add(joint);
        recluster(0f);
    }

    public synchronized void removeJoint(PhysicsJoint joint) {
        Objects.requireNonNull(joint, "joint");
        if (registeredJoints.remove(joint)) {
            for (Cluster cluster : activeClusters) {
                if (cluster.joints.remove(joint)) {
                    cluster.scene.removeJoint(joint);
                }
            }
            recluster(0f);
        }
    }

    public synchronized void addJoint(BallJoint joint) {
        ensureOpen();
        Objects.requireNonNull(joint, "joint");
        registeredBallJoints.add(joint);
        recluster(0f);
    }

    public synchronized void removeJoint(BallJoint joint) {
        Objects.requireNonNull(joint, "joint");
        if (registeredBallJoints.remove(joint)) {
            for (Cluster cluster : activeClusters) {
                if (cluster.ballJoints.remove(joint)) {
                    cluster.scene.removeJoint(joint);
                }
            }
            recluster(0f);
        }
    }

    public synchronized List<ModelInstance> instances() {
        return List.copyOf(registeredInstances.keySet());
    }

    public synchronized List<MmdPhysicsScene> activeScenes() {
        return activeClusters.stream().map(c -> c.scene).toList();
    }

    public synchronized MmdPhysicsScene sceneOf(ModelInstance instance) {
        Cluster cluster = clusterByInstance.get(instance);
        return cluster == null ? null : cluster.scene;
    }

    public synchronized int clusterCount() {
        return activeClusters.size();
    }

    public synchronized int instanceCount() {
        return registeredInstances.size();
    }

    int registeredJointCount() {
        return registeredJoints.size() + registeredBallJoints.size();
    }

    public synchronized void recluster() {
        recluster(0f);
    }

    public synchronized void recluster(float dt) {
        ensureOpen();
        if (registeredInstances.isEmpty()) {
            for (Cluster cluster : activeClusters) cluster.close();
            activeClusters.clear();
            clusterByInstance.clear();
            pairSeparationTimers.clear();
            return;
        }

        List<ModelInstance> instanceList = new ArrayList<>(registeredInstances.keySet());
        int count = instanceList.size();
        Vector3d[] positions = new Vector3d[count];
        Vector3f[] velocities = new Vector3f[count];

        for (int i = 0; i < count; i++) {
            ModelInstance inst = instanceList.get(i);
            RegisteredInstance reg = registeredInstances.get(inst);
            positions[i] = instancePosition(inst);
            reg.updateVelocity(positions[i], dt);
            velocities[i] = reg.estimatedVelocity;
        }

        int[] parent = new int[count];
        for (int i = 0; i < count; i++) parent[i] = i;

        for (PhysicsJoint joint : registeredJoints) {
            int a = findInstanceIndex(joint.bodyA(), instanceList);
            int b = findInstanceIndex(joint.bodyB(), instanceList);
            if (a >= 0 && b >= 0) union(parent, a, b);
        }
        for (BallJoint joint : registeredBallJoints) {
            int a = findInstanceIndex(joint.bodyA(), instanceList);
            int b = findInstanceIndex(joint.bodyB(), instanceList);
            if (a >= 0 && b >= 0) union(parent, a, b);
        }

        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                ModelInstance instI = instanceList.get(i);
                ModelInstance instJ = instanceList.get(j);
                PairKey pairKey = new PairKey(instI, instJ);

                Vector3d diff = new Vector3d(positions[j]).sub(positions[i]);
                double dist = diff.length();

                double closingSpeed = 0.0;
                if (dist > 1e-4) {
                    Vector3d relV = new Vector3d(velocities[i].x - velocities[j].x,
                            velocities[i].y - velocities[j].y,
                            velocities[i].z - velocities[j].z);
                    closingSpeed = relV.dot(diff) / dist;
                }

                Cluster clusterI = clusterByInstance.get(instI);
                Cluster clusterJ = clusterByInstance.get(instJ);
                boolean wasSameCluster = clusterI != null && clusterI == clusterJ;

                if (wasSameCluster) {
                    double effectiveSplitRadius = splitRadius;
                    if (closingSpeed > 0.0 && velocityLookaheadSeconds > 0.0) {
                        effectiveSplitRadius += closingSpeed * velocityLookaheadSeconds;
                    }

                    if (dist <= effectiveSplitRadius) {
                        pairSeparationTimers.remove(pairKey);
                        union(parent, i, j);
                    } else {
                        double sepTime = pairSeparationTimers.getOrDefault(pairKey, 0.0) + Math.max(0.0, dt);
                        pairSeparationTimers.put(pairKey, sepTime);
                        if (sepTime < splitGraceSeconds) {
                            union(parent, i, j);
                        }
                    }
                } else {
                    double effectiveMergeRadius = clusterRadius;
                    if (closingSpeed > 0.0 && velocityLookaheadSeconds > 0.0) {
                        effectiveMergeRadius += closingSpeed * velocityLookaheadSeconds;
                    }

                    if (dist <= effectiveMergeRadius) {
                        pairSeparationTimers.remove(pairKey);
                        union(parent, i, j);
                    }
                }
            }
        }

        Map<Integer, List<ModelInstance>> groups = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int root = find(parent, i);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(instanceList.get(i));
        }

        List<Cluster> nextClusters = new ArrayList<>(groups.size());
        Set<Cluster> reusableClusters = Collections.newSetFromMap(new IdentityHashMap<>());
        reusableClusters.addAll(activeClusters);

        Map<ModelInstance, Cluster> nextClusterByInstance = new IdentityHashMap<>();

        for (List<ModelInstance> group : groups.values()) {
            Cluster chosenCluster = null;
            for (ModelInstance member : group) {
                Cluster candidate = clusterByInstance.get(member);
                if (candidate != null && reusableClusters.contains(candidate)) {
                    chosenCluster = candidate;
                    reusableClusters.remove(candidate);
                    break;
                }
            }

            if (chosenCluster == null) {
                Vector3d centroid = computeCentroid(group, positions, instanceList);
                MmdPhysicsScene newScene = new MmdPhysicsScene(centroid, substepCount);
                chosenCluster = new Cluster(newScene);
                if (environmentFactory != null) {
                    chosenCluster.environment = environmentFactory.create(newScene, centroid);
                    if (chosenCluster.environment != null) {
                        newScene.world().setCollisionEnvironment(chosenCluster.environment);
                    }
                }
            }

            chosenCluster.members.clear();
            for (ModelInstance member : group) {
                chosenCluster.members.add(member);
                nextClusterByInstance.put(member, chosenCluster);
                RegisteredInstance reg = registeredInstances.get(member);
                member.enablePhysics(chosenCluster.scene, reg.profile);
            }

            for (PhysicsJoint joint : registeredJoints) {
                int a = findInstanceIndex(joint.bodyA(), group);
                int b = findInstanceIndex(joint.bodyB(), group);
                if (a >= 0 && b >= 0 && !chosenCluster.joints.contains(joint)) {
                    chosenCluster.scene.addJoint(joint);
                    chosenCluster.joints.add(joint);
                }
            }
            for (BallJoint joint : registeredBallJoints) {
                int a = findInstanceIndex(joint.bodyA(), group);
                int b = findInstanceIndex(joint.bodyB(), group);
                if (a >= 0 && b >= 0 && !chosenCluster.ballJoints.contains(joint)) {
                    chosenCluster.scene.addJoint(joint);
                    chosenCluster.ballJoints.add(joint);
                }
            }

            nextClusters.add(chosenCluster);
        }

        for (Cluster abandoned : reusableClusters) {
            abandoned.close();
        }

        activeClusters.clear();
        activeClusters.addAll(nextClusters);
        clusterByInstance.clear();
        clusterByInstance.putAll(nextClusterByInstance);
    }

    public synchronized void step(float deltaSeconds) {
        ensureOpen();
        recluster(deltaSeconds);
        for (Cluster cluster : activeClusters) {
            cluster.scene.step(deltaSeconds);
        }
    }

    public synchronized void evaluateFrame(float partialTick, float deltaSeconds) {
        ensureOpen();
        recluster(deltaSeconds);
        for (Cluster cluster : activeClusters) {
            cluster.scene.evaluateFrame(partialTick, deltaSeconds);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Cluster cluster : activeClusters) {
            cluster.close();
        }
        activeClusters.clear();
        clusterByInstance.clear();
        pairSeparationTimers.clear();
        for (ModelInstance instance : registeredInstances.keySet()) {
            instance.detachPhysics();
        }
        registeredInstances.clear();
        registeredJoints.clear();
        registeredBallJoints.clear();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("MmdPhysicsClusterManager is closed");
    }

    private static Vector3d instancePosition(ModelInstance instance) {
        Vector3f local = instance.getSkeletonInstance().getTransform().getPosition();
        return new Vector3d(instance.getSkeletonInstance().getWorldOrigin()).add(local.x, local.y, local.z);
    }

    private static Vector3d computeCentroid(List<ModelInstance> group, Vector3d[] positions, List<ModelInstance> all) {
        Vector3d centroid = new Vector3d();
        for (ModelInstance instance : group) {
            int idx = all.indexOf(instance);
            centroid.add(positions[idx]);
        }
        return centroid.mul(1.0 / group.size());
    }

    private static int findInstanceIndex(SimBody body, List<ModelInstance> list) {
        for (int i = 0; i < list.size(); i++) {
            if (ownsBody(list.get(i), body)) return i;
        }
        return -1;
    }

    private static boolean ownsBody(ModelInstance instance, SimBody body) {
        MmdRagdoll ragdoll = instance.getRagdoll();
        return ragdoll != null && ragdoll.bodies().contains(body);
    }

    private static int find(int[] parent, int i) {
        if (parent[i] == i) return i;
        return parent[i] = find(parent, parent[i]);
    }

    private static void union(int[] parent, int i, int j) {
        int rootI = find(parent, i);
        int rootJ = find(parent, j);
        if (rootI != rootJ) parent[rootI] = rootJ;
    }

    private record PairKey(ModelInstance a, ModelInstance b) {
        PairKey {
            if (System.identityHashCode(a) > System.identityHashCode(b)) {
                ModelInstance tmp = a;
                a = b;
                b = tmp;
            }
        }

        boolean contains(ModelInstance instance) {
            return a == instance || b == instance;
        }
    }
}
