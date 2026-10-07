package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsBuilder;
import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsReader;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.*;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.MmdModelData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import org.joml.Vector3f;

import java.util.*;

/** PMX and converted PMD are decoded once; runtime physics and IK never consume their raw tables. */
public final class MmdSkeletonDynamicsReader implements SkeletonDynamicsReader<MmdModelData> {
    @Override
    public void read(MmdModelData data, SkeletonDynamicsBuilder builder) {
        List<Bone> bones = pmxBones(builder.skeleton());
        List<RigidBody> bodies = data.tail().rigidBodies().stream().map(body -> new RigidBody(
                body.localName(), body.universalName(), bone(bones, body.boneIndex()),
                body.collisionGroup(), body.nonCollisionMask(), body.shape(), body.size(),
                body.position(), body.rotation(), body.mass(), body.linearDamping(),
                body.angularDamping(), body.restitution(), body.friction(), body.mode())).toList();
        // PMX permits -1 references. Such joints cannot be simulated and are omitted.
        List<Joint> joints = data.tail().joints().stream()
                .filter(joint -> joint.rigidBodyA() >= 0 && joint.rigidBodyB() >= 0
                        && joint.rigidBodyA() < bodies.size() && joint.rigidBodyB() < bodies.size()
                        && joint.rigidBodyA() != joint.rigidBodyB())
                .map(joint -> new Joint(joint.localName(), joint.universalName(),
                        joint.rigidBodyA(), joint.rigidBodyB(), joint.position(), joint.rotation(),
                        joint.positionMin(), joint.positionMax(), joint.rotationMin(), joint.rotationMax(),
                        joint.positionSpring(), joint.rotationSpring())).toList();
        builder.physics(new Physics(bodies, joints, data.modelScale(), false, false, 1f, Set.of()));
        readBoneConstraints(builder);
        readIk(builder);
    }

    /** Converts file-index references and flags into direct, format-neutral bone constraints. */
    public static void readBoneConstraints(SkeletonDynamicsBuilder builder) {
        List<Bone> bones = pmxBones(builder.skeleton());
        for (Bone target : bones) {
            PmxBone definition = (PmxBone) target.getBoneData();
            TransformInheritance inheritance = null;
            if (definition.inherit != null) {
                Bone source = bone(bones, definition.inherit.parentIndex().intValue());
                if (source != null && source != target
                        && (definition.flags.inheritParentTranslation || definition.flags.inheritParentRotation)) {
                    inheritance = new TransformInheritance(source, definition.inherit.weight(),
                            definition.flags.inheritParentTranslation, definition.flags.inheritParentRotation);
                }
            }
            Vector3f axis = definition.flags.isAxisFixed && definition.fixedAxis != null
                    && definition.fixedAxis.lengthSquared() >= 1e-8f ? definition.fixedAxis : null;
            if (inheritance != null || axis != null) builder.pose(new BonePoseConstraint(target, inheritance, axis));
        }
    }

    /** Also usable when an application supplies PMX bones without a complete model tail. */
    public static void readIk(SkeletonDynamicsBuilder builder) {
        Skeleton skeleton = builder.skeleton();
        List<Bone> bones = pmxBones(skeleton);
        for (Bone controller : bones) {
            PmxBone definition = (PmxBone) controller.getBoneData();
            if (definition.ik == null || definition.ik.CCD_Count <= 0) continue;
            Bone effector = bone(bones, definition.ik.boneIndex.intValue());
            if (effector == null) continue;
            List<IkLink> links = new ArrayList<>();
            for (var link : definition.ik.chains) {
                Bone driver = bone(bones, link.boneIndex.intValue());
                if (driver == null) continue;
                RotationLimit limit = link.useRotationLimit && link.limit != null
                        ? new RotationLimit(link.limit.min(), link.limit.max()) : null;
                links.add(new IkLink(driver, limit));
            }
            Collections.reverse(links); // PMX stores tip-to-base order.
            if (links.isEmpty()) continue;
            for (int index = 0; index < links.size(); index++) {
                IkLink link = links.get(index);
                Bone endpoint = index + 1 < links.size() ? links.get(index + 1).bone() : effector;
                links.set(index, new IkLink(link.bone(), link.limit(),
                        hinge(skeleton, link.bone(), endpoint, link.limit())));
            }
            boolean direction = links.size() == 1 && definition.tailObject instanceof Vector3f
                    && skeleton.getBindingAbsolute(controller).getPosition()
                    .distanceSquared(skeleton.getBindingAbsolute(effector).getPosition()) < 1e-4f;
            builder.ik(new IkChain(controller.getName(), controller, effector, links,
                    Math.min(definition.ik.CCD_Count, 256), 1e-4f,
                    Math.abs(definition.ik.boneRotationLimit), true,
                    direction ? TargetMode.DIRECTION : TargetMode.POSITION));
        }
    }

    private static List<Bone> pmxBones(Skeleton skeleton) {
        return Arrays.stream(skeleton.getBones()).filter(bone -> bone.getBoneData() instanceof PmxBone).toList();
    }

    private static Hinge hinge(Skeleton skeleton, Bone driver, Bone effector, RotationLimit limit) {
        if (limit == null) return null;
        Vector3f min = limit.minimum(), max = limit.maximum();
        int freeAxis = -1;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(max.get(axis) - min.get(axis)) > 1e-6f) {
                if (freeAxis >= 0) return null;
                freeAxis = axis;
            } else if (Math.abs(min.get(axis)) > 1e-6f) return null;
        }
        if (freeAxis < 0 || min.get(freeAxis) < -(float) Math.PI || max.get(freeAxis) > (float) Math.PI) return null;
        Vector3f axis = new Vector3f().setComponent(freeAxis, 1f);
        Vector3f reference = skeleton.getBindingAbsolute(effector).getPosition()
                .sub(skeleton.getBindingAbsolute(driver).getPosition());
        skeleton.getBindingAbsolute(driver).invertTransform().transformDirection(reference);
        reference.fma(-reference.dot(axis), axis);
        if (reference.lengthSquared() < 1e-12f) return null;
        return new Hinge(axis, reference.normalize(), min.get(freeAxis), max.get(freeAxis));
    }
    private static Bone bone(List<Bone> bones, int index) {
        return index >= 0 && index < bones.size() ? bones.get(index) : null;
    }
}
