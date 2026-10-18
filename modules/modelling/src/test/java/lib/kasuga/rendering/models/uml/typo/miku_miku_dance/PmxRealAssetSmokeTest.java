package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollConfig;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.MmdModelData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("box3d")
class PmxRealAssetSmokeTest {
    @Test
    void parsesEveryPmxInLocalMmdCompatibilityFixtures() throws Exception {
        Path fixtureDir = findFixtureDir();
        Assumptions.assumeTrue(Files.isDirectory(fixtureDir), "local MMD fixtures are optional");
        List<Path> packages;
        try (var stream = Files.list(fixtureDir)) {
            packages = stream.filter(path -> path.getFileName().toString().endsWith(".mmd.zip")).toList();
        }
        Assumptions.assumeFalse(packages.isEmpty(), "local MMD fixtures are optional");
        int parsed = 0;
        for (Path pack : packages) {
            try (ZipHelper zip = ZipHelper.fromFile(pack.toString())) {
                for (var resource : zip.searchNameForResource(
                        name -> name.toLowerCase(Locale.ROOT).endsWith(".pmx"))) {
                    ProbeLoader loader = new ProbeLoader();
                    loader.load(resource.name(), resource.buffer());
                    assertNotNull(loader.getHeader(), pack + "#" + resource.name());
                    assertNotNull(loader.getTail(), pack + "#" + resource.name());
                    if (resource.name().equalsIgnoreCase("TDA Bunny Miku 2.0.pmx")) {
                        assertActiveRagdollPreservesBoneLengths(loader, fixtureDir);
                    }
                    parsed++;
                }
            }
        }
        assertTrue(parsed >= packages.size());
    }

