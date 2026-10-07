package lib.kasuga.rendering.models.uml.loaders;

import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.*;
import org.joml.Vector3f;

import java.util.*;

/** Builds and validates a rig before attaching its immutable definitions to a skeleton. */
public final class SkeletonDynamicsBuilder {
    private final Skeleton skeleton;
    private Physics physics;
    private final Map<String, IkChain> chains = new LinkedHashMap<>();
    private final Map<String, DiamondConstraint> diamonds = new LinkedHashMap<>();
    private final Map<Bone, BonePoseConstraint> poseConstraints = new LinkedHashMap<>();

    public SkeletonDynamicsBuilder(Skeleton skeleton) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        SkeletonDynamics current = skeleton.getDynamics();
        physics = current.physics();
        current.ikChains().forEach(chain -> chains.put(chain.name(), chain));
        current.diamonds().forEach(diamond -> diamonds.put(diamond.name(), diamond));
        poseConstraints.putAll(current.poseConstraints());
    }

    public Skeleton skeleton() { return skeleton; }
    public Bone bone(String name) {
        Bone bone = skeleton.getBoneMap().get(name);
        if (bone == null) throw new IllegalArgumentException("unknown bone: " + name);
        return bone;
    }
    public <T> SkeletonDynamicsBuilder read(T source, SkeletonDynamicsReader<? super T> reader) {
        Objects.requireNonNull(reader, "reader").read(source, this);
        return this;
    }
    public SkeletonDynamicsBuilder physics(Physics definition) {
        physics = Objects.requireNonNull(definition, "definition");
        return this;
    }
    public SkeletonDynamicsBuilder physics(List<RigidBody> bodies, List<Joint> joints) {
        return physics(new Physics(bodies, joints, new Vector3f(1), false, true, 1f, Set.of()));
    }
    public SkeletonDynamicsBuilder ik(IkChain chain) {
        chains.put(chain.name(), chain);
        return this;
    }
    public SkeletonDynamicsBuilder diamond(DiamondConstraint diamond) {
        diamonds.put(diamond.name(), diamond);
        return this;
    }

    public SkeletonDynamicsBuilder pose(BonePoseConstraint constraint) {
        poseConstraints.put(constraint.bone(), constraint);
        return this;
    }

    public SkeletonDynamics build() {
        Set<Bone> bones = Set.of(skeleton.getBones());
        for (BonePoseConstraint constraint : poseConstraints.values()) {
            requireBone(bones, constraint.bone());
            if (constraint.inheritance() != null) {
                requireBone(bones, constraint.inheritance().source());
                if (constraint.bone() == constraint.inheritance().source()) {
                    throw new IllegalArgumentException("bone cannot inherit itself");
                }
            }
            Set<Bone> visited = new HashSet<>();
            Bone current = constraint.bone();
            while (current != null) {
                if (!visited.add(current)) throw new IllegalArgumentException("cyclic transform inheritance");
                BonePoseConstraint next = poseConstraints.get(current);
                current = next == null || next.inheritance() == null ? null : next.inheritance().source();
            }
        }
        for (RigidBody body : physics.bodies()) if (body.bone() != null) requireBone(bones, body.bone());
        for (Bone bone : physics.bindPoseFollowers()) requireBone(bones, bone);
        for (Joint joint : physics.joints()) {
            if (joint.rigidBodyA() < 0 || joint.rigidBodyB() < 0
                    || joint.rigidBodyA() >= physics.bodies().size() || joint.rigidBodyB() >= physics.bodies().size()
                    || joint.rigidBodyA() == joint.rigidBodyB()) {
                throw new IllegalArgumentException("invalid body references in joint: " + joint.name());
            }
        }
        for (IkChain chain : chains.values()) {
            requireBone(bones, chain.controller()); requireBone(bones, chain.effector());
            Set<Bone> links = new HashSet<>();
            for (int index = 0; index < chain.links().size(); index++) {
                Bone link = chain.links().get(index).bone();
                requireBone(bones, link);
                Bone endpoint = index + 1 < chain.links().size()
                        ? chain.links().get(index + 1).bone() : chain.effector();
                if (!links.add(link) || link == endpoint || !isAncestor(link, endpoint)) {
                    throw new IllegalArgumentException("IK links must follow the hierarchy: " + chain.name());
                }
            }
        }
        Set<String> closedChains = new HashSet<>();
        for (DiamondConstraint diamond : diamonds.values()) {
            IkChain first = chains.get(diamond.firstChain()), second = chains.get(diamond.secondChain());
            if (first == null || second == null || first.targetMode() != TargetMode.POSITION
                    || second.targetMode() != TargetMode.POSITION) {
                throw new IllegalArgumentException("diamond must reference two position IK chains: " + diamond.name());
            }
            if (!closedChains.add(first.name()) || !closedChains.add(second.name())) {
                throw new IllegalArgumentException("IK chain belongs to multiple diamonds: " + diamond.name());
            }
            Set<Bone> driven = new HashSet<>();
            first.links().forEach(link -> driven.add(link.bone()));
            if (second.links().stream().anyMatch(link -> driven.contains(link.bone()))) {
                throw new IllegalArgumentException("diamond branches must have independent driver bones");
            }
            Bone firstBase = first.links().getFirst().bone(), secondBase = second.links().getFirst().bone();
            if (skeleton.getBindingAbsolute(firstBase).getPosition()
                    .distance(skeleton.getBindingAbsolute(secondBase).getPosition()) > diamond.tolerance()) {
                throw new IllegalArgumentException("diamond bases must share a bind-pose pivot");
            }
            if (first.effector() == second.effector()
                    || first.links().stream().anyMatch(link -> isAncestor(link.bone(), secondBase))
                    || second.links().stream().anyMatch(link -> isAncestor(link.bone(), firstBase))) {
                throw new IllegalArgumentException("diamond branches must be independent hierarchy branches");
            }
        }
        return new SkeletonDynamics(physics, new ArrayList<>(chains.values()), new ArrayList<>(diamonds.values()),
                new ArrayList<>(poseConstraints.values()));
    }

    public SkeletonDynamics attach() {
        SkeletonDynamics result = build();
        skeleton.setDynamics(result);
        return result;
    }

    private static void requireBone(Set<Bone> bones, Bone bone) {
        if (!bones.contains(bone)) throw new IllegalArgumentException("bone belongs to another skeleton: " + bone.getName());
    }
    private static boolean isAncestor(Bone ancestor, Bone descendant) {
        for (Bone current = descendant.getParent(); current != null; current = current.getParent()) {
            if (current == ancestor) return true;
        }
        return false;
    }
}
