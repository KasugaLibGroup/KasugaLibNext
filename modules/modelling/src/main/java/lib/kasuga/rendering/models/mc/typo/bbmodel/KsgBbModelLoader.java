package lib.kasuga.rendering.models.mc.typo.bbmodel;

import com.google.gson.JsonParseException;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads Blockbench's native .bbmodel JSON format into the UML model structure. */
public final class KsgBbModelLoader implements ModelLoader<String, ResourceLocation, Integer> {
    private final String name;
    private final MaterialSetBuilder<Integer> materialSetBuilder;
    private final HashMap<SourceType, HashMap<String, SourceManager<?>>> sidedSources;

    public KsgBbModelLoader(String name) {
        this.name = name;
        this.materialSetBuilder = new MaterialSetBuilder<>(this);
        this.sidedSources = new HashMap<>();
    }

    @Override
    public Map<ResourceLocation, Model> load(ResourceLocation identifier, String input) {
        BbModelDefinition definition;
        try {
            definition = BbModelDefinition.parse(input);
        } catch (IllegalArgumentException | JsonParseException exception) {
            throw new IllegalArgumentException("Invalid Blockbench model " + identifier, exception);
        }

        Map<Integer, Material> materials = buildMaterials(identifier, definition);
        Model model = buildSkeletonAndGeometry(definition, materials, materialSetBuilder.endMaterialSet());
        return Map.of(identifier, model);
    }

    /**
     * Pure geometry + skeleton construction (no Minecraft runtime state): tests drive it directly with
     * stub materials. Named outliner groups become skeleton bones (bind = pivot offset relative to the
     * nearest named ancestor, translation-only — the group's own rotation stays baked into the vertices),
     * so the bind pose is byte-identical to the flattened loader; runtime bone rotations pivot around the
     * group origin via the BDEF conjugation. Unnamed groups flatten into their nearest named ancestor.
     */
    static Model buildSkeletonAndGeometry(BbModelDefinition definition, Map<Integer, Material> materials,
                                          MaterialSet materialSet) {
        List<Vertex> vertices = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        Bone root = new Bone("root", new Transform(), null);
        List<Bone> bones = new ArrayList<>();
        bones.add(root);

        if (definition.outliner().isEmpty()) {
            for (BbModelDefinition.Element element : definition.elements().values()) {
                appendElement(element, BlockBenchTransform.IDENTITY, true, definition, materials, root, vertices, meshes);
            }
        } else {
            // Pass 1: resolve each group's pivot in Blockbench pixels — explicit origin, else the average
            // of the subtree element origins (auto-exported models often drop group origins).
            Map<BbModelDefinition.GroupNode, Vector3f> pivots =
                    computeGroupPivots(definition.outliner(), definition.elements());
            // Pass 2: walk the outliner building bones + binding vertices to their nearest named ancestor.
            for (BbModelDefinition.OutlineNode node : definition.outliner()) {
                appendOutline(node, BlockBenchTransform.IDENTITY, true, definition, materials, pivots,
                        root, ZERO_PX, bones, vertices, meshes);
            }
            wireChildren(bones);
        }

        Skeleton skeleton = new Skeleton(bones.toArray(new Bone[0]), root, new lib.kasuga.rendering.models.uml.structure.skeleton.Anchor[0], null, new Transform());
        return new Model(
                vertices.toArray(new Vertex[0]), meshes.toArray(new Mesh[0]), bones.toArray(new Bone[0]), skeleton,
                materialSet, MeshMode.QUADS, null, null
        );
    }

    private static final Vector3f ZERO_PX = new Vector3f();


    /**
     * Resolves every outliner group's pivot (Blockbench pixels). Explicit {@code origin} wins; a group
     * without one falls back to the mean of its subtree element origins — for uniformly-pivoted content
     * (e.g. fan blades sharing one origin) that equals the intended rotation center.
     */
    static Map<BbModelDefinition.GroupNode, Vector3f> computeGroupPivots(
            List<BbModelDefinition.OutlineNode> outliner, Map<String, BbModelDefinition.Element> elements) {
        Map<BbModelDefinition.GroupNode, Vector3f> pivots = new HashMap<>();
        for (BbModelDefinition.OutlineNode node : outliner) {
            collectGroupPivots(node, elements, pivots);
        }
        return pivots;
    }

