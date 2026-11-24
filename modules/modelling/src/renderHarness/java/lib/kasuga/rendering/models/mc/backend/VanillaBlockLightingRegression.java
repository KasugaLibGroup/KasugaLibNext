package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.BonePalettePacker;
import lib.kasuga.rendering.models.uml.backend.gpu.TextureUploadRing;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Arrays;

import static lib.kasuga.rendering.models.mc.backend.GlFixture.*;

/** Actual production vertex/fragment programs, compared with Minecraft's unmodified block/item shaders. */
final class VanillaBlockLightingRegression {
    private static final Vector3f[] NORMALS = {
            new Vector3f(0, -1, 0), new Vector3f(0, 1, 0),
            new Vector3f(0, 0, -1), new Vector3f(0, 0, 1),
            new Vector3f(-1, 0, 0), new Vector3f(1, 0, 0)
    };
    private static final float[] SHADES = {.5f, 1f, .8f, .8f, .6f, .6f};
    // Authored JY yellow paint and the outrigger's light-colored part; no local assets are required.
    private static final float[][] COLORS = {{238f / 255, 185f / 255, 39f / 255, 1},
            {197f / 255, 229f / 255, 237f / 255, 1}};
    private static final float[][] LIGHTS = {{1, 1, 1, 1}, {.7f, .6f, .5f, 1}, {.7f, .8f, .9f, 1}};

