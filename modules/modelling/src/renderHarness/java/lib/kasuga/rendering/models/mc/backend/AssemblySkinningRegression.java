package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.BonePalettePacker;
import lib.kasuga.rendering.models.uml.backend.gpu.*;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssemblyBuilder;
import lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssemblyProbe;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.SDEFBoneBindingData;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.*;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.*;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import org.joml.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.*;
import java.util.*;

import static lib.kasuga.rendering.models.mc.backend.GlFixture.*;

/** Checks remapped garment bone indices against the production CPU and GPU skinning paths. */
final class AssemblySkinningRegression {
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void run() throws Exception {
        Model body = source(false), garment = source(true);
        Model combined = new ModelAssemblyBuilder("body", body, 0).part("skirt", garment, 0).part("coat", garment, 0)
                .assemble().model();
        verify(combined, false);
        String local = System.getProperty("kasuga.assembly.fixtures");
        if (local != null) verify(ModelAssemblyProbe.loadCompatibleOutfit(java.nio.file.Path.of(local)), true);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void verify(Model combined, boolean real) throws Exception {
        try (ModelInstance instance = new ModelInstance(combined, null, null, null, null, null);
             var fixture = new GlFixture(); var vertexRing = new GpuUploadRing(); var boneRing = new TextureUploadRing()) {
            instance.getSkeletonInstance().rotate(real ? "左腕" : "arm", new Quaternionf().rotateZ(0.7f));
            if (!real) {
                instance.getSkeletonInstance().rotate("skirt/hem", new Quaternionf().rotateZ(0.3f));
                instance.getSkeletonInstance().rotate("coat/hem", new Quaternionf().rotateZ(-0.3f));
            }
            instance.updateImmediate();
            int count = combined.getVertices().length;
            ByteBuffer packed = MemoryUtil.memCalloc(count * 104).order(ByteOrder.nativeOrder());
            FloatBuffer palette = MemoryUtil.memAllocFloat(combined.getBones().length * 36);
            FloatBuffer result = MemoryUtil.memAllocFloat(count * 3);
            int program = GlslProgram.link(resource("/assets/kasuga_lib/shaders/ksg_skinning.transform.glsl"),
                    null, SkinningAttributes.LOCATIONS, new String[]{"tf_Position"});
            int output = GL15.glGenBuffers();
            try {
                for (int i = 0; i < count; i++) {
                    Vertex vertex = combined.getVertices()[i];
                    int offset = i * 104;
                    packed.putFloat(offset, vertex.getPosition().x).putFloat(offset + 4, vertex.getPosition().y)
                            .putFloat(offset + 8, vertex.getPosition().z);
                    packed.put(offset + 14, (byte) 127);
                    packed.putFloat(offset + 16, 1).putFloat(offset + 28, 1);
                    var func = vertex.getBinding().getFunc();
                    packed.putInt(offset + 32, func == BoneBindingFunc.SDEF ? 1 : func == BoneBindingFunc.QDEF ? 2 : 0);
                    if (vertex.getBinding().getData() instanceof SDEFBoneBindingData data && data.getSDEFData() != null) {
                        var sdef = data.getSDEFData();
                        vector(packed, offset + 68, sdef.r0()); vector(packed, offset + 80, sdef.r1()); vector(packed, offset + 92, sdef.c());
                    }
                    int weightIndex = 0;
                    for (var weight : vertex.getBinding().getWeights()) {
                        packed.putFloat(offset + 36 + weightIndex * 4, weight.getFirst().getIndex());
                        packed.putFloat(offset + 52 + weightIndex * 4, weight.getSecond());
                        weightIndex++;
                    }
                }
                for (Bone bone : combined.getBones()) {
                    Transform absolute = instance.getSkeletonInstance().getAbsoluteTransforms().get(bone);
                    BonePalettePacker.put(palette, absolute.transform(), combined.getSkeleton().getBindingInverse(bone).transform(), absolute.normal());
                }
                palette.flip();
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, boneRing.upload(palette));
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexRing.upload(packed));
                SkinningAttributes.configure(104, 0, 12, 16, 32, 36, 52, 68, 80, 92);
                GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, output);
                GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, (long) count * 12, GL15.GL_DYNAMIC_READ);
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, output);
                GL20.glUseProgram(program); integer(program, "ksg_BoneTransforms", 0);
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
                vertexRing.markSubmitted(); boneRing.markSubmitted();
                GL30.glBeginTransformFeedback(GL11.GL_POINTS);
                GL11.glDrawArrays(GL11.GL_POINTS, 0, count);
                GL30.glEndTransformFeedback();
                GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, result);
                for (int i = 0; i < count; i++) {
                    Vertex vertex = combined.getVertices()[i];
                    List<BoneContext> contexts = new ArrayList<>();
                    for (var weight : vertex.getBinding().getWeights()) {
                        Bone bone = weight.getFirst();
                        contexts.add(new BoneContext(bone, weight.getSecond(), bone.getBoneData(), bone.getTransform(),
                                combined.getSkeleton().getBindingAbsolute(bone), instance.getSkeletonInstance().getAbsoluteTransforms().get(bone),
                                combined.getSkeleton().getBindingInverse(bone)));
                    }
                    Vector3f cpu = vertex.getBinding().getFunc().apply(vertex, (List) contexts).getPosition();
                    expect(result.get(i * 3), cpu.x, 1e-5, "assembled garment GPU x " + i);
                    expect(result.get(i * 3 + 1), cpu.y, 1e-5, "assembled garment GPU y " + i);
                    expect(result.get(i * 3 + 2), cpu.z, 1e-5, "assembled garment GPU z " + i);
                }
                noError("assembled garment transform feedback");
                if (real) System.out.println("REAL_OVERLAY_SKINNING_PASS vertices=" + count + " bones=" + combined.getBones().length);
            } finally {
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
                GL20.glDeleteProgram(program); GL15.glDeleteBuffers(output);
                MemoryUtil.memFree(packed); MemoryUtil.memFree(palette); MemoryUtil.memFree(result);
            }
        }
    }

    private static void vector(ByteBuffer buffer, int offset, Vector3f value) {
        buffer.putFloat(offset, value.x).putFloat(offset + 4, value.y).putFloat(offset + 8, value.z);
    }

    @SuppressWarnings("unchecked")
    private static Model source(boolean extra) {
        Bone root = new Bone("root", new Transform(), null), arm = new Bone("arm", new Transform().translate(0, 1, 0), null);
        root.setChildren(new Bone[]{arm}); arm.setParent(root);
        List<Bone> bones = new ArrayList<>(List.of(root, arm));
        Bone driver = arm;
        if (extra) {
            Bone hem = new Bone("hem", new Transform().translate(0, 1, 0), null);
            arm.setChildren(new Bone[]{hem}); hem.setParent(arm); bones.add(hem); driver = hem;
        }
        Vertex[] vertices = new Vertex[3];
        for (int i = 0; i < vertices.length; i++) {
            vertices[i] = new Vertex(new Vector3f(i + 1, extra ? 2 : 1, 0), null);
            vertices[i].setBinding(new BoneBinding(new Pair[]{Pair.of(driver, 1f)}, BoneBindingFunc.BDEF, null));
        }
        Mesh mesh = new Mesh(vertices, new Vector3f(0, 0, 1), new Transform(), new Material[0], null);
        Skeleton skeleton = new Skeleton(bones.toArray(Bone[]::new), root, new Anchor[0], null, new Transform());
        return new Model(vertices, new Mesh[]{mesh}, skeleton.getBones(), skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
    }
}