    private static Vector3f collectGroupPivots(BbModelDefinition.OutlineNode node,
                                               Map<String, BbModelDefinition.Element> elements,
                                               Map<BbModelDefinition.GroupNode, Vector3f> pivots) {
        if (node instanceof BbModelDefinition.ElementNode elementNode) {
            BbModelDefinition.Element element = elements.get(elementNode.elementId());
            return element == null ? null : new Vector3f(element.origin());
        }
        BbModelDefinition.GroupNode group = (BbModelDefinition.GroupNode) node;
        Vector3f explicit = group.origin();
        if (explicit != null) {
            pivots.put(group, new Vector3f(explicit));
        }
        Vector3f sum = new Vector3f();
        int count = 0;
        for (BbModelDefinition.OutlineNode child : group.children()) {
            Vector3f childOrigin = collectGroupPivots(child, elements, pivots);
            if (childOrigin != null) {
                sum.add(childOrigin);
                count++;
            }
        }
        if (explicit == null) {
            pivots.put(group, count == 0 ? new Vector3f() : sum.div(count));
        }
        return explicit == null ? pivots.get(group) : new Vector3f(explicit);
    }

    /** Wires each created bone's {@code children} array from the parent links established during the walk. */
    private static void wireChildren(List<Bone> bones) {
        Map<Bone, List<Bone>> childrenByParent = new HashMap<>();
        for (Bone bone : bones) {
            if (bone.getParent() != null) {
                childrenByParent.computeIfAbsent(bone.getParent(), ignored -> new ArrayList<>()).add(bone);
            }
        }
        childrenByParent.forEach((parent, children) ->
                parent.setChildren(children.toArray(new Bone[0])));
    }

    private Map<Integer, Material> buildMaterials(ResourceLocation identifier, BbModelDefinition definition) {
        Map<Integer, Material> result = new HashMap<>();
        for (BbModelDefinition.Texture texture : definition.textures()) {
            int textureReference = textureReference(texture);
            MCTexture modelTexture = createTexture(identifier, texture, definition);
            materialSetBuilder.registerTexture(textureReference, modelTexture)
                    .useTexture(textureReference)
                    .addSpriteBuildingFunc((builder, sprites, material) -> sprites.textureId(textureReference).endSprite())
                    .endMaterial(textureReference);
            result.put(textureReference, materialSetBuilder.getNamedMaterial(textureReference));
        }
        return result;
    }

    static int textureReference(BbModelDefinition.Texture texture) {
        // In native .bbmodel files, face.texture indexes the textures array.
        return texture.index();
    }

    private MCTexture createTexture(ResourceLocation modelIdentifier, BbModelDefinition.Texture texture,
                                    BbModelDefinition definition) {
        KasugaTextureManager textureManager = Constants.TEXTURE_BASIC;
        Object sourceIdentifier;
        ResourceLocation textureIdentifier;
        int[] canvas = uvCanvas(texture, definition);
        if (texture.source().startsWith("data:image/")) {
            BufferedImage image = decodeImage(texture.source(), modelIdentifier);
            textureIdentifier = embeddedTextureLocation(modelIdentifier, texture.index());
            sourceIdentifier = Pair.of(textureIdentifier, image);
        } else {
            textureIdentifier = resolveTextureLocation(modelIdentifier, texture.source(), texture.name());
            sourceIdentifier = textureIdentifier;
        }
        textureManager.load(sourceIdentifier);
        net.minecraft.client.resources.model.Material material =
                new net.minecraft.client.resources.model.Material(RenderState.KSG_LAYER_0, textureIdentifier);
        // The MCTexture size is the divisor normalizePixelUv() uses, so it must be the UV canvas — never the
        // decoded image size, which only supplies the sprite content.
        return new MCTexture(texture.id(), () -> material, canvas[0], canvas[1],
                new MCTextureData(sourceIdentifier, textureManager, true));
    }

