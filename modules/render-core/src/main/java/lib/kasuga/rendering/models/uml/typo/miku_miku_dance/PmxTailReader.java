package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail.*;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Lossless reader for the PMX sections after the bone table. */
public final class PmxTailReader implements StreamLoader {
    private static final int MAX_ELEMENTS = 16_777_216;

    private final PMXLoader<?, ?, ?, ?> loader;
    private final PmxHeader fixedHeader;

    public PmxTailReader(PMXLoader<?, ?, ?, ?> loader) {
        this.loader = loader;
        this.fixedHeader = null;
    }

    PmxTailReader(PmxHeader header) {
        this.loader = null;
        this.fixedHeader = header;
    }

    @Override
    public PmxTail load(ByteBuffer source, SerialContext context) {
        PmxHeader header = fixedHeader != null ? fixedHeader : loader.getHeader();
        ByteBuffer buffer = source.order(ByteOrder.LITTLE_ENDIAN);
        try {
            List<PmxMorph> morphs = readMorphs(buffer, header);
            List<PmxDisplayFrame> frames = readDisplayFrames(buffer, header);
            List<PmxRigidBody> bodies = readRigidBodies(buffer, header);
            List<PmxJoint> joints = readJoints(buffer, header);
            List<PmxSoftBody> softBodies = header.version >= 2.1f
                    ? readSoftBodies(buffer, header)
                    : List.of();
            if (buffer.hasRemaining()) {
                throw error(buffer, "Unexpected " + buffer.remaining() + " trailing byte(s)");
            }
            return new PmxTail(morphs, frames, bodies, joints, softBodies);
        } catch (BufferUnderflowException e) {
            throw new PmxFormatException("Truncated PMX tail at byte " + buffer.position(), e);
        }
    }

