package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;

/**
 * PMX sections following the bone table.  Keeping the lossless PMX data next
 * to the render model lets animation and physics backends opt in without
 * making the binary loader depend on a particular runtime implementation.
 */
public record PmxTail(
        List<PmxMorph> morphs,
        List<PmxDisplayFrame> displayFrames,
        List<PmxRigidBody> rigidBodies,
        List<PmxJoint> joints,
        List<PmxSoftBody> softBodies
) {
    public PmxTail {
        morphs = List.copyOf(morphs);
        displayFrames = List.copyOf(displayFrames);
        rigidBodies = List.copyOf(rigidBodies);
        joints = List.copyOf(joints);
        softBodies = List.copyOf(softBodies);
    }

    public enum MorphKind {
        GROUP, VERTEX, BONE, UV, ADDITIONAL_UV_1, ADDITIONAL_UV_2,
        ADDITIONAL_UV_3, ADDITIONAL_UV_4, MATERIAL, FLIP, IMPULSE;

        public static MorphKind fromId(int id) {
            if (id < 0 || id >= values().length) {
                throw new IllegalArgumentException("Unsupported PMX morph type: " + id);
            }
            return values()[id];
        }
    }

    public record PmxMorph(
            String localName,
            String universalName,
            int panel,
            MorphKind kind,
            List<PmxMorphOffset> offsets
    ) {
        public PmxMorph {
            offsets = List.copyOf(offsets);
        }
    }

    public sealed interface PmxMorphOffset permits GroupOffset, VertexOffset,
            BoneOffset, UvOffset, MaterialOffset, ImpulseOffset {}

    /** Used by both group and PMX 2.1 flip morphs; the parent kind preserves the semantics. */
    public record GroupOffset(int morphIndex, float weight) implements PmxMorphOffset {}

    public record VertexOffset(int vertexIndex, Vector3f displacement) implements PmxMorphOffset {}

    public record BoneOffset(
            int boneIndex,
            Vector3f translation,
            Quaternionf rotation
    ) implements PmxMorphOffset {}

    /** layer 0 is the base UV and layers 1..4 are the additional vec4 channels. */
    public record UvOffset(
            int vertexIndex,
            int layer,
            Vector4f displacement
    ) implements PmxMorphOffset {}

    public record MaterialOffset(
            int materialIndex,
            int operation,
            Vector4f diffuse,
            Vector3f specular,
            float shininess,
            Vector3f ambient,
            Vector4f edgeColor,
            float edgeSize,
            Vector4f textureTint,
            Vector4f sphereTint,
            Vector4f toonTint
    ) implements PmxMorphOffset {}

    public record ImpulseOffset(
            int rigidBodyIndex,
            boolean local,
            Vector3f velocity,
            Vector3f torque
    ) implements PmxMorphOffset {}

    public record PmxDisplayFrame(
            String localName,
            String universalName,
            boolean special,
            List<DisplayElement> elements
    ) {
        public PmxDisplayFrame {
            elements = List.copyOf(elements);
        }
    }

    public record DisplayElement(boolean morph, int index) {}

    public record PmxRigidBody(
            String localName,
            String universalName,
            int boneIndex,
            int collisionGroup,
            int nonCollisionMask,
            int shape,
            Vector3f size,
            Vector3f position,
            Vector3f rotation,
            float mass,
            float linearDamping,
            float angularDamping,
            float restitution,
            float friction,
            int mode
    ) {}

    public record PmxJoint(
            String localName,
            String universalName,
            int type,
            int rigidBodyA,
            int rigidBodyB,
            Vector3f position,
            Vector3f rotation,
            Vector3f positionMin,
            Vector3f positionMax,
            Vector3f rotationMin,
            Vector3f rotationMax,
            Vector3f positionSpring,
            Vector3f rotationSpring
    ) {}

    public record PmxSoftBody(
            String localName,
            String universalName,
            int shape,
            int materialIndex,
            int collisionGroup,
            int nonCollisionMask,
            int flags,
            int bendingLinkDistance,
            int clusterCount,
            float totalMass,
            float collisionMargin,
            int aeroModel,
            SoftBodyConfig config,
            SoftBodyCluster cluster,
            SoftBodyIteration iteration,
            SoftBodyMaterial material,
            List<SoftBodyAnchor> anchors,
            List<Integer> pinnedVertices
    ) {
        public PmxSoftBody {
            anchors = List.copyOf(anchors);
            pinnedVertices = List.copyOf(pinnedVertices);
        }
    }

    public record SoftBodyConfig(
            float velocityCorrection,
            float damping,
            float drag,
            float lift,
            float pressure,
            float volumeConservation,
            float dynamicFriction,
            float poseMatching,
            float rigidContactHardness,
            float kineticContactHardness,
            float softContactHardness,
            float anchorHardness
    ) {}

    public record SoftBodyCluster(
            float softRigidHardness,
            float softKineticHardness,
            float softSoftHardness,
            float softRigidImpulseSplit,
            float softKineticImpulseSplit,
            float softSoftImpulseSplit
    ) {}

    public record SoftBodyIteration(
            int velocity,
            int position,
            int drift,
            int cluster
    ) {}

    public record SoftBodyMaterial(float linear, float angular, float volume) {}

    public record SoftBodyAnchor(int rigidBodyIndex, int vertexIndex, boolean nearMode) {}
}