    /**
     * Blockbench records face and mesh UVs in the texture's UV canvas: {@code uv_width}/{@code uv_height}
     * when the file declares them, otherwise the declared (or project) texture size. It is deliberately
     * independent of the decoded image, because Blockbench stretches an image over its canvas — a 64×64 png
     * under a 128×128 canvas still carries UVs up to 128 (see {@code qj_bogey_main.bbmodel}).
     */
    static int[] uvCanvas(BbModelDefinition.Texture texture, BbModelDefinition definition) {
        return new int[]{
                positiveOr(texture.uvWidth(), positiveOr(texture.width(), definition.textureWidth())),
                positiveOr(texture.uvHeight(), positiveOr(texture.height(), definition.textureHeight()))
        };
    }

    /**
     * Walks the outliner building both the baked geometry and the skeleton. {@code bindTarget} is the
     * nearest named ancestor bone (or {@code root}) — unnamed groups keep it, named groups create a new
     * bone whose children (elements and nested groups) bind to it. {@code parentBonePivotPx} is the
     * nearest named ancestor's pivot in Blockbench pixels, used for the relative bind offset.
     */
    private static void appendOutline(BbModelDefinition.OutlineNode node, BlockBenchTransform parentTransform, boolean parentVisible,
                               BbModelDefinition definition, Map<Integer, Material> materials,
                               Map<BbModelDefinition.GroupNode, Vector3f> pivots, Bone bindTarget, Vector3f parentBonePivotPx,
                               List<Bone> bones, List<Vertex> vertices, List<Mesh> meshes) {
        if (node instanceof BbModelDefinition.ElementNode elementNode) {
            BbModelDefinition.Element element = definition.elements().get(elementNode.elementId());
            if (element != null) {
                appendElement(element, parentTransform, parentVisible, definition, materials, bindTarget, vertices, meshes);
            }
            return;
        }
        BbModelDefinition.GroupNode group = (BbModelDefinition.GroupNode) node;
        // The bake uses the RAW origin (null → zero, exactly the pre-bone behavior): the group's own
        // rotation and pivot remain folded into the vertices, so the bind pose is byte-identical to the
        // flattened loader. The bone pivot (explicit or fallback) only decides where runtime rotations
        // rotate AROUND — it never affects the baked coordinates.
        Vector3f rawOrigin = group.origin() == null ? ZERO_PX : group.origin();
        BlockBenchTransform transform = parentTransform.child(rawOrigin, group.rotation());
        Bone nextBind = bindTarget;
        Vector3f nextParentPivotPx = parentBonePivotPx;
        if (!group.name().isEmpty()) {
            Vector3f pivotPx = pivots.get(group);
            Vector3f offset = new Vector3f(pivotPx).sub(parentBonePivotPx).mul(1.0f / 16.0f);
            Bone bone = new Bone(group.name(), new Transform().translate(offset.x, offset.y, offset.z), null);
            bone.setParent(bindTarget);
            bones.add(bone);
            nextBind = bone;
            nextParentPivotPx = pivotPx;
        }
        for (BbModelDefinition.OutlineNode child : group.children()) {
            appendOutline(child, transform, parentVisible && group.visible(), definition, materials, pivots,
                    nextBind, nextParentPivotPx, bones, vertices, meshes);
        }
    }

    private static void appendElement(BbModelDefinition.Element element, BlockBenchTransform parentTransform, boolean parentVisible,
                               BbModelDefinition definition, Map<Integer, Material> materials, Bone bindTarget,
                               List<Vertex> vertices, List<Mesh> meshes) {
        if (!parentVisible || !element.visible()) return;
        BlockBenchTransform transform = parentTransform.child(element.origin(), element.rotation());
        if ("mesh".equals(element.type())) {
            appendMeshElement(element, transform, definition, materials, bindTarget, vertices, meshes);
        } else {
            appendCubeElement(element, transform, definition, materials, bindTarget, vertices, meshes);
        }
    }

