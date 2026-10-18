package lib.kasuga.rendering.models.uml.math.binding;

import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.DualQuaternion;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.SDEFData;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.SDEFBoneBindingData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.structure.Pair;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface BoneBindingFunc {

    Vertex apply(Vertex vertex, List<BoneContext> context);

    BoneBindingFunc IDENTITY = (vertex, context) -> vertex;

    BoneBindingFunc BDEF = (vertex, context) -> {
        if (context.isEmpty()) {
            return IDENTITY.apply(vertex, context);
        }
        Vector3f posFinal = new Vector3f();
        HashMap<Mesh, Vector3f> normalFinals = new HashMap<>();
        for (BoneContext<BoneData> boneContext : context) {
            Vector3f posLocal = new Vector3f(vertex.getPosition());
            posLocal = boneContext.invTransform().apply(posLocal);
            Vector3f posTransformed = boneContext.absTransform().apply(posLocal);
            posTransformed.mul(boneContext.weight());
            posFinal.add(posTransformed);
            for (Map.Entry<Mesh, Vector3f> meshAndNormal : vertex.getNormals().entrySet()) {
                Vector3f normalTransformed = new Vector3f(meshAndNormal.getValue());
                boneContext.invTransform().normal().transform(normalTransformed);
                boneContext.absTransform().normal().transform(normalTransformed);
                normalTransformed.mul(boneContext.weight());
                if (normalFinals.containsKey(meshAndNormal.getKey())) {
                    normalFinals.get(meshAndNormal.getKey()).add(normalTransformed);
                } else {
                    normalFinals.put(meshAndNormal.getKey(), normalTransformed);
                }
            }
        }
        normalFinals.forEach((k, v) -> v.normalize());
        return new Vertex(vertex, posFinal, normalFinals);
    };

    BoneBindingFunc SDEF = (vertex, context) -> {
        if (context.size() < 2) {
            return BDEF.apply(vertex, context);
        }

        BoneContext<BoneData> boneCtx0 = context.get(0);
        BoneContext<BoneData> boneCtx1 = context.get(1);
        Object bd = vertex.getBinding().getData();
        if (!(bd instanceof SDEFBoneBindingData sdefData) || sdefData.getSDEFData() == null) {
            return BDEF.apply(vertex, context);
        }
        SDEFData sdef = sdefData.getSDEFData();
        float w0 = boneCtx0.weight(), w1 = boneCtx1.weight();
        float s = w0 + w1;
        if (!Float.isFinite(s) || Math.abs(s) < 1.0e-6f) {
            return BDEF.apply(vertex, context);
        }
        w0 /= s;
        w1 /= s;

        // PMX stores C/R0/R1 in model space. Build the two skinning
        // transforms (animated absolute * inverse bind) before applying the
        // standard SDEF spherical interpolation formula.
        Transform skin0 = boneCtx0.absTransform().copy().mul(boneCtx0.invTransform());
        Transform skin1 = boneCtx1.absTransform().copy().mul(boneCtx1.invTransform());
        Quaternionf rotation0 = skin0.getRotation().normalize();
        Quaternionf rotation1 = skin1.getRotation().normalize();
        if (rotation0.dot(rotation1) < 0.0f) {
            rotation1.set(-rotation1.x, -rotation1.y, -rotation1.z, -rotation1.w);
        }
        Quaternionf rotation = new Quaternionf(rotation0).slerp(rotation1, w1).normalize();

        Vector3f center = sdef.c();
        Vector3f radiusBlend = new Vector3f(sdef.r0()).mul(w0)
                .add(new Vector3f(sdef.r1()).mul(w1));
        Vector3f posFinal = new Vector3f(vertex.getPosition())
                .sub(center)
                .sub(radiusBlend);
        rotation.transform(posFinal);
        posFinal.add(
                skin0.apply(new Vector3f(center).add(sdef.r0())).mul(w0)
                        .add(skin1.apply(new Vector3f(center).add(sdef.r1())).mul(w1))
        );

        HashMap<Mesh, Vector3f> normalFinals = new HashMap<>();
        for (var e : vertex.getNormals().entrySet()) {
            Vector3f nf = rotation.transform(new Vector3f(e.getValue())).normalize();
            normalFinals.put(e.getKey(), nf);
        }
        return new Vertex(vertex, posFinal, normalFinals);
    };

    BoneBindingFunc QDEF = (vertex, context) -> {
        if (context.isEmpty()) {
            return IDENTITY.apply(vertex, context);
        }
        Vector3f posOrg = vertex.getPosition();
        List<Pair<DualQuaternion, Float>> dualQuaternions = new ArrayList<>();
        Transform skinningMatrix = new Transform();
        for (BoneContext<BoneData> boneContext : context) {
            skinningMatrix.set(boneContext.absTransform()).mul(boneContext.invTransform());
            DualQuaternion dq = skinningMatrix.toDualQuaternion();
            dualQuaternions.add(Pair.of(dq, boneContext.weight()));
        }
        DualQuaternion result = DualQuaternion.blend(dualQuaternions);
        Vector3f posFinal = result.transformPoint(new Vector3f(posOrg));
        HashMap<Mesh, Vector3f> normalFinals = new HashMap<>();
        for (Map.Entry<Mesh, Vector3f> meshAndNormal : vertex.getNormals().entrySet()) {
            Vector3f normal = new Vector3f(meshAndNormal.getValue());
            normal = result.getReal().transform(normal);
            normal.normalize();
            normalFinals.put(meshAndNormal.getKey(), normal);
        }
        return new Vertex(vertex, posFinal, normalFinals);
    };
}
