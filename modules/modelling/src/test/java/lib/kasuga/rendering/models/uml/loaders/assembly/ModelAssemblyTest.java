package lib.kasuga.rendering.models.uml.loaders.assembly;

import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.dynamic.morph.holder.GroupMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.holder.MorphHolder;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.*;
import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsBuilder;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.*;
import lib.kasuga.rendering.models.uml.structure.material.*;
import lib.kasuga.rendering.models.uml.structure.skeleton.*;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.*;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class ModelAssemblyTest {
    private static final Texture TEXTURE = new Texture("cloth", 16, 16, null);

    @Test
    void hundredGarmentsReuseOneAssemblyAndOneSkeletonWithoutMutatingSources() {
        Model body = model(1, false, MeshMode.TRIANGLES), cloth = model(1, false, MeshMode.TRIANGLES);
        ModelAssemblyBuilder builder = new ModelAssemblyBuilder("body", body, 1);
        for (int i = 0; i < 100; i++) builder.part("cloth-" + i, cloth, 3);
        ModelAssemblyCache cache = new ModelAssemblyCache();
        ModelAssembly outfit = builder.assemble(cache);
        assertSame(outfit, cache.getOrAssemble(builder.build()));
        assertEquals(1, cache.stats().builds()); assertEquals(1, cache.stats().hits());
        assertEquals(101, outfit.model().getMeshes().length);
        assertEquals(303, outfit.model().getVertices().length);
        assertEquals(2, outfit.model().getBones().length);
        assertEquals(1, outfit.model().getMaterialSet().getTextures().length);
        assertEquals(101, outfit.model().getMaterialSet().getMaterials().length);
        var instance = instance(outfit);
        instance.getSkeletonInstance().rotate("arm", new Quaternionf().rotateZ((float) Math.PI / 2));
        instance.updateImmediate();
        Vertex vertex = outfit.part("cloth-99").vertex(cloth.getVertices()[0]);
        assertEquals(0, skinned(instance, vertex).distance(new Vector3f(0, 2, 0)), 1e-5);
        assertSame(outfit.part("body").bone(body.getBones()[1]), vertex.getBinding().getWeights()[0].getFirst());
        assertEquals(-1, cloth.getVertices()[0].getIndex());
        assertEquals(0, cloth.getBones()[0].getIndex());
        assertEquals(new Vector3f(1, 1, 0), cloth.getVertices()[0].getPosition());
        assertEquals(0, cloth.getBones()[1].getTransform().getRotation().angle(), 1e-5);
        ModelInstance other = instance(outfit);
        assertEquals(0, skinned(other, vertex).distance(new Vector3f(1, 1, 0)), 1e-5);
    }

    @Test
    void hiddenRegionsRemoveFacesAndUnusedVerticesButKeepSharedCornerDataIndependent() {
        Model body = model(2, false, MeshMode.TRIANGLES), cloth = model(1, false, MeshMode.QUADS);
        Vertex hidden = body.getVertices()[0];
        body.getMorph().addMorph("hidden", new VertexPosMorph<>(hidden, "hidden", new Vector3f(5, 5, 5)));
        ModelAssembly outfit = new ModelAssemblyBuilder("body", body, 0,
                part -> part.region("covered-torso", 0).hideRegions("covered-torso"))
                .part("shirt", cloth, 0).assemble();
        assertEquals(2, outfit.model().getMeshes().length);
        assertEquals(7, outfit.model().getVertices().length);
        assertEquals(MeshMode.MIXED, outfit.model().getMeshMode());
        assertNull(outfit.part("body").vertex(hidden));
        assertNull(outfit.part("body").mesh(body.getMeshes()[0]));
        assertTrue(outfit.model().getMorph().getMorphs("hidden").isEmpty());
        Vertex copy = outfit.part("shirt").vertex(cloth.getVertices()[0]);
        Mesh face = outfit.part("shirt").mesh(cloth.getMeshes()[0]);
        Material material = outfit.part("shirt").material(cloth.getMaterialSet().getMaterials()[0]);
        copy.getUV(face, material).set(0.8f, 0.2f);
        copy.getNormal(face).set(1, 0, 0);
        assertEquals(new Vector2f(0, 0), cloth.getVertices()[0].getUV(cloth.getMeshes()[0], cloth.getMaterialSet().getMaterials()[0]));
        assertEquals(new Vector3f(0, 0, 1), cloth.getVertices()[0].getNormal(cloth.getMeshes()[0]));
    }

    @Test
    void extraBonesAnchorsAndAliasedBoneNamesPreserveBindSpace() {
        Model body = model(1, false, MeshMode.TRIANGLES), cloth = model(1, true, MeshMode.TRIANGLES);
        ModelAssembly outfit = new ModelAssemblyBuilder("body", body, 0).part("skirt", cloth, 0).assemble();
        assertEquals(3, outfit.model().getBones().length);
        Bone hem = outfit.part("skirt").bone(cloth.getBones()[2]);
        assertEquals("skirt/hem", hem.getName());
        assertSame(outfit.model().getSkeleton().getBoneMap().get("arm"), hem.getParent());
        assertEquals(cloth.getSkeleton().getBindingAbsolute(cloth.getBones()[2]).getPosition(),
                outfit.model().getSkeleton().getBindingAbsolute(hem).getPosition());
        assertNotNull(outfit.model().getSkeleton().getAnchor("skirt/edge"));
        assertSame(hem, outfit.model().getSkeleton().getAnchor("skirt/edge").getBinding().getWeights()[0].getFirst());

        Model isolated = new ModelAssemblyBuilder("body", body, 0)
                .part("separate", cloth, 0, part -> part.matchBodyBones(false).mapBone("root", "root")).assemble().model();
        assertNotNull(isolated.getSkeleton().getBoneMap().get("separate/arm"));
        assertEquals(4, isolated.getBones().length);
    }

    @Test
    void animationMorphGroupsFlipAndMaterialFramesTargetOnlyTheirOwnPart() {
        Model body = model(1, false, MeshMode.TRIANGLES), cloth = model(1, true, MeshMode.TRIANGLES);
        Vertex vertex = cloth.getVertices()[0];
        VertexPosMorph<Object> lift = new VertexPosMorph<>(vertex, "lift", new Vector3f(vertex.getPosition()).add(0, 2, 0));
        cloth.getMorph().addMorph("lift", lift);
        cloth.getMorph().addMorph("inverse", new FlipMorph<>("inverse", lift));
        GroupMorph<Object> group = new GroupMorph<>("fit");
        group.addHolder(new MorphHolder<>("lift", lift, List.of(vertex)), 0.5f);
        cloth.getMorph().addGroup("fit", group);
        cloth.getMorph().addMorph("red", new MaterialColorMorph<>(cloth.getMaterialSet().getMaterials()[0], "red", new Vector4f(1, 0, 0, 1)));
        cloth.getAnimations().register("fit", new AnimationSampler<Integer>() {
            public float duration(Integer ignored) { return 1; }
            public Pose sample(Integer ignored, float seconds) {
                return new Pose.Builder().morph("fit", 1, 1).bone("hem", new Transform().translate(0.2f, 0, 0),
                        lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode.REPLACE).frame(0, 1).build();
            }
        }, 0);
        var outfit = new ModelAssemblyBuilder("body", body, 0).part("shirt", cloth, 0).part("coat", cloth, 0).assemble();
        ModelInstance instance = instance(outfit);
        assertTrue(instance.getPosing().play("shirt/fit", false));
        instance.sample(1); instance.getMorph().update();
        Vertex shirt = outfit.part("shirt").vertex(vertex), coat = outfit.part("coat").vertex(vertex);
        assertEquals(1, instance.getMorph().getVertexResult(shirt).getPosition().y, 1e-5);
        assertNull(instance.getMorph().getVertexResult(coat));
        assertEquals(1, instance.getMaterialInstance().getCurrentMatFrame(outfit.part("shirt").material(cloth.getMaterialSet().getMaterials()[0])));
        assertEquals(0, instance.getMaterialInstance().getCurrentMatFrame(outfit.part("coat").material(cloth.getMaterialSet().getMaterials()[0])));
        assertNotNull(instance.getSkeletonInstance().getTransforms().get(outfit.part("shirt").bone(cloth.getBones()[2])));
        assertNull(instance.getSkeletonInstance().getTransforms().get(outfit.part("coat").bone(cloth.getBones()[2])));
        instance.getMorph().activateMorph(outfit.part("coat").morphId("inverse"), 0.25f);
        instance.getMorph().update();
        assertEquals(1.5f, instance.getMorph().getVertexResult(coat).getPosition().y, 1e-5);
        assertEquals(3, cloth.getMorph().morphTypeCount());
        assertEquals(0, lift.getMorphTypeIndex()); // Source ordinals remain unchanged.
    }

    @Test
    void samplerAdapterPreservesFormulaContextAndClothingIkNames() {
        var outfit = new ModelAssemblyBuilder("body", model(1, false, MeshMode.TRIANGLES), 0)
                .part("shirt", model(1, true, MeshMode.TRIANGLES), 0).assemble();
        Namespace namespace = new Namespace(Code.ROOT_NAMESPACE);
        AnimationSampler<Integer> source = new AnimationSampler<>() {
            public float duration(Integer data) { return 1; }
            public Pose sample(Integer data, float time) { return Pose.empty(); }
            public Pose sample(Integer data, float time, Namespace context) {
                assertSame(namespace, context);
                return new Pose.Builder().ikEnabled("hem-ik", false).bone("hem", new Transform(),
                        lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode.REPLACE).build();
            }
        };
        Pose pose = outfit.part("shirt").sampler(source).sample(0, 0, namespace);
        assertTrue(pose.bones().containsKey("shirt/hem"));
        assertEquals(Map.of("shirt/hem-ik", false), pose.ikEnabled());
    }

    @Test
    void cacheKeyIncludesRevisionsMappingsMasksAndTolerancesAndInvalidatesByResource() {
        Model body = model(2, false, MeshMode.TRIANGLES), cloth = model(1, false, MeshMode.TRIANGLES);
        ModelAssemblyCache cache = new ModelAssemblyCache();
        var first = new ModelAssemblyBuilder("body", body, 0).part("shirt", cloth, 0).assemble(cache);
        assertSame(first, new ModelAssemblyBuilder("body", body, 0).part("shirt", cloth, 0).assemble(cache));
        assertNotSame(first, new ModelAssemblyBuilder("body", body, 1).part("shirt", cloth, 0).assemble(cache));
        assertNotSame(first, new ModelAssemblyBuilder("body", body, 0).part("shirt", cloth, 1).assemble(cache));
        assertNotSame(first, new ModelAssemblyBuilder("body", body, 0).part("shirt", cloth, 0, part -> part.mapBone("arm", "arm")).assemble(cache));
        assertNotSame(first, new ModelAssemblyBuilder("body", body, 0, part -> part.hideMeshes(0)).part("shirt", cloth, 0).assemble(cache));
        assertNotSame(first, new ModelAssemblyBuilder("body", body, 0).part("shirt", cloth, 0).bindTolerance(1e-3f).assemble(cache));
        assertEquals(6, cache.invalidate(cloth)); assertEquals(0, cache.stats().entries());
        new ModelAssemblyBuilder("body", body, 0).part("shirt", cloth, 0).assemble(cache);
        assertEquals(1, cache.invalidate("shirt"));
        assertEquals(0, cache.invalidate("missing"));
    }

    @Test
    void boundedLruAndVertexBudgetRetainOnlyRecentAssembliesAndOldResultsStayUsable() {
        Model body = model(1, false, MeshMode.TRIANGLES);
        ModelAssemblyCache cache = new ModelAssemblyCache(2, 6);
        var a = new ModelAssemblyBuilder("body", body, 0).assemble(cache);
        var b = new ModelAssemblyBuilder("body", body, 1).assemble(cache);
        new ModelAssemblyBuilder("body", body, 0).assemble(cache); // A becomes most recent.
        new ModelAssemblyBuilder("body", body, 2).assemble(cache); // B is evicted.
        assertSame(a, new ModelAssemblyBuilder("body", body, 0).assemble(cache));
        assertNotSame(b, new ModelAssemblyBuilder("body", body, 1).assemble(cache));
        assertEquals(2, cache.stats().entries()); assertEquals(6, cache.stats().vertices());
        assertEquals(3, b.model().getVertices().length);
        cache.clear(); assertEquals(0, cache.stats().vertices());
        ModelAssemblyCache tiny = new ModelAssemblyCache(2, 2);
        var first = new ModelAssemblyBuilder("body", body, 0).assemble(tiny);
        assertNotSame(first, new ModelAssemblyBuilder("body", body, 0).assemble(tiny));
        assertEquals(0, tiny.stats().entries());
    }

    @Test
    void concurrentRequestsOnlyBuildOnce() throws Exception {
        var request = new ModelAssemblyBuilder("body", model(1, false, MeshMode.TRIANGLES), 0).build();
        var cache = new ModelAssemblyCache();
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<ModelAssembly>> jobs = new ArrayList<>();
            for (int i = 0; i < 16; i++) jobs.add(executor.submit(() -> cache.getOrAssemble(request)));
            ModelAssembly first = jobs.getFirst().get(10, TimeUnit.SECONDS);
            for (var job : jobs) assertSame(first, job.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, cache.stats().builds()); assertEquals(15, cache.stats().hits());
    }

    @Test
    void invalidMasksMappingsBindPosesAndDuplicateIdsFailBeforeCaching() {
        Model body = model(1, false, MeshMode.TRIANGLES), cloth = model(1, false, MeshMode.TRIANGLES);
        assertThrows(IllegalArgumentException.class, () -> new ModelAssemblyBuilder("body", body, 0, part -> part.hideMeshes(1)));
        assertThrows(IllegalArgumentException.class, () -> new ModelAssemblyBuilder("body", body, 0, part -> part.hideRegions("undefined")));
        assertThrows(IllegalArgumentException.class, () -> new ModelAssemblyBuilder("body", body, 0).part("body", cloth, 0).build());
        assertThrows(IllegalArgumentException.class, () -> new ModelAssemblyBuilder("body", body, 0)
                .part("cloth", cloth, 0, part -> part.mapBone("arm", "missing")).assemble());
        Model shifted = model(1, false, MeshMode.TRIANGLES);
        shifted.getSkeleton().getBindingAbsolute(shifted.getBones()[1]).translate(1, 0, 0);
        var cache = new ModelAssemblyCache();
        assertThrows(IllegalArgumentException.class, () -> new ModelAssemblyBuilder("body", body, 0).part("cloth", shifted, 0).assemble(cache));
        assertEquals(0, cache.stats().entries()); assertEquals(0, cache.stats().builds());
    }

    @Test
    void customAssemblerAndMorphRemapperAreReusableExtensionPoints() {
        Model body = model(1, false, MeshMode.TRIANGLES);
        Vertex source = body.getVertices()[0];
        var builtin = MorphRemapper.standard();
        List<String> calls = new ArrayList<>();
        body.getMorph().addMorph("custom", new VertexPosMorph<>(source, "custom", new Vector3f(3, 3, 3)));
        ModelAssembler custom = ModelAssembler.standard((morph, mapping) -> {
            calls.add(mapping.id()); return builtin.remap(morph, mapping);
        });
        var request = new ModelAssemblyBuilder("body", body, 0).build();
        var cache = new ModelAssemblyCache(4, 100, custom);
        var result = cache.getOrAssemble(request);
        assertSame(result, cache.getOrAssemble(request));
        assertEquals(List.of("body"), calls);
        assertNotSame(body.getMorph().getMorph("custom"), result.model().getMorph().getMorph("custom"));
    }

    @Test
    @Tag("box3d")
    void assembledSecondaryPhysicsConvertsUnitsRemapsJointsAndRunsNativeSimulation() {
        Model body = model(1, false, MeshMode.TRIANGLES), cloth = model(1, true, MeshMode.TRIANGLES);
        var main = rigid("arm", body.getBones()[1], new Vector3f(0, 2, 0), new Vector3f(0.4f), RigidBody.KINEMATIC);
        var duplicate = rigid("arm", cloth.getBones()[1], new Vector3f(0, 1, 0), new Vector3f(0.2f), RigidBody.KINEMATIC);
        var hem = rigid("hem", cloth.getBones()[2], new Vector3f(0, 2, 0), new Vector3f(0.1f), RigidBody.DYNAMIC);
        new SkeletonDynamicsBuilder(body.getSkeleton()).physics(new Physics(List.of(main), List.of(), new Vector3f(0.5f), false, true, 1, Set.of())).attach();
        Joint joint = new Joint("cloth-joint", "", 0, 1, new Vector3f(0, 1.5f, 0), new Vector3f(), new Vector3f(),
                new Vector3f(), new Vector3f(-0.5f), new Vector3f(0.5f), new Vector3f(), new Vector3f());
        new SkeletonDynamicsBuilder(cloth.getSkeleton()).physics(new Physics(List.of(duplicate, hem), List.of(joint),
                new Vector3f(1), false, true, 1, Set.of())).attach();
        var outfit = new ModelAssemblyBuilder("body", body, 0).part("skirt", cloth, 0).assemble();
        assertEquals(2, outfit.model().getSkeleton().getDynamics().physics().bodies().size());
        assertEquals(0, outfit.part("body").rigidBodyIndex(0));
        assertEquals(0, outfit.part("skirt").rigidBodyIndex(0));
        assertEquals(1, outfit.part("skirt").rigidBodyIndex(1));
        assertEquals(new Vector3f(1), outfit.model().getSkeleton().getDynamics().physics().unitScale());
        try (ModelInstance instance = instance(outfit); var physics = instance.enablePhysics()) {
            assertNotNull(physics); assertEquals(2, physics.bodies().size()); assertEquals(1, physics.joints().size());
            for (int i = 0; i < 30; i++) instance.animate(1f / 60);
            assertTrue(physics.body(1).orElseThrow().position().isFinite());
            assertEquals("skirt/hem", physics.body(1).orElseThrow().source().bone().getName());
        }
    }

    private static RigidBody rigid(String name, Bone bone, Vector3f position, Vector3f size, int mode) {
        return new RigidBody(name, "", bone, 0, 0, RigidBody.SPHERE, size, position,
                new Vector3f(), mode == RigidBody.KINEMATIC ? 0 : 1, 0.1f, 0.1f, 0, 0.5f, mode);
    }

    private static ModelInstance instance(ModelAssembly assembly) {
        var model = assembly.model();
        return new ModelInstance(model, null, null, null, new MaterialSetInstance(model.getMaterialSet()), null);
    }

    private static Vector3f skinned(ModelInstance instance, Vertex vertex) {
        List<BoneContext> contexts = new ArrayList<>();
        for (Pair<Bone, Float> weight : vertex.getBinding().getWeights()) {
            Bone bone = weight.getFirst();
            var skeleton = instance.getModel().getSkeleton();
            contexts.add(new BoneContext(bone, weight.getSecond(), bone.getBoneData(), bone.getTransform(),
                    skeleton.getBindingAbsolute(bone), instance.getSkeletonInstance().getAbsoluteTransforms().get(bone),
                    skeleton.getBindingInverse(bone)));
        }
        return vertex.getBinding().getFunc().apply(vertex, (List) contexts).getPosition();
    }

    private static Model model(int faces, boolean extraBone, MeshMode mode) {
        Bone root = new Bone("root", new Transform(), null), arm = new Bone("arm", new Transform().translate(0, 1, 0), null);
        root.setChildren(new Bone[]{arm}); arm.setParent(root);
        List<Bone> bones = new ArrayList<>(List.of(root, arm));
        Anchor[] anchors = new Anchor[0];
        if (extraBone) {
            Bone hem = new Bone("hem", new Transform().translate(0, 1, 0), null);
            arm.setChildren(new Bone[]{hem}); hem.setParent(arm); bones.add(hem);
            anchors = new Anchor[]{new Anchor("edge", binding(hem), new Transform(), null)};
        }
        Material material = new Material(new Texture[]{TEXTURE}, null);
        Sprite sprite = new Sprite(TEXTURE, new Vector2f(), new Vector2f(0, 1), new Vector2f(1, 1), new Vector2f(1, 0),
                new Vector4f(1), null, null, null);
        material.addSprite(new SpriteSet(null, sprite)); material.addSprite(new SpriteSet(null, sprite));
        List<Vertex> vertices = new ArrayList<>(); List<Mesh> meshes = new ArrayList<>();
        for (int f = 0; f < faces; f++) {
            int count = mode == MeshMode.QUADS ? 4 : 3;
            Vertex[] corner = new Vertex[count];
            for (int i = 0; i < count; i++) {
                corner[i] = new Vertex(new Vector3f(i == 1 || i == 2 ? 2 : 1, i >= 2 ? 2 : 1, f), null);
                corner[i].setBinding(binding(arm)); vertices.add(corner[i]);
            }
            Mesh mesh = new Mesh(corner, new Vector3f(0, 0, 1), new Transform(), new Material[]{material}, null);
            for (int i = 0; i < count; i++) corner[i].addUV(mesh, material, new Vector2f(i / (float) count, 0));
            meshes.add(mesh);
        }
        Skeleton skeleton = new Skeleton(bones.toArray(Bone[]::new), root, anchors, null, new Transform());
        return new Model(vertices.toArray(Vertex[]::new), meshes.toArray(Mesh[]::new), skeleton.getBones(), skeleton,
                new MaterialSet(TEXTURE, material), mode, null, null);
    }

    private static BoneBinding binding(Bone bone) {
        return new BoneBinding(new Pair[]{Pair.of(bone, 1f)}, BoneBindingFunc.BDEF, null);
    }
}