    private static void appendCubeElement(BbModelDefinition.Element element, BlockBenchTransform transform, BbModelDefinition definition,
                                   Map<Integer, Material> materials, Bone bindTarget, List<Vertex> vertices, List<Mesh> meshes) {
        Vector3f from = new Vector3f(element.from()).sub(element.origin()).mul(1.0f / 16.0f);
        Vector3f to = new Vector3f(element.to()).sub(element.origin()).mul(1.0f / 16.0f);
        for (Map.Entry<lib.kasuga.rendering.models.mc.util.Direction, BbModelDefinition.Face> entry : element.cubeFaces().entrySet()) {
            Material material = materials.get(entry.getValue().texture());
            if (material == null) continue;
            Vector3f[] positions = cubeFacePositions(entry.getKey(), from, to);
            Vector2f[] uvs = rectangularUvs(entry.getValue().uv(), entry.getValue().rotation());
            appendFace(positions, uvs, transform, material, bindTarget, vertices, meshes);
        }
    }

    private static void appendMeshElement(BbModelDefinition.Element element, BlockBenchTransform transform, BbModelDefinition definition,
                                   Map<Integer, Material> materials, Bone bindTarget, List<Vertex> vertices, List<Mesh> meshes) {
        for (BbModelDefinition.MeshFace face : element.meshFaces()) {
            Material material = materials.get(face.texture());
            if (material == null || face.vertices().size() < 3) continue;
            List<String> names = face.vertices();
            if (names.size() <= 4) {
                appendMeshFace(element, sortMeshFaceVertices(element.vertices(), names),
                        transform, definition, face, material, bindTarget, vertices, meshes);
                continue;
            }
            for (int index = 1; index < names.size() - 1; index++) {
                appendMeshFace(element, List.of(names.get(0), names.get(index), names.get(index + 1)),
                        transform, definition, face, material, bindTarget, vertices, meshes);
            }
        }
    }

