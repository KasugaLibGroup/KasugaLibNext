package lib.kasuga.rendering.models.mc.typo.fmt;

import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reader for FMT's BOB/BEO and FMF binary model streams. */
public final class FmtBinaryLoader implements ModelLoader<byte[], ResourceLocation, Integer> {
    private final String name;
    private final MaterialSetBuilder<Integer> materials;
    private final HashMap<SourceType, HashMap<String, SourceManager<?>>> sidedSources = new HashMap<>();
    private byte[] current;
    private ResourceLocation identifier;

    public FmtBinaryLoader(String name) { this.name = name; this.materials = new MaterialSetBuilder<>(this); }

    @Override public Map<ResourceLocation, Model> load(ResourceLocation id, byte[] input) {
        identifier = id; current = input;
        Material material = buildMaterial();
        List<Quad> quads = id.getPath().endsWith(".fmf") ? FmfRecords.read(input) : BobRecords.read(input);
        List<Vertex> vertices = new ArrayList<>(); List<Mesh> meshes = new ArrayList<>();
        Bone root = new Bone("root", new Transform(), null);
        for (Quad quad : quads) addQuad(quad, material, root, vertices, meshes);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new lib.kasuga.rendering.models.uml.structure.skeleton.Anchor[0], null, new Transform());
        return Map.of(id, new Model(vertices.toArray(Vertex[]::new), meshes.toArray(Mesh[]::new), new Bone[]{root}, skeleton,
                materials.endMaterialSet(), MeshMode.QUADS, null, null));
    }

    private Material buildMaterial() {
        // BOB/BEO carry UVs but deliberately do not embed the image.  FMT
        // resolves a sibling texture by model path, e.g.
        // models/fmt/traincraft/1.bob -> textures/fmt/traincraft/1.png.
        // Keep the missing sprite fallback for standalone files without one.
        ResourceLocation textureId = findExternalTexture();
        ResourceLocation loc = textureId == null ? MissingTextureAtlasSprite.getLocation() : textureId;
        Object source = textureId == null ? loc : textureId;
        BufferedImage image = textureId == null ? null : readExternalTexture(textureId);
        if (textureId != null) Constants.TEXTURE_BASIC.load(textureId);
        MCTexture texture = new MCTexture("main", () -> new net.minecraft.client.resources.model.Material(RenderState.KSG_LAYER_0, loc), 16, 16,
                new MCTextureData(source, Constants.TEXTURE_BASIC));
        materials.beginMaterial().setMaterialData(FmtMaterialData.from(image))
                .registerTexture(0, texture).useTexture(0)
                .addSpriteBuildingFunc((builder, sprites, material) -> sprites.textureId(0).endSprite())
                .endMaterial(0);
        return materials.getNamedMaterial(0);
    }

    private ResourceLocation findExternalTexture() {
        if (identifier == null) return null;
        String path = identifier.getPath();
        if (path.startsWith("models/")) path = path.substring("models/".length());
        int extension = path.lastIndexOf('.');
        if (extension >= 0) path = path.substring(0, extension);
        ResourceLocation textureId = ResourceLocation.tryBuild(identifier.getNamespace(), path);
        if (textureId == null) return null;
        ResourceLocation imageLocation = ResourceLocation.tryBuild(identifier.getNamespace(), "textures/" + path + ".png");
        if (imageLocation == null) return null;
        return Minecraft.getInstance().getResourceManager().getResource(imageLocation).isPresent() ? textureId : null;
    }

    private BufferedImage readExternalTexture(ResourceLocation textureId) {
        ResourceLocation imageLocation = ResourceLocation.fromNamespaceAndPath(textureId.getNamespace(),
                "textures/" + textureId.getPath() + ".png");
        try (InputStream stream = Minecraft.getInstance().getResourceManager()
                .getResourceOrThrow(imageLocation).open()) {
            return ImageIO.read(stream);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void addQuad(Quad quad, Material material, Bone root, List<Vertex> vertices, List<Mesh> meshes) {
        Vector3f[] p = quad.positions();
        Vector2f[] uvs = quad.uvs();
        if (p.length == 3) {
            p = new Vector3f[]{p[0], p[1], p[2], p[2]};
            uvs = new Vector2f[]{uvs[0], uvs[1], uvs[2], uvs[2]};
        }
        if (p.length != 4) return;
        Vector3f normal = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0]));
        if (normal.lengthSquared() < 1e-10f) return;
        normal.normalize();
        Mesh mesh = new Mesh(new Vertex[4], normal, new Transform(), new Material[]{material}, null);
        for (int i=0;i<4;i++) {
            Vertex v = new Vertex(new Vector3f(p[i]), null);
            v.addUV(mesh, material, uvs[i]);
            v.setBinding(new BoneBinding(new Pair[]{Pair.of(root, 1f)}, BoneBindingFunc.BDEF, null));
            mesh.getVertices()[i] = v;
            vertices.add(v);
        }
        meshes.add(mesh);
    }

    static final class BobRecords {
        static List<Quad> read(byte[] data) {
            List<Quad> out = new ArrayList<>(); ByteBuffer b = buffer(data, 4);
            List<Vector3f> vertices = new ArrayList<>(); List<Vector2f> uvs = new ArrayList<>();
            boolean object = false;
            Vector3f position = new Vector3f();
            Vector3f rotation = new Vector3f();
            while (b.hasRemaining()) {
                int code = b.get() & 255;
                try {
                    switch (code) {
                        case 0 -> object = false;
                        case 1 -> readString(b); // name
                        case 2 -> { // position / group
                            if (object) {
                                position.set(b.getFloat(), b.getFloat(), b.getFloat());
                            } else readString(b);
                        }
                        case 3 -> { // rotation / texture size
                            if (object) {
                                rotation.set(b.getFloat(), b.getFloat(), b.getFloat());
                                rotation.z = -rotation.z;
                            } else skip(b, 8);
                        }
                        case 4 -> { if (object) vertices.add(new Vector3f(b.getFloat(), b.getFloat(), b.getFloat())); else readString(b); }
                        case 5 -> {
                            if (object) uvs.add(new Vector2f(b.getFloat(), b.getFloat()));
                            else {
                                object = true;
                                position.zero();
                                rotation.zero();
                            }
                        }
                        case 6 -> skip(b, 12); // normal
                        case 7 -> {
                            int count = b.getInt();
                            if (count < 3 || count > 64 || b.remaining() < count * 8) throw new IllegalArgumentException("Invalid BOB face");
                            Vector3f[] p = new Vector3f[count];
                            Vector2f[] uv = new Vector2f[count];
                            boolean valid = true;
                            for (int i = 0; i < count; i++) {
                                int vi = b.getInt();
                                if (vi < 0 || vi >= vertices.size()) valid = false;
                                else p[i] = vertices.get(vi);
                            }
                            for (int i = 0; i < count; i++) {
                                int ui = b.getInt();
                                if (ui < 0 || ui >= uvs.size()) valid = false;
                                else uv[i] = uvs.get(ui);
                            }
                            if (valid && (count == 3 || count == 4)) {
                                Transform transform = new Transform()
                                        .translate(new Vector3f(position).mul(1f / 16f));
                                RotHelper.rotation(transform, rotation);
                                for (int i = 0; i < p.length; i++) {
                                    p[i] = new Vector3f(p[i]).mul(1f / 16f);
                                    transform.apply(p[i]);
                                    p[i].y = -p[i].y;
                                }
                                // Mirroring the source Y axis reverses the
                                // winding. Restore it together with the UV
                                // corner association so Minecraft's back-face
                                // culling keeps the BOB/BEO surfaces visible.
                                if (p.length == 3) {
                                    swap(p, 1, 2);
                                    swap(uv, 1, 2);
                                } else if (p.length == 4) {
                                    swap(p, 1, 3);
                                    swap(uv, 1, 3);
                                }
                                out.add(new Quad(p, uv));
                            }
                        }
                        default -> throw new IllegalArgumentException("Unknown BOB record " + code);
                    }
                } catch (RuntimeException e) {
                    break;
                }
            }
            return out;
        }
    }

    static final class FmfRecords {
        static List<Quad> read(byte[] data) {
            List<Quad> out = new ArrayList<>(); ByteBuffer b = buffer(data, 4); List<Vector3f> p = new ArrayList<>(); List<Vector2f> uv = new ArrayList<>();
            boolean object = false;
            while (b.hasRemaining()) { int code=b.get() & 255; if (code==0) { emit(out,p,uv); p.clear(); uv.clear(); object = false; continue; }
                try { switch(code) {
                    case 1 -> { if (object) { if (b.remaining() >= 12) p.add(new Vector3f(b.getFloat(),b.getFloat(),b.getFloat())); } else object = true; }
                    case 2 -> { if (object) { if (b.remaining() >= 12) p.add(new Vector3f(b.getFloat(),b.getFloat(),b.getFloat())); } else readString(b); }
                    case 3 -> { if (object) { if (b.remaining() >= 12) p.add(new Vector3f(b.getFloat(),b.getFloat(),b.getFloat())); } else skip(b, 8); }
                    case 4 -> { if (object) skip(b, 8); else readString(b); }
                    case 5,7 -> readString(b);
                    case 6 -> skip(b, 4);
                    case 8 -> { emit(out,p,uv); p.clear(); uv.clear(); }
                    case 9,10 -> skip(b, 4);
                } } catch (RuntimeException e) { break; }
            }
            emit(out,p,uv); return out;
        }
        private static void emit(List<Quad> out,List<Vector3f> p,List<Vector2f> uv){ if(p.size() >= 3){ int n=Math.min(p.size(),4); Vector3f[] pp=p.subList(0,n).toArray(Vector3f[]::new); Vector2f[] uu=new Vector2f[n]; for(int i=0;i<n;i++) uu[i]=i<uv.size()?uv.get(i):new Vector2f(); if(n==4) out.add(new Quad(pp,uu)); } }
    }

    private static ByteBuffer buffer(byte[] data, int start) { return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).position(Math.min(start, data.length)); }
    private static void skip(ByteBuffer b, int count) { if (b.remaining() < count) throw new IllegalArgumentException(); b.position(b.position()+count); }
    private static String readString(ByteBuffer b) { int start=b.position(); while(b.hasRemaining() && b.get()!=0); return new String(data(b,start,b.position()-start-1), StandardCharsets.UTF_8); }
    private static byte[] data(ByteBuffer b,int p,int n){byte[] x=new byte[Math.max(0,n)]; ByteBuffer c=b.duplicate(); c.position(p); c.get(x); return x;}
    private static <T> void swap(T[] values, int a, int b) {
        T value = values[a];
        values[a] = values[b];
        values[b] = value;
    }
    record Quad(Vector3f[] positions, Vector2f[] uvs) {}

    @Override public MaterialSetBuilder<Integer> materialSetBuilder(){return materials;} @Override public String getName(){return name;}
    @Override public boolean isValidInput(Object input){return input instanceof byte[];} @Override public HashMap<SourceType,HashMap<String,SourceManager<?>>> getSidedSources(){return sidedSources;}
    @Override public Texture loadTexture(Object id){return materials.getTexture((Integer)id);}
}
