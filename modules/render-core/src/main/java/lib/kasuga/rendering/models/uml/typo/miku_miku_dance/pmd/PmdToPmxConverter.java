package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.pmd;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Converts PMD 1.0 into equivalent PMX 2.0 bytes for the common runtime pipeline. */
public final class PmdToPmxConverter {
    private static final Charset CP932 = Charset.forName("windows-31j");
    private static final int MAX_COUNT = 16_777_216;

    public ByteBuffer convert(ByteBuffer source) {
        try {
            Pmd pmd = read(source.duplicate().order(ByteOrder.LITTLE_ENDIAN));
            return write(pmd);
        } catch (PmdFormatException e) {
            throw e;
        } catch (BufferUnderflowException e) {
            throw new PmdFormatException("Truncated PMD file", e);
        } catch (RuntimeException e) {
            throw new PmdFormatException("Malformed PMD file", e);
        }
    }

    private static Pmd read(ByteBuffer b) {
        b.position(0);
        if (!"Pmd".equals(text(b, 3))) throw new PmdFormatException("Invalid PMD signature");
        float version = b.getFloat();
        if (Math.abs(version - 1f) > 1e-4f) throw new PmdFormatException("Unsupported PMD version: " + version);
        Pmd p = new Pmd(text(b, 20), text(b, 256));
        int vertexCount = count32(b, "vertex");
        for (int i = 0; i < vertexCount; i++) p.vertices.add(new Vertex(vec3(b), vec3(b), vec2(b),
                u16(b), u16(b), u8(b) / 100f, u8(b)));
        int indexCount = count32(b, "surface index");
        for (int i = 0; i < indexCount; i++) p.indices.add(u16(b));
        int materialCount = count32(b, "material");
        for (int i = 0; i < materialCount; i++) p.materials.add(new Material(vec4(b), b.getFloat(), vec3(b), vec3(b),
                u8(b), u8(b), u32i(b), text(b, 20)));
        int boneCount = u16(b);
        for (int i = 0; i < boneCount; i++) p.bones.add(new Bone(text(b, 20), u16(b), u16(b), u8(b), u16(b), vec3(b)));
        int ikCount = u16(b);
        for (int i = 0; i < ikCount; i++) {
            int controller = u16(b), target = u16(b), links = u8(b), iterations = u16(b);
            float angle = b.getFloat();
            int[] chain = new int[links];
            for (int j = 0; j < links; j++) chain[j] = u16(b);
            p.iks.add(new Ik(controller, target, iterations, angle, chain));
        }
        int morphCount = u16(b);
        for (int i = 0; i < morphCount; i++) {
            String name = text(b, 20);
            int offsets = count32(b, "morph offset");
            int panel = u8(b);
            List<MorphVertex> values = new ArrayList<>(offsets);
            for (int j = 0; j < offsets; j++) values.add(new MorphVertex(u32i(b), vec3(b)));
            p.morphs.add(new Morph(name, panel, values));
        }
        int shownMorphCount = u8(b);
        for (int i = 0; i < shownMorphCount; i++) p.shownMorphs.add(u16(b));
        int frameCount = u8(b);
        for (int i = 0; i < frameCount; i++) p.frameNames.add(text(b, 50));
        int assignmentCount = count32(b, "bone display assignment");
        for (int i = 0; i < assignmentCount; i++) p.assignments.add(new Assignment(u16(b), u8(b)));
        if (!b.hasRemaining()) return p;

        boolean english = u8(b) != 0;
        if (english) {
            p.englishName = text(b, 20);
            p.englishComment = text(b, 256);
            for (int i = 0; i < boneCount; i++) p.boneEnglish.add(text(b, 20));
            for (int i = 1; i < morphCount; i++) p.morphEnglish.add(text(b, 20));
            for (int i = 0; i < frameCount; i++) p.frameEnglish.add(text(b, 50));
        }
        if (!b.hasRemaining()) return p;
        for (int i = 0; i < 10; i++) p.toonTextures.add(text(b, 100));
        if (!b.hasRemaining()) return p;

        int rigidCount = count32(b, "rigid body");
        for (int i = 0; i < rigidCount; i++) p.rigidBodies.add(new Rigid(text(b, 20), u16(b), u8(b), u16(b), u8(b),
                vec3(b), vec3(b), vec3(b), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), u8(b)));
        if (!b.hasRemaining()) return p;
        int jointCount = count32(b, "joint");
        for (int i = 0; i < jointCount; i++) p.joints.add(new Joint(text(b, 20), u32i(b), u32i(b), vec3(b), vec3(b),
                vec3(b), vec3(b), vec3(b), vec3(b), vec3(b), vec3(b)));
        if (b.hasRemaining()) throw new PmdFormatException("Unexpected trailing PMD data: " + b.remaining() + " bytes");
        return p;
    }

    private static ByteBuffer write(Pmd p) {
        Out w = new Out();
        w.raw(new byte[]{'P', 'M', 'X', ' '}); w.f32(2f); w.u8(8);
        w.raw(new byte[]{1, 0, 4, 4, 4, 4, 4, 4});
        w.text(p.name); w.text(p.englishName); w.text(p.comment); w.text(p.englishComment);

        w.i32(p.vertices.size());
        for (Vertex v : p.vertices) {
            w.vec3(v.position); w.vec3(v.normal); w.vec2(v.uv);
            if (v.bone0 == v.bone1 || v.weight >= 0.99999f) {
                w.u8(0); w.i32(validBone(v.bone0, p.bones.size()));
            } else {
                w.u8(1); w.i32(validBone(v.bone0, p.bones.size())); w.i32(validBone(v.bone1, p.bones.size())); w.f32(v.weight);
            }
            w.f32(v.edge == 0 ? 1f : 0f);
        }
        w.i32(p.indices.size()); for (int index : p.indices) w.i32(index);

        TextureTable textures = textures(p);
        w.i32(textures.names.size()); textures.names.forEach(w::text);
        w.i32(p.materials.size());
        for (int i = 0; i < p.materials.size(); i++) {
            Material m = p.materials.get(i);
            w.text("material_" + i); w.text("material_" + i);
            w.vec4(m.diffuse); w.vec3(m.specular); w.f32(m.shininess); w.vec3(m.ambient);
            int flags = 0x01 | 0x02 | 0x04 | (m.edge == 0 ? 0x10 : 0);
            w.u8(flags); w.vec4(new Vector4f(0, 0, 0, 1)); w.f32(1);
            TextureRefs ref = textures.materials.get(i);
            w.i32(ref.base); w.i32(ref.sphere); w.u8(ref.sphereMode);
            w.u8(0); w.i32(ref.toon); w.text(""); w.i32(m.surfaceCount);
        }

        Map<Integer, Ik> ikByController = new HashMap<>();
        p.iks.forEach(ik -> ikByController.put(ik.controller, ik));
        w.i32(p.bones.size());
        for (int i = 0; i < p.bones.size(); i++) {
            Bone bone = p.bones.get(i); Ik ik = ikByController.get(i);
            w.text(bone.name); w.text(get(p.boneEnglish, i)); w.vec3(bone.position);
            w.i32(validParent(bone.parent, p.bones.size())); w.i32(0);
            boolean grant = bone.type == 5 && validBone(bone.ikParent, p.bones.size()) >= 0;
            int flags = 0x0002 | 0x0008 | 0x0010;
            if (bone.type == 1 || bone.type == 9) flags |= 0x0004;
            if (ik != null) flags |= 0x0020;
            if (grant) flags |= 0x0100;
            if (bone.type == 6) flags &= ~0x0008;
            if (bone.tail != 0xffff && bone.tail < p.bones.size()) flags |= 0x0001;
            w.u16(flags);
            if ((flags & 1) != 0) w.i32(bone.tail); else w.vec3(new Vector3f());
            if (grant) { w.i32(bone.ikParent); w.f32(1); }
            if (ik != null) {
                w.i32(validBone(ik.target, p.bones.size())); w.i32(ik.iterations); w.f32(ik.angle);
                w.i32(ik.chain.length);
                for (int link : ik.chain) { w.i32(validBone(link, p.bones.size())); w.u8(0); }
            }
        }

        List<Morph> morphs = p.morphs.size() <= 1 ? List.of() : p.morphs.subList(1, p.morphs.size());
        Map<Integer, Integer> baseIndices = new HashMap<>();
        if (!p.morphs.isEmpty()) {
            List<MorphVertex> base = p.morphs.getFirst().vertices;
            for (int i = 0; i < base.size(); i++) baseIndices.put(i, base.get(i).index);
        }
        w.i32(morphs.size());
        for (int i = 0; i < morphs.size(); i++) {
            Morph morph = morphs.get(i); w.text(morph.name); w.text(get(p.morphEnglish, i));
            w.u8(Math.max(1, Math.min(4, morph.panel))); w.u8(1); w.i32(morph.vertices.size());
            for (MorphVertex value : morph.vertices) {
                w.i32(baseIndices.getOrDefault(value.index, value.index)); w.vec3(value.offset);
            }
        }

        List<List<Integer>> frameBones = new ArrayList<>();
        for (int i = 0; i < p.frameNames.size(); i++) frameBones.add(new ArrayList<>());
        for (Assignment assignment : p.assignments) {
            int frame = assignment.frame - 1;
            if (frame >= 0 && frame < frameBones.size() && assignment.bone < p.bones.size()) frameBones.get(frame).add(assignment.bone);
        }
        int morphFrameSize = (int) p.shownMorphs.stream().filter(index -> index > 0 && index < p.morphs.size()).count();
        w.i32(p.frameNames.size() + (morphFrameSize > 0 ? 1 : 0));
        if (morphFrameSize > 0) {
            w.text("表情"); w.text("Expression"); w.u8(1); w.i32(morphFrameSize);
            for (int index : p.shownMorphs) if (index > 0 && index < p.morphs.size()) { w.u8(1); w.i32(index - 1); }
        }
        for (int i = 0; i < p.frameNames.size(); i++) {
            w.text(p.frameNames.get(i)); w.text(get(p.frameEnglish, i)); w.u8(0); w.i32(frameBones.get(i).size());
            for (int bone : frameBones.get(i)) { w.u8(0); w.i32(bone); }
        }

        w.i32(p.rigidBodies.size());
        for (Rigid rigid : p.rigidBodies) {
            w.text(rigid.name); w.text(""); int bone = validBone(rigid.bone, p.bones.size()); w.i32(bone);
            w.u8(rigid.group); w.u16(rigid.mask); w.u8(rigid.shape); w.vec3(rigid.size);
            Vector3f position = new Vector3f(rigid.position);
            if (bone >= 0) position.add(p.bones.get(bone).position);
            w.vec3(position); w.vec3(rigid.rotation); w.f32(rigid.mass); w.f32(rigid.linearDamping);
            w.f32(rigid.angularDamping); w.f32(rigid.restitution); w.f32(rigid.friction); w.u8(rigid.mode);
        }
        w.i32(p.joints.size());
        for (Joint joint : p.joints) {
            w.text(joint.name); w.text(""); w.u8(0); w.i32(joint.a); w.i32(joint.b); w.vec3(joint.position);
            w.vec3(joint.rotation); w.vec3(joint.positionMin); w.vec3(joint.positionMax); w.vec3(joint.rotationMin);
            w.vec3(joint.rotationMax); w.vec3(joint.springPosition); w.vec3(joint.springRotation);
        }
        return w.buffer();
    }

    private static TextureTable textures(Pmd p) {
        List<String> names = new ArrayList<>(); List<TextureRefs> refs = new ArrayList<>();
        for (Material material : p.materials) {
            int base = -1, sphere = -1, sphereMode = 0;
            for (String part : material.texture.split("\\*")) {
                String name = part.trim(); if (name.isEmpty()) continue;
                String lower = name.toLowerCase(Locale.ROOT); int index = addTexture(names, name);
                if (lower.endsWith(".sph")) { sphere = index; sphereMode = 1; }
                else if (lower.endsWith(".spa")) { sphere = index; sphereMode = 2; }
                else base = index;
            }
            int toon = material.toon < p.toonTextures.size() && !p.toonTextures.get(material.toon).isEmpty()
                    ? addTexture(names, p.toonTextures.get(material.toon)) : -1;
            refs.add(new TextureRefs(base, sphere, sphereMode, toon));
        }
        return new TextureTable(names, refs);
    }

    private static int addTexture(List<String> names, String name) {
        int index = names.indexOf(name); if (index >= 0) return index; names.add(name); return names.size() - 1;
    }
    private static int validBone(int value, int count) { return value == 0xffff || value < 0 || value >= count ? -1 : value; }
    private static int validParent(int value, int count) { return validBone(value, count); }
    private static String get(List<String> values, int i) { return i >= 0 && i < values.size() ? values.get(i) : ""; }
    private static int count32(ByteBuffer b, String name) { long n = Integer.toUnsignedLong(b.getInt()); if (n > MAX_COUNT) throw new PmdFormatException("Invalid PMD " + name + " count: " + n); return (int) n; }
    private static int u32i(ByteBuffer b) { long n = Integer.toUnsignedLong(b.getInt()); if (n > Integer.MAX_VALUE) throw new PmdFormatException("PMD index is too large: " + n); return (int) n; }
    private static int u16(ByteBuffer b) { return Short.toUnsignedInt(b.getShort()); }
    private static int u8(ByteBuffer b) { return Byte.toUnsignedInt(b.get()); }
    private static Vector2f vec2(ByteBuffer b) { return new Vector2f(b.getFloat(), b.getFloat()); }
    private static Vector3f vec3(ByteBuffer b) { return new Vector3f(b.getFloat(), b.getFloat(), b.getFloat()); }
    private static Vector4f vec4(ByteBuffer b) { return new Vector4f(b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat()); }
    private static String text(ByteBuffer b, int size) { byte[] v = new byte[size]; b.get(v); int end = 0; while (end < size && v[end] != 0) end++; return new String(v, 0, end, CP932); }

    private static final class Out {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void raw(byte[] v) { out.writeBytes(v); } void u8(int v) { out.write(v); }
        void u16(int v) { u8(v); u8(v >>> 8); } void i32(int v) { u8(v); u8(v >>> 8); u8(v >>> 16); u8(v >>> 24); }
        void f32(float v) { i32(Float.floatToRawIntBits(v)); }
        void text(String v) { byte[] data = v.getBytes(StandardCharsets.UTF_8); i32(data.length); raw(data); }
        void vec2(Vector2f v) { f32(v.x); f32(v.y); } void vec3(Vector3f v) { f32(v.x); f32(v.y); f32(v.z); }
        void vec4(Vector4f v) { f32(v.x); f32(v.y); f32(v.z); f32(v.w); }
        ByteBuffer buffer() { return ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN); }
    }

    private static final class Pmd {
        final String name, comment; String englishName = "", englishComment = "";
        final List<Vertex> vertices = new ArrayList<>(); final List<Integer> indices = new ArrayList<>();
        final List<Material> materials = new ArrayList<>(); final List<Bone> bones = new ArrayList<>(); final List<Ik> iks = new ArrayList<>();
        final List<Morph> morphs = new ArrayList<>(); final List<Integer> shownMorphs = new ArrayList<>();
        final List<String> frameNames = new ArrayList<>(), frameEnglish = new ArrayList<>(), boneEnglish = new ArrayList<>(), morphEnglish = new ArrayList<>(), toonTextures = new ArrayList<>();
        final List<Assignment> assignments = new ArrayList<>(); final List<Rigid> rigidBodies = new ArrayList<>(); final List<Joint> joints = new ArrayList<>();
        Pmd(String name, String comment) { this.name = name; this.comment = comment; }
    }
    private record Vertex(Vector3f position, Vector3f normal, Vector2f uv, int bone0, int bone1, float weight, int edge) {}
    private record Material(Vector4f diffuse, float shininess, Vector3f specular, Vector3f ambient, int toon, int edge, int surfaceCount, String texture) {}
    private record Bone(String name, int parent, int tail, int type, int ikParent, Vector3f position) {}
    private record Ik(int controller, int target, int iterations, float angle, int[] chain) {}
    private record MorphVertex(int index, Vector3f offset) {} private record Morph(String name, int panel, List<MorphVertex> vertices) {}
    private record Assignment(int bone, int frame) {}
    private record Rigid(String name, int bone, int group, int mask, int shape, Vector3f size, Vector3f position, Vector3f rotation, float mass, float linearDamping, float angularDamping, float restitution, float friction, int mode) {}
    private record Joint(String name, int a, int b, Vector3f position, Vector3f rotation, Vector3f positionMin, Vector3f positionMax, Vector3f rotationMin, Vector3f rotationMax, Vector3f springPosition, Vector3f springRotation) {}
    private record TextureRefs(int base, int sphere, int sphereMode, int toon) {} private record TextureTable(List<String> names, List<TextureRefs> materials) {}
}
