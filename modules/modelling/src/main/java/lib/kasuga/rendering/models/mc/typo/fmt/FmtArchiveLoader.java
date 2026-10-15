package lib.kasuga.rendering.models.mc.typo.fmt;

import com.google.gson.*;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipResource;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.mc.util.RotHelper;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Loader for FMT archive files: MTB (Model.txt) and FMTB (model.jtmt). */
public final class FmtArchiveLoader implements ModelLoader<ZipHelper, ResourceLocation, Integer> {
    private final String name;
    private final MaterialSetBuilder<Integer> materials;
    private final HashMap<SourceType, HashMap<String, SourceManager<?>>> sidedSources = new HashMap<>();
    private ZipHelper archive;
    private ResourceLocation identifier;

    public FmtArchiveLoader(String name) { this.name = name; this.materials = new MaterialSetBuilder<>(this); }

    @Override public Map<ResourceLocation, Model> load(ResourceLocation id, ZipHelper input) {
        archive = input; identifier = id;
        List<BoxDef> boxes = id.getPath().endsWith(".mtb") ? parseMtb(input) : parseJtmt(input);
        Material material = buildMaterial();
        List<Vertex> vertices = new ArrayList<>(); List<Mesh> meshes = new ArrayList<>();
        Bone root = new Bone("root", new Transform(), null);
        for (BoxDef box : boxes) appendBox(box, material, root, vertices, meshes);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new lib.kasuga.rendering.models.uml.structure.skeleton.Anchor[0], null, new Transform());
        Model model = new Model(vertices.toArray(Vertex[]::new), meshes.toArray(Mesh[]::new), new Bone[]{root}, skeleton,
                materials.endMaterialSet(), MeshMode.QUADS, null, null);
        return Map.of(id, model);
    }

    private Material buildMaterial() {
        ZipResource texture = firstTexture();
        int width = 16, height = 16;
        java.awt.image.BufferedImage image = null;
        Object source = MissingTextureAtlasSprite.getLocation();
        ResourceLocation location = MissingTextureAtlasSprite.getLocation();
        if (texture != null) {
            try {
                image = ImageIO.read(new ByteArrayInputStream(bytes(texture.buffer())));
                if (image != null) {
                    width = image.getWidth(); height = image.getHeight();
                    location = ResourceLocation.fromNamespaceAndPath(identifier.getNamespace(), "fmt/" + Integer.toHexString(identifier.hashCode()));
                    source = Pair.of(location, image); Constants.TEXTURE_BASIC.load(source);
                }
            } catch (Exception ignored) { }
        }
        final ResourceLocation textureLocation = location;
        MCTexture textureData = new MCTexture("main", () -> new net.minecraft.client.resources.model.Material(RenderState.KSG_LAYER_0, textureLocation), width, height,
                new MCTextureData(source, Constants.TEXTURE_BASIC));
        materials.beginMaterial().setMaterialData(FmtMaterialData.from(image))
                .registerTexture(0, textureData).useTexture(0)
                .addSpriteBuildingFunc((builder, sprites, material) -> sprites.textureId(0).endSprite())
                .endMaterial(0);
        return materials.getNamedMaterial(0);
    }

    private ZipResource firstTexture() {
        for (ZipResource resource : archive.searchNameForResource(n -> n.equals("model.png") || n.equals("texture.png") || n.startsWith("texture-"))) return resource;
        return null;
    }

    private static byte[] bytes(java.nio.ByteBuffer buffer) { var copy = buffer.asReadOnlyBuffer(); byte[] data = new byte[copy.remaining()]; copy.get(data); return data; }

    private List<BoxDef> parseMtb(ZipHelper input) {
        ZipResource resource = input.getResource("Model.txt"); if (resource == null) return List.of();
        String text = new String(bytes(resource.buffer()), StandardCharsets.UTF_8);
        // Processors use SerialContext.peek(); populate through a tiny context bridge is not possible,
        // so parse the stable MTB columns directly here.
        List<BoxDef> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] p = line.trim().split("\\|", -1);
            if (p.length < 20 || !p[0].equals("Element")) continue;
            // MTB exports use both "Box" and "Shapebox" (case varies by
            // Traincraft/Modeler version).  Treat the element type
            // case-insensitively so valid geometry is not silently skipped.
            if (!p[5].equalsIgnoreCase("Box") && !p[5].equalsIgnoreCase("ShapeBox")) continue;
            try {
                Vector3f position = new Vector3f(f(p[6]), f(p[7]), f(p[8]));
                Vector3f size = new Vector3f(f(p[9]), f(p[10]), f(p[11]));
                Vector3f offset = new Vector3f(f(p[15]), f(p[16]), f(p[17]));
                Vector3f rotation = new Vector3f(f(p[12]), f(p[13]), -f(p[14]));
                Vector2i uv = new Vector2i(i(p[18]), i(p[19]));
                Vector3f[] corners = null;
                if (p[5].equalsIgnoreCase("ShapeBox") && p.length >= 44) {
                    corners = new Vector3f[8];
                    for (int n = 0; n < 8; n++) {
                        corners[n] = new Vector3f(f(p[20 + n]), f(p[28 + n]), f(p[36 + n]));
                    }
                }
                result.add(new BoxDef(position, size, offset, rotation, new Vector3f(1, 1, 1), uv, corners));
            }
            catch (RuntimeException ignored) { }
        }
        return result;
    }

    private List<BoxDef> parseJtmt(ZipHelper input) {
        ZipResource resource = input.getResource("model.jtmt"); if (resource == null) return List.of();
        JsonObject root = JsonParser.parseString(new String(bytes(resource.buffer()), StandardCharsets.UTF_8)).getAsJsonObject();
        List<BoxDef> result = new ArrayList<>(); JsonObject groups = root.has("groups") ? root.getAsJsonObject("groups") : new JsonObject();
        for (JsonElement group : groups.entrySet().stream().map(Map.Entry::getValue).toList()) {
            JsonArray polys = group.getAsJsonObject().getAsJsonArray("polygons"); if (polys == null) continue;
            for (JsonElement element : polys) { JsonObject p = element.getAsJsonObject(); if (!"box".equalsIgnoreCase(p.has("type") ? p.get("type").getAsString() : "")) continue;
                result.add(new BoxDef(new Vector3f(num(p,"pos_x"),num(p,"pos_y"),num(p,"pos_z")), new Vector3f(num(p,"width",1),num(p,"height",1),num(p,"depth",1)), new Vector3f(num(p,"off_x"),num(p,"off_y"),num(p,"off_z")), new Vector3f(), new Vector3f(num(p,"scale_x",1),num(p,"scale_y",1),num(p,"scale_z",1)), new Vector2i((int)num(p,"texture_x",0),(int)num(p,"texture_y",0)), null)); }
        }
        return result;
    }

    private static float f(String s) { return Float.parseFloat(s.trim().replace(',', '.')); }
    private static int i(String s) { return Integer.parseInt(s.trim()); }
    private static float num(JsonObject o, String key) { return num(o,key,0); }
    private static float num(JsonObject o, String key, double def) { return o.has(key) ? o.get(key).getAsFloat() : (float)def; }

    private void appendBox(BoxDef box, Material material, Bone root, List<Vertex> vertices, List<Mesh> meshes) {
        Vector3f dimensions = new Vector3f(box.size.x == 0f ? 0.01f : box.size.x,
                box.size.y == 0f ? 0.01f : box.size.y,
                box.size.z == 0f ? 0.01f : box.size.z);
        Vector3f localMin = new Vector3f(box.offset).mul(1f / 16f);
        Vector3f localMax = new Vector3f(box.offset).add(dimensions).mul(1f / 16f);
        Vector3f[][] faces = box.corners == null ? standardFaces(localMin, localMax) : shapeFaces(box);
        Transform transform = new Transform().translate(new Vector3f(box.position).mul(1f / 16f));
        RotHelper.rotation(transform, box.rotation);
        transform.scale(box.scale.x, box.scale.y, box.scale.z);
        for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
            Vector3f[] face = new Vector3f[4];
            for (int n = 0; n < 4; n++) face[n] = new Vector3f(faces[faceIndex][n]);
            for (Vector3f point : face) {
                transform.apply(point);
                // FMT/MTB coordinates use the opposite vertical handedness
                // from Minecraft: positive Y points down in the source model.
                // Convert each already-transformed block vertex exactly once.
                point.y = -point.y;
            }
            // Mirroring one coordinate reverses the polygon winding. Restore
            // the outward-facing order so back-face culling and normals remain
            // correct after the coordinate-system conversion.
            Vector3f windingVertex = face[1];
            face[1] = face[3];
            face[3] = windingVertex;
            Vector2f[] faceUvs = atlasUvs(box, faceIndex, (int) material.getTextures()[0].getWidth(), (int) material.getTextures()[0].getHeight());
            Vector2f windingUv = faceUvs[1];
            faceUvs[1] = faceUvs[3];
            faceUvs[3] = windingUv;
            collapseTriangleFace(face, faceUvs);
            Vector3f normal = new Vector3f(face[1]).sub(face[0]).cross(new Vector3f(face[2]).sub(face[0]));
            if (normal.lengthSquared() < 1e-10f) continue;
            normal.normalize();
            Mesh mesh = new Mesh(new Vertex[4], normal, new Transform(), new Material[]{material}, null);
            for (int n = 0; n < 4; n++) {
                Vertex v = new Vertex(new Vector3f(face[n]), null);
                v.addUV(mesh, material, faceUvs[n]);
                v.setBinding(new BoneBinding(new Pair[]{Pair.of(root, 1f)}, BoneBindingFunc.BDEF, null));
                mesh.getVertices()[n] = v;
                vertices.add(v);
            }
            meshes.add(mesh);
        }
    }

    /**
     * ShapeBox encodes a triangular end cap by collapsing two of its four
     * corner positions. The backend renders QUADS, so preserve that triangle
     * as {@code vec0, vec1, vec2, vec2} instead of submitting a malformed
     * four-corner polygon.
     */
    private static void collapseTriangleFace(Vector3f[] face, Vector2f[] uvs) {
        Vector3f[] uniquePositions = new Vector3f[3];
        Vector2f[] uniqueUvs = new Vector2f[3];
        int uniqueCount = 0;
        for (int i = 0; i < face.length; i++) {
            boolean duplicate = false;
            for (int j = 0; j < uniqueCount; j++) {
                if (face[i].distanceSquared(uniquePositions[j]) <= 1e-10f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                if (uniqueCount == uniquePositions.length) return;
                uniquePositions[uniqueCount] = face[i];
                uniqueUvs[uniqueCount] = uvs[i];
                uniqueCount++;
            }
        }
        if (uniqueCount != 3) return;
        for (int i = 0; i < 3; i++) {
            face[i] = uniquePositions[i];
            uvs[i] = uniqueUvs[i];
        }
        face[3] = new Vector3f(uniquePositions[2]);
        uvs[3] = new Vector2f(uniqueUvs[2]);
    }

    private static Vector3f[][] standardFaces(Vector3f min, Vector3f max) {
        Vector3f v0 = new Vector3f(min.x,min.y,min.z), v1 = new Vector3f(max.x,min.y,min.z),
                v2 = new Vector3f(max.x,max.y,min.z), v3 = new Vector3f(min.x,max.y,min.z),
                v4 = new Vector3f(min.x,min.y,max.z), v5 = new Vector3f(max.x,min.y,max.z),
                v6 = new Vector3f(max.x,max.y,max.z), v7 = new Vector3f(min.x,max.y,max.z);
        return new Vector3f[][]{{v5,v1,v2,v6},{v0,v4,v7,v3},{v5,v4,v0,v1},{v2,v3,v7,v6},{v1,v0,v3,v2},{v4,v5,v6,v7}};
    }

    private static Vector3f[][] shapeFaces(BoxDef box) {
        float x = box.offset.x, y = box.offset.y, z = box.offset.z;
        float xw = x + box.size.x, yh = y + box.size.y, zd = z + box.size.z;
        Vector3f[] c = box.corners;
        Vector3f[] v = {new Vector3f(x-c[0].x,y-c[0].y,z-c[0].z),new Vector3f(xw+c[1].x,y-c[1].y,z-c[1].z),
                new Vector3f(xw+c[5].x,yh+c[5].y,z-c[5].z),new Vector3f(x-c[4].x,yh+c[4].y,z-c[4].z),
                new Vector3f(x-c[3].x,y-c[3].y,zd+c[3].z),new Vector3f(xw+c[2].x,y-c[2].y,zd+c[2].z),
                new Vector3f(xw+c[6].x,yh+c[6].y,zd+c[6].z),new Vector3f(x-c[7].x,yh+c[7].y,zd+c[7].z)};
        for (Vector3f p : v) p.mul(1f/16f);
        return new Vector3f[][]{{v[5],v[1],v[2],v[6]},{v[0],v[4],v[7],v[3]},{v[5],v[4],v[0],v[1]},{v[2],v[3],v[7],v[6]},{v[1],v[0],v[3],v[2]},{v[4],v[5],v[6],v[7]}};
    }

    /** MTB stores the origin of the standard six-face cuboid UV net. */
    private static Vector2f[] atlasUvs(BoxDef box, int face, int textureWidth, int textureHeight) {
        float w = Math.abs(box.size.x), h = Math.abs(box.size.y), d = Math.abs(box.size.z);
        float u = box.uv.x, v = box.uv.y;
        float x, y, ex, ey;
        switch (face) {
            // This is the same net and face order as FMT's
            // Generators.genBox/genBoxFace implementation.
            case 0 -> { x = d + w; y = d; ex = d; ey = h; } // v5,v1,v2,v6
            case 1 -> { x = 0;     y = d; ex = d; ey = h; } // v0,v4,v7,v3
            case 2 -> { x = d;     y = 0; ex = w; ey = d; } // v5,v4,v0,v1
            case 3 -> { x = d + w; y = 0; ex = w; ey = d; } // v2,v3,v7,v6
            case 4 -> { x = d;     y = d; ex = w; ey = h; } // v1,v0,v3,v2
            default -> { x = d + w + d; y = d; ex = w; ey = h; } // v4,v5,v6,v7
        }
        float x0 = u + x, y0 = v + y, x1 = x0 + ex, y1 = y0 + ey;
        float sx = 1f / Math.max(1, textureWidth), sy = 1f / Math.max(1, textureHeight);
        // genBoxFace assigns UVs as (xe,ys), (xs,ys), (xs,ye), (xe,ye)
        // to match its face vertex order. Keep that pairing before the
        // winding correction in appendBox().
        return new Vector2f[]{new Vector2f(x1 * sx, y0 * sy), new Vector2f(x0 * sx, y0 * sy),
                new Vector2f(x0 * sx, y1 * sy), new Vector2f(x1 * sx, y1 * sy)};
    }

    private record BoxDef(Vector3f position, Vector3f size, Vector3f offset, Vector3f rotation, Vector3f scale, Vector2i uv, Vector3f[] corners) {}
    @Override public MaterialSetBuilder<Integer> materialSetBuilder(){return materials;} @Override public String getName(){return name;}
    @Override public boolean isValidInput(Object input){return input instanceof ZipHelper;} @Override public HashMap<SourceType,HashMap<String,SourceManager<?>>> getSidedSources(){return sidedSources;}
    @Override public Texture loadTexture(Object id){return materials.getTexture((Integer)id);}
}