    static void run() throws Exception {
        try (GlFixture gl = new GlFixture(); TextureUploadRing instanceRing = new TextureUploadRing();
             TextureUploadRing boneRing = new TextureUploadRing()) {
            int single = gl.program(vertexSource("/assets/kasuga_lib/shaders/core/ksglib_main.vsh"), mainFragment());
            int batch = gl.program(vertexSource("/assets/kasuga_lib/shaders/core/ksglib_global_batch.vsh"), mainFragment());
            int vanilla = gl.program(vertexSource("/assets/minecraft/shaders/core/rendertype_solid.vsh"),
                    fragment("rendertype_solid"));
            int vanillaItem = gl.program(vertexSource("/assets/minecraft/shaders/core/rendertype_entity_cutout.vsh"),
                    fragment("rendertype_entity_cutout"));
            int albedo = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 1, 1, COLORS[0]);
            int normalMap = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 1, 1,
                    new float[]{127f / 255, 127f / 255, 1, 1});
            int specular = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 1, 1,
                    new float[]{10f / 255, 10f / 255, 0, 0});
            int overlay = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 16, 16, repeated(LIGHTS[0]));
            int lightmap = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 16, 16, repeated(LIGHTS[0]));
            int target = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 4, 4, null);
            gl.framebuffer(target, 0);
            GL11.glViewport(0, 0, 4, 4);
            int vertices = GL15.glGenBuffers();
            FloatBuffer instanceData = MemoryUtil.memAllocFloat(36);
            FloatBuffer boneData = MemoryUtil.memAllocFloat(BonePalettePacker.FLOATS_PER_BONE);
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertices);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
                        new float[]{-1, -1, -.5f, 3, -1, -.5f, -1, 3, -.5f}, GL15.GL_STATIC_DRAW);
                BonePalettePacker.put(boneData, new Matrix4f(), new Matrix4f(), new Matrix3f());
                boneData.flip();
                bindBufferTexture(6, boneRing.upload(boneData));
                fillInstance(instanceData, new Matrix3f(), false);
                bindBufferTexture(5, instanceRing.upload(instanceData));
                bindTexture(0, albedo); bindTexture(1, overlay); bindTexture(2, lightmap);
                bindTexture(3, normalMap); bindTexture(4, specular);

                for (float[] color : COLORS) {
                    updateTexture(0, albedo, 1, color);
                    for (float[] light : LIGHTS) {
                        updateTexture(2, lightmap, 16, repeated(light));
                        for (int face = 0; face < NORMALS.length; face++) {
                            float[] expected = draw(vanilla, NORMALS[face], 0, SHADES[face], false,
                                    new Matrix4f(), new Matrix3f(), false);
                            for (int path = 0; path < 3; path++) {
                                float[] actual = draw(path == 0 ? single : batch, NORMALS[face], 1, 1,
                                        path == 2, new Matrix4f(), new Matrix3f(), false);
                                compare(actual, expected, "six faces and RGB light, draw path " + path);
                            }
                            float[] itemExpected = draw(vanillaItem, NORMALS[face], 0, 1, false,
                                    new Matrix4f(), new Matrix3f(), false);
                            compare(draw(single, NORMALS[face], 2, 1, false,
                                    new Matrix4f(), new Matrix3f(), false), itemExpected,
                                    "vanilla item diffuse and packed RGB light");
                        }
                    }
                }

                updateTexture(2, lightmap, 16, repeated(LIGHTS[0]));
                Vector3f east = NORMALS[5];
                Matrix3f rotatedNormal = new Matrix3f().rotationY((float) Math.PI / 2);
                Matrix4f camera = new Matrix4f().rotationY(.4f);
                float[] expectedNorth = draw(vanilla, NORMALS[2], 0, .8f, false,
                        new Matrix4f(), new Matrix3f(), false);
                compare(draw(single, east, 1, 1, false, camera, rotatedNormal, false), expectedNorth,
                        "block orientation changes shade; camera rotation does not");
                fillInstance(instanceData, rotatedNormal, false);
                bindBufferTexture(5, instanceRing.upload(instanceData));
                compare(draw(batch, east, 1, 1, true, camera, new Matrix3f(), false), expectedNorth,
                        "instanced world normal uses each object's normal matrix");

                // An animated BDEF bone must update the lighting normal together with the geometry.
                Matrix4f bonePose = new Matrix4f().rotationY((float) Math.PI / 2);
                boneData.clear();
                BonePalettePacker.put(boneData, bonePose, new Matrix4f(), new Matrix3f(bonePose));
                boneData.flip(); bindBufferTexture(6, boneRing.upload(boneData));
                Matrix4f undoBoneForCamera = new Matrix4f(bonePose).invert();
                compare(draw(single, east, 1, 1, false, undoBoneForCamera, new Matrix3f(), true), expectedNorth,
                        "single draw shades the animated bone normal");
                fillInstance(instanceData, new Matrix3f(), true);
                bindBufferTexture(5, instanceRing.upload(instanceData));
                compare(draw(batch, east, 1, 1, true, undoBoneForCamera, new Matrix3f(), true), expectedNorth,
                        "instanced draw shades the animated bone normal");

                // Switching policies must restore the old PBR result without touching material data.
                float[] pbrBefore = draw(single, NORMALS[1], 0, 1, false,
                        new Matrix4f(), new Matrix3f(), false);
                draw(single, NORMALS[1], 1, 1, false, new Matrix4f(), new Matrix3f(), false);
                compare(draw(single, NORMALS[1], 0, 1, false,
                        new Matrix4f(), new Matrix3f(), false), pbrBefore, "PBR policy restores after a block draw");

                float[] halfAlpha = COLORS[0].clone(); halfAlpha[3] = .25f;
                updateTexture(0, albedo, 1, halfAlpha);
                setup(single, NORMALS[1], 1, 1, false, new Matrix4f(), new Matrix3f(), false);
                integer(single, "ksg_AlphaMode", 2);
                GL11.glClearColor(0, 0, 0, 0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                GlFixture.draw();
                expect(read(1, 1, GL11.GL_RGBA, 4)[3], .25, 1e-5, "BLEND preserves material alpha");
                integer(single, "ksg_AlphaMode", 1);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT); GlFixture.draw();
                expect(read(1, 1, GL11.GL_RGBA, 4)[3], 0, 1e-5, "MASK still rejects sub-threshold alpha");
                noError("vanilla block lighting");
            } finally {
                GL15.glDeleteBuffers(vertices);
                MemoryUtil.memFree(instanceData); MemoryUtil.memFree(boneData);
            }
        }
    }

    private static String fragment(String name) throws Exception {
        return resource("/assets/minecraft/shaders/core/" + name + ".fsh")
                .replace("#moj_import <fog.glsl>", resource("/assets/minecraft/shaders/include/fog.glsl")
                        .replaceFirst("(?m)^#version[^\\n]*\\n", ""));
    }

    private static float[] draw(int program, Vector3f normal, int mode, float shade, boolean instanced,
                                Matrix4f camera, Matrix3f modelNormal, boolean skinning) {
        setup(program, normal, mode, shade, instanced, camera, modelNormal, skinning);
        GL11.glClearColor(0, 0, 0, 0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        if (instanced) GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 3, 1);
        else GlFixture.draw();
        return read(1, 1, GL11.GL_RGBA, 4);
    }

    private static void setup(int program, Vector3f normal, int mode, float shade, boolean instanced,
                              Matrix4f camera, Matrix3f modelNormal, boolean skinning) {
        GL20.glUseProgram(program);
        int position = GL20.glGetAttribLocation(program, "Position");
        GL20.glEnableVertexAttribArray(position);
        GL20.glVertexAttribPointer(position, 3, GL11.GL_FLOAT, false, 12, 0L);
        attribute(program, "Color", shade, shade, shade, 1);
        attribute(program, "Normal", normal.x, normal.y, normal.z, 0);
        attribute(program, "Tangent", normal.z == 0 ? 0 : 1, 0, normal.z == 0 ? 1 : 0, 1);
        attribute(program, "UV0", .5f, .5f, 0, 0);
        attribute(program, "TextureUV", .5f, .5f, 0, 0);
        attribute(program, "TextureBounds", 0, 0, 1, 1);
        attribute(program, "AlphaCutoff", .5f, 0, 0, 0);
        attribute(program, "BoneIndices", 0, 0, 0, 0);
        attribute(program, "BoneWeights", 1, 0, 0, 0);
        int type = GL20.glGetAttribLocation(program, "BoneBindingType");
        if (type >= 0) { GL20.glDisableVertexAttribArray(type); GL30.glVertexAttribI1i(type, 0); }
        int uv1 = GL20.glGetAttribLocation(program, "UV1");
        if (uv1 >= 0) { GL20.glDisableVertexAttribArray(uv1); GL30.glVertexAttribI2i(uv1, 0, 10); }
        int uv2 = GL20.glGetAttribLocation(program, "UV2");
        if (uv2 >= 0) { GL20.glDisableVertexAttribArray(uv2); GL30.glVertexAttribI2i(uv2, 240, 240); }
        integer(program, "Sampler0", 0); integer(program, "Sampler1", 1); integer(program, "Sampler2", 2);
        integer(program, "ksg_NormalMap", 3); integer(program, "ksg_SpecularMap", 4);
        integer(program, "ksg_InstanceData", 5); integer(program, "ksg_BoneTransforms", 6);
        integer(program, "ksg_LightingMode", mode); integer(program, "ksg_Instanced", instanced ? 1 : 0);
        integer(program, "ksg_GpuSkinningEnabled", skinning ? 1 : 0);
        integer(program, "ksg_AlphaMode", 0); integer(program, "ksg_OitMode", 0);
        integer(program, "ksg_PeelEnabled", 0); integer(program, "ksg_ParallaxSamples", 4);
        scalar(program, "ksg_ParallaxScale", 0); scalar(program, "ksg_EmissiveStrength", 0);
        scalar(program, "ksg_AmbientLightEnhancement", 1.5f); scalar(program, "ksg_StylizedShadingStrength", .65f);
        scalar(program, "FogStart", 100); scalar(program, "FogEnd", 1000);
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "ksg_BlockShade"), .5f, 1, .8f, .6f);
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, 1);
        GL20.glUniform2i(GL20.glGetUniformLocation(program, "ksg_PackedLight"), 240, 240);
        GL20.glUniform2i(GL20.glGetUniformLocation(program, "ksg_PackedOverlay"), 0, 10);
        Vector3f light0 = new Vector3f(.2f, 1, -.7f).normalize();
        Vector3f light1 = new Vector3f(-.2f, 1, .7f).normalize();
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "Light0_Direction"), light0.x, light0.y, light0.z);
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "Light1_Direction"), light1.x, light1.y, light1.z);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ModelViewMat"), false, camera.get(new float[16]));
        // Cancel camera rotation only in clip space: lighting must remain in world space.
        Matrix4f projection = skinning ? new Matrix4f() : new Matrix4f(camera).invert();
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ProjMat"), false, projection.get(new float[16]));
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ksg_ModelPoseMat"), false, new Matrix4f().get(new float[16]));
        GL20.glUniformMatrix3fv(GL20.glGetUniformLocation(program, "ksg_ModelNormalMat"), false, modelNormal.get(new float[9]));
    }

    private static void fillInstance(FloatBuffer data, Matrix3f normal, boolean skinning) {
        data.clear(); data.put(new Matrix4f().get(new float[16]));
        float[] columns = normal.get(new float[9]);
        for (int column = 0; column < 3; column++) {
            data.put(columns, column * 3, 3).put(0);
        }
        data.put(1).put(0).put(skinning ? 1 : 0).put(0);
        data.put(240).put(240).put(0).put(10); data.flip();
    }

    private static void attribute(int program, String name, float x, float y, float z, float w) {
        int location = GL20.glGetAttribLocation(program, name);
        if (location < 0) return;
        GL20.glDisableVertexAttribArray(location); GL20.glVertexAttrib4f(location, x, y, z, w);
    }

    private static void bindBufferTexture(int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit); GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
    }

    private static void updateTexture(int unit, int texture, int size, float[] rgba) {
        bindTexture(unit, texture);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, size, size, GL11.GL_RGBA, GL11.GL_FLOAT, rgba);
    }

    private static float[] repeated(float[] color) {
        float[] pixels = new float[16 * 16 * 4];
        for (int i = 0; i < 16 * 16; i++) System.arraycopy(color, 0, pixels, i * 4, 4);
        return pixels;
    }

    private static void compare(float[] actual, float[] expected, String label) {
        for (int i = 0; i < 4; i++) expect(actual[i], expected[i], 1e-5,
                label + " channel=" + i + " actual=" + Arrays.toString(actual) + " expected=" + Arrays.toString(expected));
    }
}
