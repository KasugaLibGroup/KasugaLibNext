package lib.kasuga.rendering.models.mc.typo.bbmodel;

import lib.kasuga.rendering.models.uml.backend.Backend;
import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KsgBbModelLoader skeleton-exposure tests: named outliner groups become skeleton bones with pivot bind
 * transforms, vertices bind to their nearest named ancestor, bind pose is unchanged, and runtime bone
 * rotations pivot AROUND the group origin (BDEF conjugation, W·B⁻¹) — not the model origin.
 *
 * <p>The real {@code test_fan_be.bbmodel} (named groups: {@code group}/{@code cover}/{@code fan}/{@code set},
 * from the file's {@code groups[]} metadata) exercises the full asset; synthetic definitions cover the
 * unnamed-group flattening and the missing-origin pivot fallback.
 */
class KsgBbModelLoaderSkeletonTest {

    @Test
    void unnamedBlockbenchAnimationDoesNotPreventModelLoading() {
        var definition = BbModelDefinition.parse("{\"animations\":[{\"length\":1}]}");
        var materials = lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture.minimal()
                .getModel().getMaterialSet();
        Model model = KsgBbModelLoader.buildSkeletonAndGeometry(definition, Map.of(), materials);
        assertTrue(model.getAnimations().contains("animation_0"));
    }

    @Test
    void blockbenchClipsUseTheSameNamedPosingEntryAsGltfAndPlainModels() {
        BbModelDefinition definition = BbModelDefinition.parse("""
                {"animations":[{"name":"move","length":1,"animators":{
                  "root-id":{"name":"root","type":"bone","keyframes":[
                    {"channel":"position","time":0,"data_points":[{"x":"0","y":"0","z":"0"}]},
                    {"channel":"position","time":1,"data_points":[{"x":"32","y":"0","z":"0"}]}
                  ]}
                }}]}
                """);
        MaterialSet materials = lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture.minimal()
                .getModel().getMaterialSet();
        Model model = KsgBbModelLoader.buildSkeletonAndGeometry(definition, Map.of(), materials);
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        assertTrue(instance.getPosing().hasClip("move"));
        assertTrue(instance.getPosing().play("move", false));
        instance.animate(1); instance.sample(1);
        assertEquals(2, instance.getSkeletonInstance().getTransforms().get(model.getSkeleton().getRoot())
                .getPosition().x, 1e-5);
        var body = lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture.minimal().getModel();
        var outfit = new lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssemblyBuilder("body", body, 0)
                .part("mc-cloth", model, 0).assemble();
        var combined = new ModelInstance(outfit.model(), null, null, null, null, null);
        assertTrue(combined.getPosing().play("mc-cloth/move", false));
        combined.animate(1); combined.sample(1);
        assertEquals(2, combined.getSkeletonInstance().getTransforms().get(outfit.model().getSkeleton().getRoot())
                .getPosition().x, 1e-5);
    }

    private static final float EPS = 1e-4f;

    /** BDEF skinning only — the uml skinning is CPU-side, no MC runtime needed. */
    private static final Bridge<Object> BDEF_BRIDGE = new Bridge<>() {
        @Override
        public HashMap<Vertex, Vertex> transformVertices(Model model, SkeletonInstance skeleton, Vertex[] vertices) {
            return new HashMap<>();
        }

        @Override
        public Mesh[] transformMeshes(Model model, SkeletonInstance skeleton, Mesh[] meshes) {
            return meshes;
        }

        @Override
        public Object getBackendRenderable(ModelInstance modelInstance, HashMap<Vertex, Vertex> vertices, Mesh[] meshes) {
            return null;
        }

        @Override
        public BoneBindingFunc getBoneBindingFunc(Model model, SkeletonInstance skeleton, Vertex vertex) {
            return BoneBindingFunc.BDEF;
        }

        @Override
        public BackendContext<?, Object, ?, ?> getBackendContext(ModelInstance modelInstance) {
            return null;
        }

        @Override
        public void setBackends(Map<String, Backend<?, Object, ?, ?>> backends) {
        }

        @Override
        public Map<String, Backend<?, Object, ?, ?>> getBackends() {
            return Map.of();
        }
    };

    private static ModelInstance loadFanAsset() throws Exception {
        String input = Files.readString(findFanAsset());
        BbModelDefinition definition = BbModelDefinition.parse(input);
        Texture texture = new Texture("fan_tex", 64f, 64f, null);
        Material material = new Material(new Texture[]{texture}, null);
        Map<Integer, Material> materials = Map.of(0, material);
        Model model = KsgBbModelLoader.buildSkeletonAndGeometry(definition, materials, new MaterialSet(texture, material));
        return new ModelInstance(model, null, null, null, null, null);
    }

    /** Locates the shared fan asset by walking up from the test working dir to the repository root. */
    private static Path findFanAsset() {
        Path relative = Path.of("modules/modelling/src/contentTesting/resources/assets/kasuga_lib/models/be/test_fan_be.bbmodel");
        for (Path dir = Path.of(System.getProperty("user.dir")); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot locate test_fan_be.bbmodel (cwd=" + System.getProperty("user.dir") + ")");
    }

    private static Bone bone(ModelInstance instance, String name) {
        return instance.getSkeletonInstance().getSkeleton().getBoneMap().get(name);
    }

    private static List<Vertex> verticesBoundTo(ModelInstance instance, String boneName) {
        return Arrays.stream(instance.getModel().getVertices())
                .filter(v -> v.getBinding() != null
                        && v.getBinding().getWeights().length > 0
                        && boneName.equals(v.getBinding().getWeights()[0].getFirst().getName()))
                .toList();
    }

    private static Vector3f skinnedPosition(ModelInstance instance, Vertex vertex) {
        return instance.getSkeletonInstance().getVertexTransforms(instance.getModel(), BDEF_BRIDGE)
                .get(vertex).getPosition();
    }

    @Test
    void namedGroupsBecomeBones() throws Exception {
        ModelInstance instance = loadFanAsset();
        Map<String, Bone> boneMap = instance.getSkeletonInstance().getSkeleton().getBoneMap();
        assertTrue(boneMap.containsKey("root"));
        assertTrue(boneMap.containsKey("group"));
        assertTrue(boneMap.containsKey("cover"));
        assertTrue(boneMap.containsKey("fan"));
        assertTrue(boneMap.containsKey("set"));
        // The outermost container group ("13") is a named group too — it becomes an inert bone (nothing
        // animates it), keeping the rule simple: every named group is a bone, regardless of position.
        assertTrue(boneMap.containsKey("13"));
    }

    @Test
    void boneHierarchyMatchesOutliner() throws Exception {
        ModelInstance instance = loadFanAsset();
        Bone container = bone(instance, "13");
        Bone group = bone(instance, "group");
        Bone cover = bone(instance, "cover");
        Bone fan = bone(instance, "fan");
        Bone set = bone(instance, "set");
        Bone root = bone(instance, "root");
        assertNotNull(group);
        assertEquals(container, group.getParent(), "container '13' wraps the whole model");
        assertEquals(root, container.getParent());
        assertEquals(group, cover.getParent());
        assertEquals(cover, fan.getParent(), "fan is nested inside cover in the outliner");
        assertEquals(group, set.getParent());
    }

    @Test
    void boneBindTransformsAreRelativePivots() throws Exception {
        ModelInstance instance = loadFanAsset();
        // container "13" [0,15.5,0] − root(0)
        assertPosition(bone(instance, "13").getTransform(), 0f, 15.5f / 16f, 0f);
        // group [0,9,0] − container [0,15.5,0]
        assertPosition(bone(instance, "group").getTransform(), 0f, -6.5f / 16f, 0f);
        // cover [−0.00205,10.43221,−0.00027] − group [0,9,0]
        assertPosition(bone(instance, "cover").getTransform(),
                -0.00205f / 16f, 1.43221f / 16f, -0.00027f / 16f);
        // fan [0,5.869,0] − cover
        assertPosition(bone(instance, "fan").getTransform(),
                0.00205f / 16f, (5.869f - 10.43221f) / 16f, 0.00027f / 16f);
        // set [−1,−6,−1] − group [0,9,0]
        assertPosition(bone(instance, "set").getTransform(),
                -1f / 16f, -15f / 16f, -1f / 16f);
    }

    @Test
    void verticesBindToNearestNamedAncestor() throws Exception {
        ModelInstance instance = loadFanAsset();
        // The fan group has real geometry (axle cube); the cover and set groups own their elements.
        assertFalse(verticesBoundTo(instance, "fan").isEmpty());
        assertFalse(verticesBoundTo(instance, "cover").isEmpty());
        assertFalse(verticesBoundTo(instance, "set").isEmpty());
        // The pedestal element (bottom cube, origin [0,0,1]) is a direct child of the container "13" —
        // no named group between it and the container → binds "13".
        boolean pedestalFound = false;
        for (Vertex vertex : verticesBoundTo(instance, "13")) {
            if (Math.abs(vertex.getPosition().y - 1f) < 1e-3f) {
                pedestalFound = true;
                break;
            }
        }
        assertTrue(pedestalFound, "pedestal element should bind the container bone '13'");
    }

    @Test
    void bindPoseUnchangedWithoutOverrides() throws Exception {
        ModelInstance instance = loadFanAsset();
        SkeletonInstance skeleton = instance.getSkeletonInstance();
        skeleton.updateTransform();
        for (Vertex vertex : instance.getModel().getVertices()) {
            Vector3f skinned = skinnedPosition(instance, vertex);
            assertPosition(skinned, vertex.getPosition().x, vertex.getPosition().y, vertex.getPosition().z);
        }
    }

    @Test
    void rotatingFanPivotsAroundFanOrigin() throws Exception {
        ModelInstance instance = loadFanAsset();
        SkeletonInstance skeleton = instance.getSkeletonInstance();
        // fan pivot [0,5.869,0] px → blocks
        Vector3f pivot = new Vector3f(0f, 5.869f / 16f, 0f);
        List<Vertex> fanVertices = verticesBoundTo(instance, "fan");
        List<Vertex> setVertices = verticesBoundTo(instance, "set");
        assertTrue(skeleton.transform("fan", new Transform().rotate(0f, 90f, 0f, true)));
        skeleton.updateTransform();
        Map<Vertex, Vertex> skinned = skeleton.getVertexTransforms(instance.getModel(), BDEF_BRIDGE);
        for (Vertex vertex : fanVertices) {
            Vector3f v = vertex.getPosition();
            Vector3f d = new Vector3f(v).sub(pivot);
            // rotateY(90°): (x, y, z) → (z, y, −x), then translate back to the pivot
            Vector3f expected = new Vector3f(d.z, d.y, -d.x).add(pivot);
            assertPosition(skinned.get(vertex).getPosition(), expected.x, expected.y, expected.z);
        }
        // Sibling subtree (set) must NOT move when only the fan bone rotates.
        for (Vertex vertex : setVertices) {
            assertPosition(skinned.get(vertex).getPosition(),
                    vertex.getPosition().x, vertex.getPosition().y, vertex.getPosition().z);
        }
    }

    @Test
    void rotatingCoverCarriesTheNestedFan() throws Exception {
        ModelInstance instance = loadFanAsset();
        SkeletonInstance skeleton = instance.getSkeletonInstance();
        // cover pivot [−0.00205,10.43221,−0.00027] px
        Vector3f pivot = new Vector3f(-0.00205f / 16f, 10.43221f / 16f, -0.00027f / 16f);
        assertTrue(skeleton.transform("cover", new Transform().rotate(0f, 0f, 30f, true)));
        skeleton.updateTransform();
        Map<Vertex, Vertex> skinned = skeleton.getVertexTransforms(instance.getModel(), BDEF_BRIDGE);
        // Both the cover's own elements AND the nested fan subtree rotate around the cover pivot.
        for (Vertex vertex : verticesBoundTo(instance, "fan")) {
            Vector3f d = new Vector3f(vertex.getPosition()).sub(pivot);
            Vector3f expected = new Vector3f(d.x, d.y, d.z); // placeholder; recomputed below
            // rotateZ(30°): x' = x·cos30 − y·sin30, y' = x·sin30 + y·cos30
            float cos = (float) Math.cos(Math.toRadians(30));
            float sin = (float) Math.sin(Math.toRadians(30));
            expected = new Vector3f(d.x * cos - d.y * sin, d.x * sin + d.y * cos, d.z).add(pivot);
            assertPosition(skinned.get(vertex).getPosition(), expected.x, expected.y, expected.z);
        }
        // The pedestal ("13"-bound) is a sibling of the group subtree — untouched.
        for (Vertex vertex : verticesBoundTo(instance, "13")) {
            assertPosition(skinned.get(vertex).getPosition(),
                    vertex.getPosition().x, vertex.getPosition().y, vertex.getPosition().z);
        }
    }

    @Test
    void rotatingGroupMovesSubtreeButNotPedestal() throws Exception {
        ModelInstance instance = loadFanAsset();
        SkeletonInstance skeleton = instance.getSkeletonInstance();
        // group pivot [0,9,0] px
        Vector3f pivot = new Vector3f(0f, 9f / 16f, 0f);
        assertTrue(skeleton.transform("group", new Transform().rotate(0f, 45f, 0f, true)));
        skeleton.updateTransform();
        Map<Vertex, Vertex> skinned = skeleton.getVertexTransforms(instance.getModel(), BDEF_BRIDGE);
        float cos = (float) Math.cos(Math.toRadians(45));
        float sin = (float) Math.sin(Math.toRadians(45));
        for (Vertex vertex : verticesBoundTo(instance, "set")) {
            Vector3f d = new Vector3f(vertex.getPosition()).sub(pivot);
            Vector3f expected = new Vector3f(d.x * cos + d.z * sin, d.y, -d.x * sin + d.z * cos).add(pivot);
            assertPosition(skinned.get(vertex).getPosition(), expected.x, expected.y, expected.z);
        }
        for (Vertex vertex : verticesBoundTo(instance, "13")) {
            assertPosition(skinned.get(vertex).getPosition(),
                    vertex.getPosition().x, vertex.getPosition().y, vertex.getPosition().z);
        }
    }

    @Test
    void unnamedGroupFlattensIntoNearestNamedAncestor() throws Exception {
        BbModelDefinition definition = BbModelDefinition.parse("""
                {
                  "resolution": {"width": 16, "height": 16},
                  "elements": [
                    {"name": "cube", "type": "cube", "uuid": "element1", "origin": [0,0,0],
                     "from": [-1,-1,-1], "to": [1,1,1],
                     "faces": {"north": {"uv": [0,0,1,1], "texture": 0}}},
                    {"name": "cube2", "type": "cube", "uuid": "element2", "origin": [36,36,36],
                     "from": [32,32,32], "to": [40,40,40],
                     "faces": {"north": {"uv": [0,0,1,1], "texture": 0}}}
                  ],
                  "groups": [
                    {"name": "a", "uuid": "gA", "origin": [0,0,0]},
                    {"name": "", "uuid": "gB", "origin": [0,0,0]}
                  ],
                  "outliner": [
                    {"uuid": "gA", "children": [
                      {"uuid": "gB", "children": ["element1"]}
                    ]},
                    "element2"
                  ],
                  "textures": [{"name": "t", "source": "", "width": 16, "height": 16}]
                }
                """);
        Texture texture = new Texture("t", 16f, 16f, null);
        Material material = new Material(new Texture[]{texture}, null);
        Model model = KsgBbModelLoader.buildSkeletonAndGeometry(definition, Map.of(0, material), new MaterialSet(texture, material));
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        assertTrue(bone(instance, "a") != null);
        // Element inside the unnamed group binds "a" (nearest named ancestor); the top-level element binds root.
        boolean innerBoundToA = false;
        boolean outerBoundToRoot = false;
        for (Vertex vertex : model.getVertices()) {
            String boneName = vertex.getBinding().getWeights()[0].getFirst().getName();
            if (boneName.equals("a") && vertex.getPosition().lengthSquared() < 0.1f) {
                innerBoundToA = true;
            }
            if (boneName.equals("root") && vertex.getPosition().x > 1f) {
                outerBoundToRoot = true;
            }
        }
        assertTrue(innerBoundToA, "element in unnamed group should bind the nearest named ancestor 'a'");
        assertTrue(outerBoundToRoot, "top-level element should bind root");
    }

    @Test
    void missingGroupOriginFallsBackToSubtreeAverage() throws Exception {
        BbModelDefinition definition = BbModelDefinition.parse("""
                {
                  "resolution": {"width": 16, "height": 16},
                  "elements": [
                    {"name": "c1", "type": "cube", "uuid": "e1", "origin": [0,8,0],
                     "from": [-1,-1,-1], "to": [1,1,1],
                     "faces": {"north": {"uv": [0,0,1,1], "texture": 0}}},
                    {"name": "c2", "type": "cube", "uuid": "e2", "origin": [0,12,0],
                     "from": [-1,-1,-1], "to": [1,1,1],
                     "faces": {"north": {"uv": [0,0,1,1], "texture": 0}}},
                    {"name": "c3", "type": "cube", "uuid": "e3", "origin": [0,16,0],
                     "from": [-1,-1,-1], "to": [1,1,1],
                     "faces": {"north": {"uv": [0,0,1,1], "texture": 0}}}
                  ],
                  "groups": [
                    {"name": "g", "uuid": "gA"}
                  ],
                  "outliner": [
                    {"uuid": "gA", "children": ["e1", "e2", "e3"]}
                  ],
                  "textures": [{"name": "t", "source": "", "width": 16, "height": 16}]
                }
                """);
        Texture texture = new Texture("t", 16f, 16f, null);
        Material material = new Material(new Texture[]{texture}, null);
        Model model = KsgBbModelLoader.buildSkeletonAndGeometry(definition, Map.of(0, material), new MaterialSet(texture, material));
        Bone g = model.getSkeleton().getBoneMap().get("g");
        assertNotNull(g);
        // Average of [0,8,0],[0,12,0],[0,16,0] = [0,12,0] px → [0,0.75,0] blocks
        assertPosition(g.getTransform(), 0f, 12f / 16f, 0f);
    }

    private static void assertPosition(Transform transform, float x, float y, float z) {
        assertPosition(transform.getPosition(), x, y, z);
    }

    private static void assertPosition(Vector3f actual, float x, float y, float z) {
        assertEquals(x, actual.x, EPS, "x");
        assertEquals(y, actual.y, EPS, "y");
        assertEquals(z, actual.z, EPS, "z");
    }
}