    private static void appendMeshFace(BbModelDefinition.Element element, List<String> names,
                                BlockBenchTransform transform, BbModelDefinition definition,
                                BbModelDefinition.MeshFace face, Material material, Bone bindTarget,
                                List<Vertex> vertices, List<Mesh> meshes) {
        Vector3f[] positions = new Vector3f[names.size()];
        Vector2f[] uvs = new Vector2f[names.size()];
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            Vector3f position = element.vertices().get(name);
            Vector2f uv = face.uvs().get(name);
            if (position == null || uv == null) return;
            positions[index] = new Vector3f(position).mul(1.0f / 16.0f);
            // Kept in Blockbench pixels; appendFace() normalizes them by the material's UV canvas.
            uvs[index] = new Vector2f(uv);
        }
        appendFace(positions, uvs, transform, material, bindTarget, vertices, meshes);
    }

    static List<String> sortMeshFaceVertices(Map<String, Vector3f> vertices, List<String> names) {
        if (names.size() != 4) return names;
        Vector3f first = vertices.get(names.get(0));
        Vector3f second = vertices.get(names.get(1));
        Vector3f third = vertices.get(names.get(2));
        Vector3f fourth = vertices.get(names.get(3));
        if (first == null || second == null || third == null || fourth == null) return names;

        if (isCrossedQuad(second, third, first, fourth)) {
            return List.of(names.get(2), names.get(0), names.get(1), names.get(3));
        }
        if (isCrossedQuad(first, second, third, fourth)) {
            return List.of(names.get(0), names.get(2), names.get(1), names.get(3));
        }
        return names;
    }

    private static boolean isCrossedQuad(Vector3f baseFirst, Vector3f baseSecond,
                                         Vector3f top, Vector3f check) {
        Vector3f line = new Vector3f(baseSecond).sub(baseFirst);
        float lengthSquared = line.lengthSquared();
        if (lengthSquared == 0.0f) return false;
        float projection = new Vector3f(top).sub(baseFirst).dot(line) / lengthSquared;
        Vector3f projectedPoint = new Vector3f(baseFirst).fma(projection, line);
        Vector3f normal = projectedPoint.sub(top);
        return normal.dot(new Vector3f(check).sub(baseSecond)) > 0.0f;
    }

    private static void appendFace(Vector3f[] positions, Vector2f[] uvs, BlockBenchTransform transform, Material material, Bone bindTarget,
                            List<Vertex> vertices, List<Mesh> meshes) {
        Vector3f[] transformed = new Vector3f[positions.length];
        for (int index = 0; index < positions.length; index++) transformed[index] = transform.apply(positions[index]);
        Vector3f cross = new Vector3f(transformed[1]).sub(transformed[0])
                .cross(new Vector3f(transformed[2]).sub(transformed[0]));
        // Zero-area faces (zero-thickness panels exported by Blockbench) have a zero-length cross product;
        // normalize() would yield NaN normal components that poison the shader's lighting (black geometry).
        // The face is invisible anyway — skip it.
        if (cross.lengthSquared() <= 1e-12f) {
            return;
        }
        Vector3f normal = cross.normalize();
        Mesh mesh = new Mesh(new Vertex[transformed.length], normal, new Transform(), new Material[]{material}, null);
        for (int index = 0; index < transformed.length; index++) {
            Vertex vertex = new Vertex(transformed[index], null);
            vertex.addUV(mesh, material, normalizePixelUv(uvs[index], material));
            // Bind to the nearest named ancestor bone (or root): BDEF skinning conjugates the pivot
            // translation (W·B⁻¹), so runtime rotations of that bone pivot AROUND its bind origin.
            vertex.setBinding(new BoneBinding(new Pair[]{Pair.of(bindTarget, 1.0f)}, BoneBindingFunc.BDEF, null));
            mesh.getVertices()[index] = vertex;
            vertices.add(vertex);
        }
        meshes.add(mesh);
    }

    /** Converts Blockbench's pixel UV coordinates to the normalized range used by the renderer. */
    static Vector2f normalizePixelUv(Vector2f uv, Material material) {
        Texture[] textures = material.getTextures();
        if (textures.length == 0 || textures[0] == null) return new Vector2f(uv);
        float width = textures[0].getWidth();
        float height = textures[0].getHeight();
        return width > 0.0f && height > 0.0f
                ? new Vector2f(uv.x / width, uv.y / height)
                : new Vector2f(uv);
    }

    private record BlockBenchTransform(Quaternionf rotation, Vector3f absoluteOrigin, Vector3f parentPivot) {
        private static final BlockBenchTransform IDENTITY = new BlockBenchTransform(
                new Quaternionf(), new Vector3f(), new Vector3f()
        );

        private BlockBenchTransform child(Vector3f pivot, Vector3f localRotation) {
            Vector3f scaledPivot = new Vector3f(pivot).mul(1.0f / 16.0f);
            Vector3f origin = scaledPivot.sub(parentPivot).rotate(rotation).add(absoluteOrigin);
            // Blockbench composes Z→Y→X (see QuaternionHelper.fromZYXAngle): a group's or element's own
            // rotation is applied in its parent's already-rotated frame, around its own origin.
            Quaternionf combinedRotation = new Quaternionf(rotation).mul(QuaternionHelper.fromZYXDegrees(localRotation));
            return new BlockBenchTransform(combinedRotation, origin, scaledPivot);
        }

        private Vector3f apply(Vector3f localPosition) {
            return new Vector3f(localPosition).rotate(rotation).add(absoluteOrigin);
        }
    }

    /**
     * Corner order of each cube face, as seen from OUTSIDE the cube: top-left, bottom-left, bottom-right,
     * top-right of the face's UV rectangle.
     *
     * <p>This is the order Blockbench maps a face's {@code uv} rectangle onto ({@code CubeFace.UVToLocal}):
     * for every face {@code u} runs to the viewer's right and {@code v} downward, with the top face using
     * {@code u = +X, v = +Z} and the bottom face {@code u = +X, v = -Z}. It is also the vanilla
     * {@code FaceInfo} corner order, and it is the winding the backend needs for outward normals — the
     * rectangle's natural top-left → top-right → bottom-right → bottom-left order is back-facing.
     */
    private static Vector3f[] cubeFacePositions(lib.kasuga.rendering.models.mc.util.Direction direction, Vector3f min, Vector3f max) {
        return switch (direction) {
            case DOWN -> new Vector3f[]{new Vector3f(min.x, min.y, max.z), new Vector3f(min.x, min.y, min.z), new Vector3f(max.x, min.y, min.z), new Vector3f(max.x, min.y, max.z)};
            case UP -> new Vector3f[]{new Vector3f(min.x, max.y, min.z), new Vector3f(min.x, max.y, max.z), new Vector3f(max.x, max.y, max.z), new Vector3f(max.x, max.y, min.z)};
            case NORTH -> new Vector3f[]{new Vector3f(max.x, max.y, min.z), new Vector3f(max.x, min.y, min.z), new Vector3f(min.x, min.y, min.z), new Vector3f(min.x, max.y, min.z)};
            case SOUTH -> new Vector3f[]{new Vector3f(min.x, max.y, max.z), new Vector3f(min.x, min.y, max.z), new Vector3f(max.x, min.y, max.z), new Vector3f(max.x, max.y, max.z)};
            case WEST -> new Vector3f[]{new Vector3f(min.x, max.y, min.z), new Vector3f(min.x, min.y, min.z), new Vector3f(min.x, min.y, max.z), new Vector3f(min.x, max.y, max.z)};
            case EAST -> new Vector3f[]{new Vector3f(max.x, max.y, max.z), new Vector3f(max.x, min.y, max.z), new Vector3f(max.x, min.y, min.z), new Vector3f(max.x, max.y, min.z)};
        };
    }

    /**
     * UV rectangle corners for {@link #cubeFacePositions}: left-top, left-bottom, right-bottom, right-top —
     * the same top-left → bottom-left → bottom-right → top-right tour. {@code rotation} turns the texture
     * clockwise (Blockbench/vanilla semantics), so the corner feeding the face's top-left slot walks
     * backwards through the rectangle.
     */
    static Vector2f[] rectangularUvs(float[] uv, int rotation) {
        Vector2f[] result = {
                new Vector2f(uv[0], uv[1]), new Vector2f(uv[0], uv[3]),
                new Vector2f(uv[2], uv[3]), new Vector2f(uv[2], uv[1])
        };
        int turns = Math.floorMod(rotation / 90, 4);
        if (turns == 0) return result;
        Vector2f[] rotated = new Vector2f[4];
        for (int index = 0; index < 4; index++) rotated[index] = result[(index + turns) % 4];
        return rotated;
    }

    static ResourceLocation resolveTextureLocation(ResourceLocation model, String source, String name) {
        ResourceLocation sourceLocation = source.contains(":")
                ? ResourceLocation.parse(source)
                : ResourceLocation.fromNamespaceAndPath(model.getNamespace(), source.isBlank() ? name : source);
        String path = sourceLocation.getPath();
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (path.endsWith(".png")) path = path.substring(0, path.length() - ".png".length());
        return ResourceLocation.fromNamespaceAndPath(sourceLocation.getNamespace(), path);
    }

    private static ResourceLocation embeddedTextureLocation(ResourceLocation model, int index) {
        String path = model.getPath().replaceAll("[^a-z0-9_./-]", "_").replace(".bbmodel", "");
        // Atlas sprite identifiers omit the conventional textures/ directory and .png suffix.
        // Keeping those filesystem markers here makes the stitched sprite lookup miss.
        return ResourceLocation.fromNamespaceAndPath(model.getNamespace(), "bbmodel/" + path + "_" + index);
    }

    private static BufferedImage decodeImage(String source, ResourceLocation model) {
        int separator = source.indexOf(',');
        if (separator < 0) throw new IllegalArgumentException("Invalid embedded texture in " + model);
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(source.substring(separator + 1))));
            if (image == null) throw new IllegalArgumentException("Unsupported embedded texture in " + model);
            return image;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unable to decode embedded texture in " + model, exception);
        }
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    @Override public MaterialSetBuilder<Integer> materialSetBuilder() { return materialSetBuilder; }
    @Override public String getName() { return name; }
    @Override public boolean isValidInput(Object input) { return input instanceof String; }
    @Override public HashMap<SourceType, HashMap<String, SourceManager<?>>> getSidedSources() { return sidedSources; }
    @Override public Texture loadTexture(Object textureIdentifier) { return materialSetBuilder.getTexture((Integer) textureIdentifier); }
}
