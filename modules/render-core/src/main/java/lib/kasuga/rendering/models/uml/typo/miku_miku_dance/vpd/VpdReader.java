package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads CP932 VPD files, including MikuMikuMoving morph extensions. */
public final class VpdReader {
    private static final Charset WINDOWS_31J = Charset.forName("windows-31j");

    public VpdPose read(ByteBuffer source) {
        ByteBuffer input = source.duplicate();
        input.position(0);
        String text = WINDOWS_31J.decode(input).toString().replace("\r", "");
        String[] lines = text.split("\n", -1);
        try {
            int cursor = nextContent(lines, 0);
            if (cursor >= lines.length || !stripComment(lines[cursor]).startsWith("Vocaloid Pose Data file")) {
                throw new VpdFormatException("Invalid VPD signature");
            }
            cursor = nextContent(lines, cursor + 1);
            String modelName = beforeSemicolon(lines[cursor++]);
            cursor = nextContent(lines, cursor);
            int boneCount = Integer.parseInt(beforeSemicolon(lines[cursor++]));
            if (boneCount < 0) throw new VpdFormatException("Negative VPD bone count");

            Map<String, Transform> bones = new LinkedHashMap<>();
            Map<String, Float> morphs = new LinkedHashMap<>();
            while ((cursor = nextContent(lines, cursor)) < lines.length) {
                String header = stripComment(lines[cursor++]).trim();
                if (header.startsWith("Bone")) {
                    String name = blockName(header, "Bone");
                    cursor = nextContent(lines, cursor);
                    float[] location = numbers(beforeSemicolon(lines[cursor++]), 3);
                    cursor = nextContent(lines, cursor);
                    float[] rotation = numbers(beforeSemicolon(lines[cursor++]), 4);
                    cursor = requireClose(lines, cursor, "bone", name);
                    Quaternionf quaternion = new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]);
                    if (quaternion.lengthSquared() == 0f) quaternion.identity();
                    else quaternion.normalize();
                    bones.put(name, new Transform().translate(new Vector3f(location[0], location[1], location[2]))
                            .mul(quaternion));
                } else if (header.startsWith("Morph")) {
                    String name = blockName(header, "Morph");
                    cursor = nextContent(lines, cursor);
                    float weight = Float.parseFloat(beforeSemicolon(lines[cursor++]));
                    cursor = requireClose(lines, cursor, "morph", name);
                    morphs.put(name, weight);
                } else {
                    throw new VpdFormatException("Unexpected VPD block: " + header);
                }
            }
            if (bones.size() != boneCount) {
                throw new VpdFormatException("VPD declared " + boneCount + " bones but contained " + bones.size());
            }
            return new VpdPose(modelName, bones, morphs);
        } catch (VpdFormatException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new VpdFormatException("Malformed VPD", e);
        }
    }

    private static int nextContent(String[] lines, int cursor) {
        while (cursor < lines.length && stripComment(lines[cursor]).trim().isEmpty()) cursor++;
        return cursor;
    }

    private static String stripComment(String line) {
        int comment = line.indexOf("//");
        return comment < 0 ? line : line.substring(0, comment);
    }

    private static String beforeSemicolon(String line) {
        String content = stripComment(line);
        int semicolon = content.indexOf(';');
        if (semicolon < 0) throw new VpdFormatException("Missing VPD semicolon: " + content.trim());
        return content.substring(0, semicolon).trim();
    }

    private static String blockName(String header, String kind) {
        int brace = header.indexOf('{');
        if (brace < 0 || header.substring(brace + 1).trim().isEmpty()) {
            throw new VpdFormatException("Invalid " + kind + " block header: " + header);
        }
        return header.substring(brace + 1).trim();
    }

    private static float[] numbers(String text, int count) {
        String[] parts = text.split(",");
        if (parts.length != count) throw new VpdFormatException("Expected " + count + " values: " + text);
        float[] result = new float[count];
        for (int i = 0; i < count; i++) result[i] = Float.parseFloat(parts[i].trim());
        return result;
    }

    private static int requireClose(String[] lines, int cursor, String kind, String name) {
        cursor = nextContent(lines, cursor);
        if (cursor >= lines.length || !stripComment(lines[cursor]).trim().startsWith("}")) {
            throw new VpdFormatException("Unclosed VPD " + kind + " block: " + name);
        }
        return cursor + 1;
    }
}