    private List<PmxMorph> readMorphs(ByteBuffer b, PmxHeader h) {
        int count = count(b, "morph");
        List<PmxMorph> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String local = text(b, h.info.encoding, "morph local name");
            String universal = text(b, h.info.encoding, "morph universal name");
            int panel = u8(b);
            int kindId = u8(b);
            MorphKind kind;
            try {
                kind = MorphKind.fromId(kindId);
            } catch (IllegalArgumentException e) {
                throw error(b, e.getMessage());
            }
            if (h.version < 2.1f && kindId > 8) {
                throw error(b, "PMX " + h.version + " cannot contain morph type " + kindId);
            }
            int offsetCount = count(b, "morph offset");
            List<PmxMorphOffset> offsets = new ArrayList<>(offsetCount);
            for (int j = 0; j < offsetCount; j++) {
                offsets.add(readMorphOffset(b, h, kind));
            }
            result.add(new PmxMorph(local, universal, panel, kind, offsets));
        }
        return result;
    }

    private PmxMorphOffset readMorphOffset(ByteBuffer b, PmxHeader h, MorphKind kind) {
        return switch (kind) {
            case GROUP, FLIP -> new GroupOffset(index(b, h.info.morphIndexSize, false), b.getFloat());
            case VERTEX -> new VertexOffset(index(b, h.info.vertexIndexSize, true), vec3(b));
            case BONE -> new BoneOffset(
                    index(b, h.info.boneIndexSize, false), vec3(b), quaternion(b));
            case UV, ADDITIONAL_UV_1, ADDITIONAL_UV_2, ADDITIONAL_UV_3, ADDITIONAL_UV_4 ->
                    new UvOffset(index(b, h.info.vertexIndexSize, true), kind.ordinal() - MorphKind.UV.ordinal(), vec4(b));
            case MATERIAL -> new MaterialOffset(
                    index(b, h.info.materialIndexSize, false), u8(b), vec4(b), vec3(b), b.getFloat(),
                    vec3(b), vec4(b), b.getFloat(), vec4(b), vec4(b), vec4(b));
            case IMPULSE -> new ImpulseOffset(
                    index(b, h.info.rigidBodyIndexSize, false), u8(b) != 0, vec3(b), vec3(b));
        };
    }

    private List<PmxDisplayFrame> readDisplayFrames(ByteBuffer b, PmxHeader h) {
        int count = count(b, "display frame");
        List<PmxDisplayFrame> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String local = text(b, h.info.encoding, "display frame local name");
            String universal = text(b, h.info.encoding, "display frame universal name");
            boolean special = u8(b) != 0;
            int elementCount = count(b, "display frame element");
            List<DisplayElement> elements = new ArrayList<>(elementCount);
            for (int j = 0; j < elementCount; j++) {
                int type = u8(b);
                if (type > 1) throw error(b, "Unsupported display element type: " + type);
                elements.add(new DisplayElement(
                        type == 1,
                        index(b, type == 0 ? h.info.boneIndexSize : h.info.morphIndexSize, false)
                ));
            }
            result.add(new PmxDisplayFrame(local, universal, special, elements));
        }
        return result;
    }

    private List<PmxRigidBody> readRigidBodies(ByteBuffer b, PmxHeader h) {
        int count = count(b, "rigid body");
        List<PmxRigidBody> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String local = text(b, h.info.encoding, "rigid body local name");
            String universal = text(b, h.info.encoding, "rigid body universal name");
            int bone = index(b, h.info.boneIndexSize, false);
            int group = u8(b);
            int mask = Short.toUnsignedInt(b.getShort());
            int shape = u8(b);
            if (shape > 2) throw error(b, "Unsupported rigid body shape: " + shape);
            Vector3f size = vec3(b);
            Vector3f position = vec3(b);
            Vector3f rotation = vec3(b);
            float mass = b.getFloat();
            float linearDamping = b.getFloat();
            float angularDamping = b.getFloat();
            float restitution = b.getFloat();
            float friction = b.getFloat();
            int mode = u8(b);
            if (mode > 2) throw error(b, "Unsupported rigid body mode: " + mode);
            result.add(new PmxRigidBody(local, universal, bone, group, mask, shape, size,
                    position, rotation, mass, linearDamping, angularDamping, restitution, friction, mode));
        }
        return result;
    }

    private List<PmxJoint> readJoints(ByteBuffer b, PmxHeader h) {
        int count = count(b, "joint");
        List<PmxJoint> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String local = text(b, h.info.encoding, "joint local name");
            String universal = text(b, h.info.encoding, "joint universal name");
            int type = u8(b);
            if (type > 5) throw error(b, "Unsupported PMX joint type: " + type);
            result.add(new PmxJoint(local, universal, type,
                    index(b, h.info.rigidBodyIndexSize, false),
                    index(b, h.info.rigidBodyIndexSize, false),
                    vec3(b), vec3(b), vec3(b), vec3(b), vec3(b), vec3(b), vec3(b), vec3(b)));
        }
        return result;
    }

    private List<PmxSoftBody> readSoftBodies(ByteBuffer b, PmxHeader h) {
        int count = count(b, "soft body");
        List<PmxSoftBody> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String local = text(b, h.info.encoding, "soft body local name");
            String universal = text(b, h.info.encoding, "soft body universal name");
            int shape = u8(b);
            if (shape > 1) throw error(b, "Unsupported soft body shape: " + shape);
            int materialIndex = index(b, h.info.materialIndexSize, false);
            int group = u8(b);
            int mask = Short.toUnsignedInt(b.getShort());
            int flags = u8(b);
            int bendingDistance = b.getInt();
            int clusterCount = b.getInt();
            float mass = b.getFloat();
            float margin = b.getFloat();
            int aeroModel = b.getInt();
            if (aeroModel < 0 || aeroModel > 4) throw error(b, "Unsupported soft body aero model: " + aeroModel);

            SoftBodyConfig config = new SoftBodyConfig(
                    b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(),
                    b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat());
            SoftBodyCluster cluster = new SoftBodyCluster(
                    b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat());
            SoftBodyIteration iteration = new SoftBodyIteration(
                    b.getInt(), b.getInt(), b.getInt(), b.getInt());
            SoftBodyMaterial material = new SoftBodyMaterial(b.getFloat(), b.getFloat(), b.getFloat());

            int anchorCount = count(b, "soft body anchor");
            List<SoftBodyAnchor> anchors = new ArrayList<>(anchorCount);
            for (int j = 0; j < anchorCount; j++) {
                anchors.add(new SoftBodyAnchor(
                        index(b, h.info.rigidBodyIndexSize, false),
                        index(b, h.info.vertexIndexSize, true),
                        u8(b) != 0));
            }
            int pinCount = count(b, "soft body pin");
            List<Integer> pins = new ArrayList<>(pinCount);
            for (int j = 0; j < pinCount; j++) {
                pins.add(index(b, h.info.vertexIndexSize, true));
            }
            result.add(new PmxSoftBody(local, universal, shape, materialIndex, group, mask, flags,
                    bendingDistance, clusterCount, mass, margin, aeroModel, config, cluster, iteration,
                    material, anchors, pins));
        }
        return result;
    }

    private static int count(ByteBuffer b, String label) {
        int value = b.getInt();
        if (value < 0 || value > MAX_ELEMENTS) {
            throw error(b, "Invalid " + label + " count: " + value);
        }
        return value;
    }

    private static String text(ByteBuffer b, Charset encoding, String label) {
        int size = b.getInt();
        if (size < 0 || size > b.remaining()) {
            throw error(b, "Invalid " + label + " byte length: " + size);
        }
        byte[] bytes = new byte[size];
        b.get(bytes);
        return new String(bytes, encoding);
    }

    private static int index(ByteBuffer b, int size, boolean unsigned) {
        return switch (size) {
            case 1 -> unsigned ? Byte.toUnsignedInt(b.get()) : b.get();
            case 2 -> unsigned ? Short.toUnsignedInt(b.getShort()) : b.getShort();
            case 4 -> b.getInt();
            default -> throw error(b, "Invalid PMX index size: " + size);
        };
    }

    private static int u8(ByteBuffer b) {
        return Byte.toUnsignedInt(b.get());
    }

    private static Vector3f vec3(ByteBuffer b) {
        return new Vector3f(b.getFloat(), b.getFloat(), b.getFloat());
    }

    private static Vector4f vec4(ByteBuffer b) {
        return new Vector4f(b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat());
    }

    private static Quaternionf quaternion(ByteBuffer b) {
        return new Quaternionf(b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat());
    }

    private static PmxFormatException error(ByteBuffer b, String message) {
        return new PmxFormatException(message + " at byte " + b.position());
    }
}
