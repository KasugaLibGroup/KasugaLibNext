package lib.kasuga.rendering.models.uml.typo.gltf;

import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollConfig;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GltfLoaderTest {
    @TempDir Path temporary;

    @Test
    void parsesConvertsAndAnimatesASkinnedGltf() throws Exception {
        Path file = minimalSkinnedGltf();
        GltfAsset asset = GltfLoader.load(file, Set.of("move"));

        assertEquals(1, asset.primitives().size());
        assertEquals(1, asset.skins().size());
        assertNotNull(asset.animation("move"));
        assertTrue(asset.primitives().getFirst().skinned());

        Model model = GltfModelConverter.convert(asset);
        assertEquals(3, model.getVertices().length);
        assertEquals(1, model.getMeshes().length);
        assertEquals(2, model.getBones().length);

        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        GltfAnimationPoseDriver driver = new GltfAnimationPoseDriver(instance);
        instance.setPoseDriver(driver);
        assertTrue(driver.play("move", false));
        instance.animate(1f);
        instance.sample(1f);
        instance.updateImmediate();

        var joint = ((GltfModelData)model.getModelData()).boneByNode().get(1);
        assertEquals(1f, instance.getSkeletonInstance().getAbsoluteTransforms()
                .get(joint).getPosition().x, 1e-5f);
    }

    @Test
    void canonicalizesOutOfRangeDuplicateAndInvalidGltfWeights() throws Exception {
        Path file = minimalSkinnedGltf(new float[]{
                2f, 3f, Float.NaN, -1f,
                Float.NaN, -2f, 0f, Float.NEGATIVE_INFINITY,
                0.25f, 0.75f, 0f, 0f
        });

        Model model = GltfModelConverter.convert(GltfLoader.load(file));
        var joint = ((GltfModelData) model.getModelData()).boneByNode().get(1);
        var meshNode = ((GltfModelData) model.getModelData()).boneByNode().get(0);

        var merged = model.getVertices()[0].getBinding().getWeights();
        assertEquals(1, merged.length);
        assertEquals(joint, merged[0].getFirst());
        assertEquals(1f, merged[0].getSecond(), 1e-6f);

        var fallback = model.getVertices()[1].getBinding().getWeights();
        assertEquals(1, fallback.length);
        assertEquals(meshNode, fallback[0].getFirst());
        assertEquals(1f, fallback[0].getSecond(), 1e-6f);
    }

    @Test
    void parsesAndConvertsOptionalMaribelAndRenkoFixtures() throws Exception {
        for (String name : new String[]{"maribel", "renko"}) {
            GltfAsset asset;
            try (var source = optionalGlbFixture(name)) {
                asset = GltfLoader.load(source);
            }
            assertFalse(asset.primitives().isEmpty(), name + " must contain mesh primitives");
            assertFalse(asset.skins().isEmpty(), name + " must contain a skin");
            Model model = GltfModelConverter.convert(asset);
            assertTrue(model.getMeshes().length > 0);
            assertTrue(model.getBones().length > 1);
        }
    }

    @Test
    @Tag("box3d")
    void bundledMaribelAndRenkoManifestsCreateProfiledRagdolls() throws Exception {
        for (String name : new String[]{"maribel", "renko"}) {
            String configPath = "/assets/kasuga_lib/ragdolls/" + name + ".json";
            try (var modelStream = optionalGlbFixture(name);
                 var configStream = GltfLoaderTest.class.getResourceAsStream(configPath)) {
                assertNotNull(configStream, configPath);
                GltfAsset asset = GltfLoader.loadAllAnimations(modelStream);
                float modelScale = name.equals("renko") ? 10f : 1.3f;
                Model model = GltfModelConverter.convert(asset, new org.joml.Vector3f(modelScale),
                        (index, texture) -> new lib.kasuga.rendering.models.uml.structure.material.Texture(
                                texture.name(), texture.image().getWidth(), texture.image().getHeight(),
                                new GltfModelData.GltfTextureData(texture.name(), texture.image())));
                MinecraftRagdollConfig config = MinecraftRagdollConfig.fromJson(JsonParser.parseReader(
                        new java.io.InputStreamReader(configStream)).getAsJsonObject());
                ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
                Map<lib.kasuga.rendering.models.uml.structure.skeleton.Bone, org.joml.Vector3f> bindPositions
                        = new IdentityHashMap<>();
                Map<lib.kasuga.rendering.models.uml.structure.skeleton.Bone, org.joml.Matrix4f> bindMatrices
                        = new IdentityHashMap<>();
                instance.getSkeletonInstance().getAbsoluteTransforms().forEach((bone, transform) ->
                {
                    bindPositions.put(bone, transform.getPosition());
                    bindMatrices.put(bone, new org.joml.Matrix4f(transform.transform()));
                });
                var ragdoll = instance.enablePhysics(config.profile());
                try {
                    ragdoll.setSubstepCount(config.simulation().substeps());
                    assertEquals(config.profile().bodies().size(), ragdoll.bodies().size());
                    assertEquals(ragdoll.bodies().size() - 1, ragdoll.joints().size());
                    assertTrue(ragdoll.bodies().stream().allMatch(body -> body.bone() != null));
                    assertTrue(ragdoll.bodies().stream().allMatch(body -> body.source().linearDamping() == 0f
                                    && body.source().angularDamping() == 0f),
                            name + " profile bodies must preserve gravity-driven free fall");
                    Map<lib.kasuga.rendering.models.uml.structure.skeleton.Bone, lib.kasuga.rendering.models.uml.structure.skeleton.Bone> configuredParents = new java.util.HashMap<>();
                    config.profile().bodies().forEach(body ->
                            configuredParents.put(((GltfModelData) model.getModelData()).boneByNode().get(body.rigidBodyIndex()), ((GltfModelData) model.getModelData()).boneByNode().get(body.parentRigidBodyIndex())));
                    for (var joint : ragdoll.joints()) {
                        var child = ((lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll.Body)
                                joint.bodyB()).bone();
                        var parent = ((lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll.Body)
                                joint.bodyA()).bone();
                        assertEquals(configuredParents.get(child), parent,
                                name + " must use the configured humanoid parent for body " + child);
                    }
                    ragdoll.setGravity(new org.joml.Vector3f());
                    ragdoll.step(1f / 120f);
                    assertTrue(ragdoll.bodies().stream().allMatch(body -> body.position().isFinite()));
                    for (var body : ragdoll.bodies()) {
                        assertEquals(0f, bindPositions.get(body.bone()).distance(
                                        instance.getSkeletonInstance().getAbsoluteTransforms()
                                                .get(body.bone()).getPosition()),
                                1.0e-3f, name + " must preserve its bind pose on a stationary first step: "
                                        + body.bone().getName());
                    }

                    // Moving the head exercises the facial/eye/hair joints which do not
                    // own rigid bodies. glTF requires those joints to inherit the exact
                    // affine delta of their nearest physical ancestor; a TR-only
                    // approximation creates the characteristic torn face and open seams.
                    var head = ragdoll.bodies().stream()
                            .filter(body -> body.bone()
                                    == ((GltfModelData) model.getModelData()).boneByNode().get(name.equals("maribel") ? 86 : 35))
                            .findFirst().orElseThrow();
                    assertTrue(ragdoll.applyAngularImpulse(head, new org.joml.Vector3f(0.2f, 0.3f, -0.15f)));
                    for (int step = 0; step < 12; step++) ragdoll.step(1f / 120f);

                    Set<Integer> physicalNodes = new HashSet<>();
                    config.profile().bodies().forEach(body -> physicalNodes.add(body.rigidBodyIndex()));
                    GltfModelData modelData = (GltfModelData) model.getModelData();
                    for (GltfAsset.Skin skin : asset.skins()) {
                        for (int node : skin.jointNodeIndices()) {
                            if (physicalNodes.contains(node)) continue;
                            int ancestor = asset.nodes().parents()[node];
                            while (ancestor >= 0 && !physicalNodes.contains(ancestor)) {
                                ancestor = asset.nodes().parents()[ancestor];
                            }
                            if (ancestor < 0) continue;
                            var bone = modelData.boneByNode().get(node);
                            var ancestorBone = modelData.boneByNode().get(ancestor);
                            var expected = new org.joml.Matrix4f(instance.getSkeletonInstance()
                                    .getAbsoluteTransforms().get(ancestorBone).transform())
                                    .mul(new org.joml.Matrix4f(bindMatrices.get(ancestorBone)).invert())
                                    .mul(bindMatrices.get(bone));
                            assertMatrixClose(expected, instance.getSkeletonInstance()
                                            .getAbsoluteTransforms().get(bone).transform(),
                                    2.0e-3f, name + " passive skin joint " + bone.getName());
                        }
                    }

                    // Exercise the scale-sensitive path seen in the client: a
                    // complete scaled humanoid settling against static ground.
                    // Joint anchors must remain coincident, and physics must
                    // not introduce another copy of the manifest's root scale.
                    ragdoll.reset();
                    float ground = ragdoll.bodies().stream()
                            .map(GltfLoaderTest::lowestCapsulePoint)
                            .min(Float::compare).orElse(0f);
                    ragdoll.addGroundPlane(ground, 0.8f, 0f);
                    ragdoll.setGravity(new org.joml.Vector3f(0f, -9.80665f, 0f));
                    for (int step = 0; step < 600; step++) ragdoll.step(1f / 120f);
                    float maximumAnchorError = ragdoll.joints().stream()
                            .map(joint -> joint.relativePosition().length())
                            .max(Float::compare).orElse(0f);
                    assertTrue(maximumAnchorError <= 0.02f,
                            name + " scaled glTF joint anchor error=" + maximumAnchorError);
                    for (var body : ragdoll.bodies()) {
                        var expectedScale = bindMatrices.get(body.bone()).getScale(new org.joml.Vector3f());
                        var actualScale = instance.getSkeletonInstance().getAbsoluteTransforms()
                                .get(body.bone()).transform().getScale(new org.joml.Vector3f());
                        assertEquals(0f, expectedScale.distance(actualScale), 2.0e-3f,
                                name + " must preserve one copy of root scale on " + body.bone().getName()
                                        + ": expected=" + expectedScale + " actual=" + actualScale);
                    }

                    // Reproduce the client-visible impact case instead of
                    // starting already in contact with the plane. A complete
                    // humanoid is dropped from height, then every body is
                    // sampled through a late resting window so constraint or
                    // contact feedback cannot hide behind one final frame.
                    ragdoll.reset();
                    for (var body : ragdoll.bodies()) {
                        body.teleport(body.position().add(0f, 8f, 0f), body.rotation());
                    }
                    float maximumRestingLinearSpeed = 0f;
                    float maximumRestingAngularSpeed = 0f;
                    String maximumRestingLinearBody = "";
                    String maximumRestingAngularBody = "";
                    lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll.Body maximumRestingBody = null;
                    for (int step = 0; step < 1440; step++) {
                        ragdoll.step(1f / 120f);
                        if (step < 1200) continue;
                        for (var body : ragdoll.bodies()) {
                            float linearSpeed = body.linearVelocity().length();
                            if (linearSpeed > maximumRestingLinearSpeed) {
                                maximumRestingLinearSpeed = linearSpeed;
                                maximumRestingLinearBody = body.bone().getName();
                                maximumRestingBody = body;
                            }
                            float angularSpeed = body.angularVelocity().length();
                            if (angularSpeed > maximumRestingAngularSpeed) {
                                maximumRestingAngularSpeed = angularSpeed;
                                maximumRestingAngularBody = body.bone().getName();
                            }
                        }
                    }
                    assertTrue(maximumRestingLinearSpeed < 0.05f,
                            name + " high-drop resting linear jitter=" + maximumRestingLinearSpeed
                                    + " body=" + maximumRestingLinearBody
                                    + " angular=" + maximumRestingAngularSpeed
                                    + " angularBody=" + maximumRestingAngularBody
                                    + " shape=" + (maximumRestingBody == null ? "n/a" : maximumRestingBody.shapeSize())
                                    + " mass=" + (maximumRestingBody == null ? "n/a" : maximumRestingBody.source().mass()));
                    assertTrue(maximumRestingAngularSpeed < 0.1f,
                            name + " high-drop resting angular jitter=" + maximumRestingAngularSpeed
                                    + " body=" + maximumRestingAngularBody);
                    var worstAngularJoint = ragdoll.joints().stream()
                            .filter(joint -> joint.rotationLimiter() != null
                                    && joint.rotationLimiter().stiffness() >= 0.8f)
                            .max(java.util.Comparator.comparingDouble(
                                    lib.kasuga.rendering.models.uml.dynamic.physics.core.BallJoint::angularLimitViolation))
                            .orElseThrow();
                    float maximumAngularViolation = worstAngularJoint.angularLimitViolation();
                    assertTrue(maximumAngularViolation <= Math.toRadians(3.0),
                            name + " angular joint limit drift=" + Math.toDegrees(maximumAngularViolation)
                                    + " parent=" + ((lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll.Body)
                                    worstAngularJoint.bodyA()).bone().getName()
                                    + " child=" + ((lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll.Body)
                                    worstAngularJoint.bodyB()).bone().getName());
                } finally {
                    instance.close();
                }
            }
        }
    }

    /** Optional large GLB fixtures are resolved only from this project's test runtime classpath. */
    private static java.io.InputStream optionalGlbFixture(String name) {
        String resource = "/assets/kasuga_lib/models/gltf/" + name + ".glb";
        var stream = GltfLoaderTest.class.getResourceAsStream(resource);
        assumeTrue(stream != null, resource + " not present; skipping optional fixture test");
        return stream;
    }

    private static float lowestCapsulePoint(
            lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll.Body body) {
        var size = body.shapeSize();
        var halfAxis = body.rotation().transform(
                new org.joml.Vector3f(0f, 0.5f * size.y, 0f));
        return body.position().y - Math.abs(halfAxis.y) - size.x;
    }

    @Test
    void bundledSkinsAgreeWithTheirNodeBindTransforms() throws Exception {
        for (String name : new String[]{"maribel", "renko"}) {
            try (var modelStream = optionalGlbFixture(name)) {
                GltfAsset asset = GltfLoader.load(modelStream);
                for (GltfAsset.Primitive primitive : asset.primitives()) {
                    if (!primitive.skinned()) continue;
                    GltfAsset.Skin skin = asset.skins().get(primitive.skinIndex());
                    for (int joint = 0; joint < skin.jointNodeIndices().length; joint++) {
                        int node = skin.jointNodeIndices()[joint];
                        var skinBind = new org.joml.Matrix4f(asset.nodes().bindWorlds()[node])
                                .mul(skin.inverseBindMatrices()[joint]);
                        assertMatrixClose(primitive.nodeWorld(), skinBind, 1.0e-3f,
                                name + " joint " + skin.jointNames()[joint]);
                    }
                }
            }
        }
    }

    private static void assertMatrixClose(org.joml.Matrix4f expected, org.joml.Matrix4f actual,
                                          float tolerance, String message) {
        float[] left = expected.get(new float[16]);
        float[] right = actual.get(new float[16]);
        for (int i = 0; i < left.length; i++) {
            assertEquals(left[i], right[i], tolerance, message + " matrix element " + i);
        }
    }

    private Path minimalSkinnedGltf() throws Exception {
        return minimalSkinnedGltf(new float[]{
                1f, 0f, 0f, 0f,
                1f, 0f, 0f, 0f,
                1f, 0f, 0f, 0f
        });
    }

    private Path minimalSkinnedGltf(float[] weights) throws Exception {
        assertEquals(12, weights.length);
        ByteBuffer data = ByteBuffer.allocate(260).order(ByteOrder.LITTLE_ENDIAN);
        put(data, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f); // positions
        put(data, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f); // normals
        put(data, 0f, 0f, 1f, 0f, 0f, 1f);             // UVs
        data.put(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        put(data, weights);                                      // weights
        data.putShort((short)0).putShort((short)1).putShort((short)2).putShort((short)0);
        put(data, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f);       // inverse bind
        put(data, 0f, 1f);                              // key times
        put(data, 0f, 0f, 0f, 1f, 0f, 0f);             // translations
        String encoded = Base64.getEncoder().encodeToString(data.array());
        String json = """
                {
                  "asset":{"version":"2.0"},
                  "scene":0,
                  "scenes":[{"nodes":[0]}],
                  "nodes":[{"name":"mesh","mesh":0,"skin":0,"children":[1]}, {"name":"joint"}],
                  "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2,"JOINTS_0":3,"WEIGHTS_0":4},"indices":5}]}],
                  "skins":[{"joints":[1],"inverseBindMatrices":6}],
                  "animations":[{"name":"move","samplers":[{"input":7,"output":8,"interpolation":"LINEAR"}],"channels":[{"sampler":0,"target":{"node":1,"path":"translation"}}]}],
                  "buffers":[{"byteLength":260,"uri":"data:application/octet-stream;base64,%s"}],
                  "bufferViews":[
                    {"buffer":0,"byteOffset":0,"byteLength":36},
                    {"buffer":0,"byteOffset":36,"byteLength":36},
                    {"buffer":0,"byteOffset":72,"byteLength":24},
                    {"buffer":0,"byteOffset":96,"byteLength":12},
                    {"buffer":0,"byteOffset":108,"byteLength":48},
                    {"buffer":0,"byteOffset":156,"byteLength":6},
                    {"buffer":0,"byteOffset":164,"byteLength":64},
                    {"buffer":0,"byteOffset":228,"byteLength":8},
                    {"buffer":0,"byteOffset":236,"byteLength":24}
                  ],
                  "accessors":[
                    {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                    {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
                    {"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
                    {"bufferView":3,"componentType":5121,"count":3,"type":"VEC4"},
                    {"bufferView":4,"componentType":5126,"count":3,"type":"VEC4"},
                    {"bufferView":5,"componentType":5123,"count":3,"type":"SCALAR"},
                    {"bufferView":6,"componentType":5126,"count":1,"type":"MAT4"},
                    {"bufferView":7,"componentType":5126,"count":2,"type":"SCALAR","min":[0],"max":[1]},
                    {"bufferView":8,"componentType":5126,"count":2,"type":"VEC3"}
                  ]
                }
                """.formatted(encoded);
        Path file = temporary.resolve("rig.gltf");
        Files.writeString(file, json);
        return file;
    }

    private static void put(ByteBuffer target, float... values) {
        for (float value : values) target.putFloat(value);
    }
}