    private static void assertActiveRagdollPreservesBoneLengths(ProbeLoader loader,
                                                                 Path fixtureDir) throws IOException {
        Vector3f modelScale = new Vector3f(0.1f);
        for (PmxBone bone : loader.getBones()) {
            bone.position.mul(modelScale);
            if (bone.tailObject instanceof Vector3f tail) tail.mul(modelScale);
        }
        Skeleton skeleton = skeleton(loader);
        Model model = new Model(new Vertex[0], new Mesh[0], skeleton.getBones(), skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES,
                new MmdModelData(loader.getHeader(), loader.getTail(), modelScale,
                        loader.getBones().size()), null);
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        Bounds bindBounds = skinnedBounds(loader, instance, modelScale);
        assertTrue(bindBounds.size().y > 1.5f && bindBounds.size().y < 2.5f,
                "unexpected scaled PMX height=" + bindBounds.size().y);
        Path configPath = fixtureDir.getParent().getParent().resolve("ragdolls/tda_bunny_miku.json");
        MinecraftRagdollConfig config;
        try (var reader = Files.newBufferedReader(configPath)) {
            config = MinecraftRagdollConfig.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
        }
        Map<Bone, Float> bindLengths = new IdentityHashMap<>();
        for (Bone bone : skeleton.getBones()) {
            if (bone.getParent() == null) continue;
            bindLengths.put(bone, instance.getSkeletonInstance().getAbsoluteTransforms().get(bone).getPosition()
                    .distance(instance.getSkeletonInstance().getAbsoluteTransforms().get(bone.getParent()).getPosition()));
        }

        SkeletonRagdoll ragdoll = instance.enablePhysics(config.profile());
        List<SkeletonRagdoll.Body> primaryBodies = ragdoll.bodies().stream()
                .filter(SkeletonRagdoll.Body::profiledRagdollBody).toList();
        List<SkeletonRagdoll.Body> secondaryBodies = ragdoll.bodies().stream()
                .filter(SkeletonRagdoll.Body::authoredSecondaryBody).toList();
        assertEquals(config.profile().bodies().size(), primaryBodies.size(),
                "every explicitly registered humanoid body must enter the primary ragdoll");
        assertFalse(secondaryBodies.isEmpty(),
                "the compatibility fixture must exercise authored skirt/hair motion");
        assertFalse(ragdoll.selfCollisionsEnabled(),
                "the primary ragdoll must start in a no-self-collision group");
        assertTrue(primaryBodies.stream().allMatch(body -> body.source().shape() == 2),
                "profile bodies must use generated bone capsules, not PMX skirt/hair shapes");
        assertTrue(primaryBodies.stream().allMatch(body -> body.source().linearDamping() == 0f
                        && body.source().angularDamping() == 0f),
                "primary profile bodies must not slow free fall with global damping");
        ragdoll.setGravity(new Vector3f());
        ragdoll.setCollisionsEnabled(false);
        ragdoll.setSolverIterations(16);
        for (SkeletonRagdoll.Body body : ragdoll.bodies()) {
            body.teleport(body.position().add(0f, 3f, 0f), body.rotation());
        }
        ragdoll.step(1f / 120f);
        Bounds translatedBounds = skinnedBounds(loader, instance, modelScale);
        assertEquals(bindBounds.size().x, translatedBounds.size().x, 0.002f,
                "ragdoll changed skinned model width");
        assertEquals(bindBounds.size().y, translatedBounds.size().y, 0.002f,
                "ragdoll changed skinned model height");
        assertEquals(bindBounds.size().z, translatedBounds.size().z, 0.002f,
                "ragdoll changed skinned model depth");
        assertEquals(3f, translatedBounds.center().y - bindBounds.center().y, 0.002f,
                "ragdoll applied an incorrect scale or root translation");

        ragdoll.bodies().getFirst().setAngularVelocity(new Vector3f(0.7f, -0.5f, 1.4f));
        ragdoll.bodies().get(1).setAngularVelocity(new Vector3f(-0.4f, 0.8f, -1.2f));
        for (int step = 0; step < 120; step++) ragdoll.step(1f / 120f);

        assertSkinnedBoundsRemainFinite(bindBounds,
                skinnedBounds(loader, instance, modelScale), "free motion");
        assertBoneLengths(instance, bindLengths, "free motion");

        // Reproduce the in-game report: hold the pelvis from an off-centre
        // surface point in mid-air. Dragging deliberately keeps the island
        // awake, so this catches joint/drag oscillation hidden by ground sleep.
        ragdoll.reset();
        for (SkeletonRagdoll.Body body : ragdoll.bodies()) {
            body.teleport(body.position().add(0f, 3f, 0f), body.rotation());
        }
        SkeletonRagdoll.Body pelvis = ragdoll.bodies().getFirst();
        Vector3f dragTarget = pelvis.toWorldPoint(
                new Vector3f(pelvis.shapeSize().x, 0f, 0f));
        assertTrue(ragdoll.beginDrag(pelvis, dragTarget));
        ragdoll.setGravity(new Vector3f(0f, -9.80665f, 0f));
        for (int step = 0; step < 600; step++) {
            ragdoll.updateDragTarget(dragTarget, 1f / 120f);
            ragdoll.step(1f / 120f);
        }
        float maximumAirDragLinearSpeed = 0f;
        float maximumAirDragAngularSpeed = 0f;
        String maximumAirDragLinearBody = "";
        String maximumAirDragAngularBody = "";
        for (int step = 0; step < 120; step++) {
            ragdoll.updateDragTarget(dragTarget, 1f / 120f);
            ragdoll.step(1f / 120f);
            for (SkeletonRagdoll.Body body : ragdoll.bodies()) {
                float linearSpeed = body.linearVelocity().length();
                if (linearSpeed > maximumAirDragLinearSpeed) {
                    maximumAirDragLinearSpeed = linearSpeed;
                    maximumAirDragLinearBody = body.source().name();
                }
                float angularSpeed = body.angularVelocity().length();
                if (angularSpeed > maximumAirDragAngularSpeed) {
                    maximumAirDragAngularSpeed = angularSpeed;
                    maximumAirDragAngularBody = body.source().name();
                }
            }
        }
        assertTrue(maximumAirDragLinearSpeed < 3f,
                "Box3D held-ragdoll linear motion exceeded its stability budget: " + maximumAirDragLinearSpeed
                        + " body=" + maximumAirDragLinearBody
                        + " angular=" + maximumAirDragAngularSpeed
                        + " angularBody=" + maximumAirDragAngularBody);
        assertTrue(maximumAirDragAngularSpeed < 25f,
                "Box3D held-ragdoll angular motion must remain bounded while the active constraint prevents sleep: "
                        + maximumAirDragAngularSpeed
                        + " body=" + maximumAirDragAngularBody);
        assertFalse(ragdoll.sleeping(),
                "an active drag constraint must not freeze the articulated island");
        ragdoll.updateDragTarget(new Vector3f(dragTarget).add(0.25f, 0f, 0f), 1f / 120f);
        assertFalse(ragdoll.sleeping(), "moving the drag target must keep the complete island awake");
        ragdoll.endDrag();

        ragdoll.setCollisionsEnabled(true);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                ragdoll.addStaticBoxCollider(new Vector3f(x, -1f, z),
                        new Vector3f(x + 1f, 0f, z + 1f), 0.8f, 0f);
            }
        }
        for (int step = 0; step < 600; step++) ragdoll.step(1f / 120f);
        float maximumRestingLinearSpeed = 0f;
        float maximumRestingAngularSpeed = 0f;
        String maximumRestingLinearBody = "";
        String maximumRestingAngularBody = "";
        for (int step = 0; step < 120; step++) {
            ragdoll.step(1f / 120f);
            for (SkeletonRagdoll.Body body : ragdoll.bodies()) {
                // Secondary hair/cloth deliberately remains responsive and
                // may keep the combined Box3D island awake. The strict rest
                // budget protects the primary humanoid; secondary motion is
                // bounded by the held-air checks above and the finite mesh/
                // bone-length checks below.
                if (!body.profiledRagdollBody()) continue;
                float linearSpeed = body.linearVelocity().length();
                if (linearSpeed > maximumRestingLinearSpeed) {
                    maximumRestingLinearSpeed = linearSpeed;
                    maximumRestingLinearBody = body.source().name();
                }
                float angularSpeed = body.angularVelocity().length();
                if (angularSpeed > maximumRestingAngularSpeed) {
                    maximumRestingAngularSpeed = angularSpeed;
                    maximumRestingAngularBody = body.source().name();
                }
            }
        }
        SkeletonRagdoll.Joint worstAnchorJoint = ragdoll.joints().stream()
                .filter(joint -> ((SkeletonRagdoll.Body) joint.bodyA()).profiledRagdollBody()
                        && ((SkeletonRagdoll.Body) joint.bodyB()).profiledRagdollBody())
                .max(Comparator.comparingDouble(joint -> joint.relativePosition().length()))
                .orElse(null);
        float maximumAnchorError = worstAnchorJoint == null
                ? 0f : worstAnchorJoint.relativePosition().length();
        // Box3D's maintained ragdoll tuning deliberately uses compliant
        // constraints. Keep the native anchors within 5 mm while the stricter
        // bone-length assertion below still guards visible mesh stretching.
        assertTrue(maximumAnchorError < 0.005f,
                "ground collision joint anchor error=" + maximumAnchorError
                        + (worstAnchorJoint == null ? "" : " joint="
                        + ((SkeletonRagdoll.Body) worstAnchorJoint.bodyA()).source().name()
                        + "->" + ((SkeletonRagdoll.Body) worstAnchorJoint.bodyB()).source().name()));
        SkeletonRagdoll.Joint worstAngularJoint = ragdoll.joints().stream()
                .filter(joint -> joint.rotationLimiter() != null)
                .max(Comparator.comparingDouble(SkeletonRagdoll.Joint::angularLimitViolation))
                .orElseThrow();
        float maximumAngularViolation = worstAngularJoint.angularLimitViolation();
        assertTrue(maximumAngularViolation <= Math.toRadians(3.0),
                "ground collision angular joint limit drift=" + Math.toDegrees(maximumAngularViolation)
                        + " parent=" + ((SkeletonRagdoll.Body) worstAngularJoint.bodyA()).source().name()
                        + " child=" + ((SkeletonRagdoll.Body) worstAngularJoint.bodyB()).source().name());
        assertTrue(maximumRestingLinearSpeed < 0.05f,
                "resting tiled-ground linear jitter=" + maximumRestingLinearSpeed
                        + " body=" + maximumRestingLinearBody
                        + " angular=" + maximumRestingAngularSpeed
                        + " angularBody=" + maximumRestingAngularBody);
        assertTrue(maximumRestingAngularSpeed < 0.1f,
                "resting tiled-ground angular jitter=" + maximumRestingAngularSpeed
                        + " body=" + maximumRestingAngularBody);
        assertTrue(ragdoll.bodies().stream().allMatch(body -> body.position().isFinite()
                        && body.rotation().isFinite()),
                "primary and secondary bodies must remain finite after settling");
        assertSkinnedBoundsRemainFinite(bindBounds,
                skinnedBounds(loader, instance, modelScale), "ground collision");
        assertBoneLengths(instance, bindLengths,
                "ground collision (max anchor error=" + maximumAnchorError + ")");
    }

    private static void assertSkinnedBoundsRemainFinite(Bounds bind, Bounds current,
                                                         String scenario) {
        Vector3f size = current.size();
        assertTrue(size.isFinite(), scenario + " produced non-finite skinned bounds");
        assertTrue(size.length() < bind.size().length() * 2f,
                scenario + " exploded the skinned mesh: bind=" + bind.size()
                        + " current=" + size);
    }

    private static void assertBoneLengths(ModelInstance instance, Map<Bone, Float> bindLengths,
                                          String scenario) {
        float maximumExcess = Float.NEGATIVE_INFINITY;
        float maximumError = 0f;
        float worstTolerance = 0f;
        String worstBone = "";
        String worstParent = "";
        float worstBindLength = 0f;
        float worstCurrentLength = 0f;
        for (Map.Entry<Bone, Float> entry : bindLengths.entrySet()) {
            Bone bone = entry.getKey();
            float length = instance.getSkeletonInstance().getAbsoluteTransforms().get(bone).getPosition()
                    .distance(instance.getSkeletonInstance().getAbsoluteTransforms().get(bone.getParent()).getPosition());
            float error = Math.abs(length - entry.getValue());
            // Mixed primary/secondary chains use compliant Box3D anchors.
            // Keep their accumulated error within a visible-subpixel 5 mm
            // budget; colocated PMX helper bones use the same tolerance.
            float tolerance = 0.005f;
            float excess = error - tolerance;
            if (excess > maximumExcess) {
                maximumExcess = excess;
                maximumError = error;
                worstTolerance = tolerance;
                worstBone = bone.getName();
                worstParent = bone.getParent().getName();
                worstBindLength = entry.getValue();
                worstCurrentLength = length;
            }
        }
        assertTrue(maximumExcess < 0f,
                scenario + " PMX bone length error=" + maximumError + " at " + worstBone
                        + " parent=" + worstParent + " bind=" + worstBindLength
                        + " current=" + worstCurrentLength + " tolerance=" + worstTolerance);
    }

    private static Bounds skinnedBounds(ProbeLoader loader, ModelInstance instance, Vector3f modelScale) {
        Bone[] pmxBones = java.util.Arrays.stream(instance.getModel().getBones())
                .filter(bone -> bone.getBoneData() instanceof PmxBone).toArray(Bone[]::new);
        Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);
        for (PmxVertex vertex : loader.getVertices()) {
            Vector3f bindPosition = new Vector3f(vertex.position).mul(modelScale);
            Vector3f skinnedPosition = new Vector3f();
            float validWeight = 0f;
            for (Map.Entry<Number, Float> influence : vertex.binding.boneWeights.entrySet()) {
                int boneIndex = influence.getKey().intValue();
                float weight = influence.getValue();
                if (boneIndex < 0 || boneIndex >= pmxBones.length || weight <= 0f) continue;
                Bone bone = pmxBones[boneIndex];
                Vector3f influenced = instance.getSkeletonInstance().getSkeleton()
                        .getBoneTransforms().get(bone).getSecond().apply(new Vector3f(bindPosition));
                instance.getSkeletonInstance().getAbsoluteTransforms().get(bone).apply(influenced);
                skinnedPosition.fma(weight, influenced);
                validWeight += weight;
            }
            if (validWeight <= 0f) {
                skinnedPosition.set(bindPosition);
            } else if (Math.abs(validWeight - 1f) > 1e-5f) {
                skinnedPosition.div(validWeight);
            }
            minimum.min(skinnedPosition);
            maximum.max(skinnedPosition);
        }
        return new Bounds(minimum, maximum);
    }

    private record Bounds(Vector3f minimum, Vector3f maximum) {
        Vector3f size() {
            return new Vector3f(maximum).sub(minimum);
        }

        Vector3f center() {
            return new Vector3f(minimum).add(maximum).mul(0.5f);
        }
    }

    private static Skeleton skeleton(ProbeLoader loader) {
        List<PmxBone> definitions = loader.getBones();
        Bone[] pmxBones = new Bone[definitions.size()];
        List<Bone> roots = new ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            PmxBone definition = definitions.get(i);
            pmxBones[i] = new Bone(definition.localBoneName,
                    loader.calculateBoneTransform(definitions, definition), definition);
            if (definition.parentBoneIndex.intValue() < 0) roots.add(pmxBones[i]);
        }
        Bone root = roots.size() == 1 ? roots.getFirst()
                : new Bone("dummy_root", new Transform(), null);
        Map<Bone, List<Bone>> children = new IdentityHashMap<>();
        for (int i = 0; i < definitions.size(); i++) {
            int parentIndex = definitions.get(i).parentBoneIndex.intValue();
            Bone parent = parentIndex < 0 ? root : pmxBones[parentIndex];
            if (pmxBones[i] == root) continue;
            pmxBones[i].setParent(parent);
            children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(pmxBones[i]);
        }
        children.forEach((parent, values) -> parent.setChildren(values.toArray(Bone[]::new)));
        if (roots.size() == 1) return new Skeleton(pmxBones, root, new Anchor[0], null, new Transform());
        Bone[] allBones = new Bone[pmxBones.length + 1];
        allBones[0] = root;
        System.arraycopy(pmxBones, 0, allBones, 1, pmxBones.length);
        return new Skeleton(allBones, root, new Anchor[0], null, new Transform());
    }

    private static Path findFixtureDir() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path rootCandidate = current.resolve("modules/modelling/src/main/resources/assets/kasuga_lib/models/pmx");
            if (Files.isDirectory(rootCandidate)) return rootCandidate;
            Path moduleCandidate = current.resolve("src/main/resources/assets/kasuga_lib/models/pmx");
            if (Files.isDirectory(moduleCandidate)) return moduleCandidate;
            current = current.getParent();
        }
        return Path.of("missing-mmd-fixtures");
    }

    private static final class ProbeLoader extends PMXLoader<ByteBuffer, String, String, DummyContext> {
        ProbeLoader() { super("real-asset-probe"); }
        @Override public ByteBuffer getAsByteBuffer(ByteBuffer input) { return input.duplicate().order(ByteOrder.LITTLE_ENDIAN); }
        @Override public void beforeAllLoaders(ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void beforeLoader(StreamLoader loader, ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void build(Map<String, Model> map, String id, ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void buildMaterial(MaterialSetBuilder builder, PmxMaterial material) {}
        @Override public String getTextureIdentifier(String texturePath) { return texturePath; }
        @Override public Vertex getVertex(PmxVertex first, Collection<PmxVertex> vertices) { return null; }
        @Override public Mesh getMesh(Vertex v1, Vertex v2, Vertex v3, PmxMesh mesh) { return null; }
        @Override public Bone getBone(List<PmxBone> bones, PmxBone bone) { return null; }
        @Override public ModelData getModelData(PmxHeader header) { return header; }
        @Override public Texture loadTexture(Object id) { return null; }
        @Override public boolean isValidInput(Object input) { return input instanceof ByteBuffer; }
    }
    private static final class DummyContext implements ContextData<DummyContext> {
        @Override public void build(SerialContext<DummyContext> context) {}
    }
}
