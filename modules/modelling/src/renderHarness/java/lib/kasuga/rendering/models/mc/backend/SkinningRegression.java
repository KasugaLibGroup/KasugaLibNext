package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.BonePalettePacker;
import lib.kasuga.rendering.models.uml.backend.gpu.GlslProgram;
import lib.kasuga.rendering.models.uml.backend.gpu.GpuUploadRing;
import lib.kasuga.rendering.models.uml.backend.gpu.TextureUploadRing;
import lib.kasuga.rendering.models.uml.backend.gpu.SkinningAttributes;
import org.joml.*;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.DualQuaternion;
import lib.kasuga.structure.Pair;
import java.util.List;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import static lib.kasuga.rendering.models.mc.backend.GlFixture.*;

/** Exercises the production samplerBuffer ABI, integer/float attributes and TF shader. */
final class SkinningRegression {
    static void run() throws Exception {
        try (var fixture = new GlFixture(); var sourceRing = new GpuUploadRing(); var boneRing = new TextureUploadRing()) {
            int program = GlslProgram.link(resource("/assets/kasuga_lib/shaders/ksg_skinning.transform.glsl"),
                    null, SkinningAttributes.LOCATIONS, new String[]{"tf_Position"});
            int output=GL15.glGenBuffers();
            ByteBuffer vertices=MemoryUtil.memCalloc(5*104).order(ByteOrder.nativeOrder());
            FloatBuffer bones=MemoryUtil.memAllocFloat(72), result=MemoryUtil.memAllocFloat(15);
            try {
                for(int i=0;i<5;i++) {
                    int offset=i*104;
                    vertices.putFloat(offset,1).putFloat(offset+4,2).putFloat(offset+8,3);
                    vertices.put(offset+14,(byte)127);
                    vertices.putFloat(offset+16,1).putFloat(offset+28,1);
                    vertices.putInt(offset+32,i==3?1:i==4?2:0);
                    vertices.putFloat(offset+36,0).putFloat(offset+40,1);
                    vertices.putFloat(offset+52,i==0?1:i==1?0:.25f);
                    vertices.putFloat(offset+56,i==0?0:i==1?1:.75f);
                }
                BonePalettePacker.put(bones,new Matrix4f().translation(2,0,0),new Matrix4f().translation(-1,0,0),new Matrix3f());
                BonePalettePacker.put(bones,new Matrix4f().translation(0,4,0),new Matrix4f().translation(0,-2,0),new Matrix3f());
                bones.flip();
                bindBones(boneRing, bones);
                bindVertices(sourceRing, vertices);
                int vanillaLocation = RenderState.UML_VERTEX_FORMAT.getElements().indexOf(RenderState.BONE_BINDING_TYPE);
                if (vanillaLocation == 8) throw new AssertionError("Fixture must distinguish vanilla and TF attribute locations");
                // Vanilla ShaderInstance binds names in vertex-format order; TF uses its own fixed map.
                GL20.glVertexAttribPointer(vanillaLocation,1,GL11.GL_INT,false,104,32L);
                SkinningAttributes.bindingType(vanillaLocation,104,32);
                if (GL20.glGetVertexAttribi(vanillaLocation,GL30.GL_VERTEX_ATTRIB_ARRAY_INTEGER) != 1)
                    throw new AssertionError("Vanilla binding type must be an integer attribute");
                GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,output);
                GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,60,GL15.GL_DYNAMIC_READ);
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,output);
                GL20.glUseProgram(program); integer(program,"ksg_BoneTransforms",0);
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
                dispatch(result, sourceRing, boneRing);
                float[] expected={2,2,3, 1,4,3, 1.25f,3.5f,3, 1.25f,3.5f,3, 1.25f,3.5f,3};
                verify(result,expected,"BDEF/SDEF/QDEF translations and nonzero indices");
                // Upload the same source allocation after a morph, then animate bone 1.
                vertices.putFloat(104,5);
                bindVertices(sourceRing, vertices);
                expected[3]=5;
                dispatch(result, sourceRing, boneRing); verify(result,expected,"morphed source reupload");
                bones.clear();
                BonePalettePacker.put(bones,new Matrix4f().translation(2,0,0),new Matrix4f().translation(-1,0,0),new Matrix3f());
                BonePalettePacker.put(bones,new Matrix4f().translation(0,6,0),new Matrix4f().translation(0,-2,0),new Matrix3f());
                bones.flip(); bindBones(boneRing, bones);
                expected[4]=6; expected[7]=5; expected[10]=5; expected[13]=5;
                dispatch(result, sourceRing, boneRing); verify(result,expected,"bone palette update");
                // A rotating bone makes the blended real quaternion non-unit before
                // normalization. Compare the production GPU path to the production CPU DQ.
                Matrix4f absolute0 = new Matrix4f().translation(2,0,0);
                Matrix4f absolute1 = new Matrix4f().translation(0,6,0).rotateZ((float)java.lang.Math.PI/2);
                Matrix4f inverse0 = new Matrix4f().translation(-1,0,0), inverse1 = new Matrix4f().translation(0,-2,0);
                bones.clear();
                BonePalettePacker.put(bones,absolute0,inverse0,new Matrix3f());
                BonePalettePacker.put(bones,absolute1,inverse1,new Matrix3f(absolute1));
                bones.flip(); bindBones(boneRing, bones);
                dispatch(result, sourceRing, boneRing);
                Matrix4f skin0 = new Matrix4f(absolute0).mul(inverse0), skin1 = new Matrix4f(absolute1).mul(inverse1);
                DualQuaternion blend = DualQuaternion.blend(List.of(
                        Pair.of(new Transform(skin0,new Matrix3f(skin0)).toDualQuaternion(),.25f),
                        Pair.of(new Transform(skin1,new Matrix3f(skin1)).toDualQuaternion(),.75f)));
                Vector3f cpu = blend.transformPoint(new Vector3f(1,2,3));
                expect(result.get(12),cpu.x,1e-5,"rotating QDEF CPU/GPU x");
                expect(result.get(13),cpu.y,1e-5,"rotating QDEF CPU/GPU y");
                expect(result.get(14),cpu.z,1e-5,"rotating QDEF CPU/GPU z");
                // Opposing rotations require quaternion hemisphere correction without
                // turning the original positive skin weights into a negative sum.
                absolute0.identity().translation(2,0,0).rotateX((float)java.lang.Math.toRadians(100));
                absolute1.identity().translation(0,6,0).rotateX((float)java.lang.Math.toRadians(-100));
                bones.clear();
                BonePalettePacker.put(bones,absolute0,inverse0,new Matrix3f(absolute0));
                BonePalettePacker.put(bones,absolute1,inverse1,new Matrix3f(absolute1));
                bones.flip(); bindBones(boneRing, bones); dispatch(result, sourceRing, boneRing);
                skin0.set(absolute0).mul(inverse0); skin1.set(absolute1).mul(inverse1);
                blend = DualQuaternion.blend(List.of(
                        Pair.of(new Transform(skin0,new Matrix3f(skin0)).toDualQuaternion(),.25f),
                        Pair.of(new Transform(skin1,new Matrix3f(skin1)).toDualQuaternion(),.75f)));
                cpu = blend.transformPoint(new Vector3f(1,2,3));
                expect(result.get(12),cpu.x,1e-5,"opposing QDEF CPU/GPU x");
                expect(result.get(13),cpu.y,1e-5,"opposing QDEF CPU/GPU y");
                expect(result.get(14),cpu.z,1e-5,"opposing QDEF CPU/GPU z");
                noError("production transform feedback");
            } finally {
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,0);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,0); GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,0);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,0);
                GL20.glDeleteProgram(program);
                GL15.glDeleteBuffers(output);
                MemoryUtil.memFree(vertices);MemoryUtil.memFree(bones);MemoryUtil.memFree(result);
            }
        }
    }
    private static void bindVertices(GpuUploadRing ring, ByteBuffer data) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ring.upload(data));
        SkinningAttributes.configure(104,0,12,16,32,36,52,68,80,92);
    }
    private static void bindBones(TextureUploadRing ring, FloatBuffer data) {
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, ring.upload(data));
    }
    private static void dispatch(FloatBuffer result, GpuUploadRing source, TextureUploadRing bones) {
        source.markSubmitted(); bones.markSubmitted();
        GL30.glBeginTransformFeedback(GL11.GL_POINTS); GL11.glDrawArrays(GL11.GL_POINTS,0,5); GL30.glEndTransformFeedback();
        result.clear(); GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,result);
    }
    private static void verify(FloatBuffer actual,float[] expected,String message) {
        for(int i=0;i<expected.length;i++) expect(actual.get(i),expected[i],1e-5,message+" component="+i);
    }
}
