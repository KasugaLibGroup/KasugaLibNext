package lib.kasuga.rendering.models.uml.typo.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.fsm.PoseBlend;
import lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssemblyBuilder;
import lib.kasuga.rendering.models.uml.structure.Model;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class GltfMorphAnimationTest {
    @TempDir Path temporary;

    @Test
    void defaultWeightsAreNodeScopedAndNodeOverridesMesh() throws Exception {
        Model model = GltfModelConverter.convert(GltfLoader.load(write(fixture("LINEAR", 0, 0, 1, .5f))));
        assertVector(new Vector3f(13, -3, 0), model.getVertices()[0].getPosition());
        assertVector(new Vector3f(-9.5f, 2, 0), model.getVertices()[3].getPosition());
        assertFalse(model.getMorph().getMorphs(new GltfAsset.MorphId(0, 0)).isEmpty());
        assertFalse(model.getMorph().getMorphs(new GltfAsset.MorphId(1, 0)).isEmpty());
    }

    @Test
    void linearWeightsReachModelInstancePositionsAndNormalsAndStopRestoresDefaults() throws Exception {
        Model model = load(fixture("LINEAR", 0, 0, 1, .5f));
        ModelInstance instance = instance(model);
        sample(instance, 1f);
        assertVector(new Vector3f(12, 3, 0), position(instance, 0));
        assertVector(new Vector3f(-9.5f, 2, 0), position(instance, 3));
        Vector3f normal = instance.getMorph().getVertexNormal(model.getVertices()[0], model.getMeshes()[0], new Vector3f());
        assertVector(new Vector3f(.25f / 2, .5f / 3, 1f / 4).normalize(), normal);
        instance.getPosing().stop();
        instance.sample(0); instance.updateImmediate();
        assertVector(new Vector3f(13, -3, 0), position(instance, 0));
    }

    @Test
    void stepHoldsThePreviousKeyAndClampsAtClipEnds() throws Exception {
        Model model = load(fixture("STEP", 0, 0, 1, .5f));
        var data = (GltfModelData) model.getModelData();
        var sampler = new GltfSampler(data);
        var clip = data.asset().animation("morph");
        assertEquals(-.75f, sampler.sample(clip, -1).morphs().get(new GltfAsset.MorphId(0, 0)).value());
        ModelInstance instance = instance(model);
        sample(instance, 1f);
        assertVector(new Vector3f(10, 0, 0), position(instance, 0));
        instance.getPosing().seek(2); instance.sample(0); instance.updateImmediate();
        assertVector(new Vector3f(14, 6, 0), position(instance, 0));
        assertEquals(.25f, sampler.sample(clip, 20).morphs().get(new GltfAsset.MorphId(0, 0)).value());
    }

    @Test
    void cubicSplineUsesPackedScalarTargetsAndTimeScaledTangents() throws Exception {
        Model model = load(fixture("CUBICSPLINE",
                0, 0, -.5f, 1.5f, 2, 0, // key 0: in, value, out
                0, 2, 1.5f, -.5f, 0, 0)); // key 1
        ModelInstance instance = instance(model);
        sample(instance, 1);
        assertVector(new Vector3f(14, 0, 0), position(instance, 0)); // weights (1, 0)
        instance.getPosing().seek(2); instance.sample(0); instance.updateImmediate();
        assertVector(new Vector3f(16, -6, 0), position(instance, 0)); // weights (1.5, -.5)
    }

    @Test
    void signedWeightsAndWeightsAboveOneSurviveCrossFadesAndAssembly() throws Exception {
        Model source = load(fixture("LINEAR", -.5f, 1.5f, 1.5f, -.5f));
        Model body = lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture.minimal().getModel();
        var assembly = new ModelAssemblyBuilder("body", body, 0)
                .part("gltf", source, 0, part -> part.matchBodyBones(false)).assemble();
        ModelInstance instance = new ModelInstance(assembly.model(), null, null, null, null, null);
        assertTrue(instance.getPosing().play("gltf/morph", false));
        instance.sample(0); instance.updateImmediate();
        var vertex = assembly.part("gltf").vertex(source.getVertices()[0]);
        assertVector(new Vector3f(8, 18, 0), instance.getMorph().getVertexPos(vertex, new Vector3f()));

        var data = (GltfModelData) source.getModelData();
        var sampler = new GltfSampler(data);
        var clip = data.asset().animation("morph");
        var halfway = PoseBlend.blend(sampler.sample(clip, 0), sampler.sample(clip, 2), .5f);
        var sink = new lib.kasuga.rendering.models.uml.dynamic.fsm.ModelInstancePoseSink(
                new ModelInstance(source, null, null, null, null, null));
        sink.applyPose(halfway); sink.model().updateImmediate();
        assertVector(new Vector3f(12, 6, 0), position(sink.model(), 0));
    }

    @Test
    void allPrimitivesOfTheTargetNodeReceiveTheWeight() throws Exception {
        JsonObject json = fixture("LINEAR", 0, 0, 1, .5f);
        JsonArray primitives = json.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives");
        primitives.add(primitives.get(0).deepCopy());
        Model model = load(json);
        ModelInstance instance = instance(model);
        sample(instance, 2);
        assertVector(new Vector3f(14, 6, 0), position(instance, 0));
        assertVector(new Vector3f(14, 6, 0), position(instance, 3));
    }

    @Test
    void rejectsWeightCountsThatDoNotMatchTheTargetCount() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> load(fixture("LINEAR", 0, 1, 2)));
        JsonObject json = fixture("LINEAR", 0, 0, 1, 1);
        json.getAsJsonArray("nodes").get(0).getAsJsonObject().add("weights", JsonParser.parseString("[1]"));
        assertThrows(IllegalArgumentException.class, () -> load(json));
    }

    @Test
    void rejectsInconsistentPrimitiveTargetsAndWeightsWithoutTargets() throws Exception {
        JsonObject json = fixture("LINEAR", 0, 0, 1, 1);
        JsonArray primitives = json.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives");
        var missing = primitives.get(0).deepCopy().getAsJsonObject();
        missing.remove("targets"); primitives.add(missing);
        assertThrows(IllegalArgumentException.class, () -> load(json));
        JsonObject noTargets = fixture("LINEAR", 0, 0, 1, 1);
        noTargets.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives")
                .get(0).getAsJsonObject().remove("targets");
        noTargets.getAsJsonArray("meshes").get(0).getAsJsonObject().remove("weights");
        noTargets.getAsJsonArray("nodes").get(0).getAsJsonObject().remove("weights");
        assertThrows(IllegalArgumentException.class, () -> load(noTargets));
    }

    private Model load(JsonObject json) throws Exception {
        return GltfModelConverter.convert(GltfLoader.loadAllAnimations(write(json)));
    }

    private Path write(JsonObject json) throws Exception {
        Path file = temporary.resolve("morph.gltf");
        Files.writeString(file, json.toString()); return file;
    }

    private static ModelInstance instance(Model model) {
        ModelInstance result = new ModelInstance(model, null, null, null, null, null);
        assertTrue(result.getPosing().play("morph", false)); return result;
    }

    private static void sample(ModelInstance instance, float seconds) {
        instance.animate(seconds); instance.sample(1); instance.updateImmediate();
    }

    private static Vector3f position(ModelInstance instance, int vertex) {
        return instance.getMorph().getVertexPos(instance.getModel().getVertices()[vertex], new Vector3f());
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-5); assertEquals(expected.y, actual.y, 1e-5); assertEquals(expected.z, actual.z, 1e-5);
    }

    private static JsonObject fixture(String interpolation, float... weights) {
        ByteBuffer bytes = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        JsonArray views = new JsonArray(), accessors = new JsonArray();
        add(bytes, views, accessors, 3, 0, 0, 0, 1, 0, 0, 0, 1, 0); // positions 0
        add(bytes, views, accessors, 3, 0, 0, 1, 0, 0, 1, 0, 0, 1); // normals 1
        add(bytes, views, accessors, 3, 2, 0, 0, 2, 0, 0, 2, 0, 0); // target 0 pos 2
        add(bytes, views, accessors, 3, 0, 1, 0, 0, 1, 0, 0, 1, 0); // target 0 normal 3
        add(bytes, views, accessors, 3, 0, 4, 0, 0, 4, 0, 0, 4, 0); // target 1 pos 4
        add(bytes, views, accessors, 3, 1, 0, 0, 1, 0, 0, 1, 0, 0); // target 1 normal 5
        add(bytes, views, accessors, 1, 5, 7); // key times 6
        add(bytes, views, accessors, 1, weights); // scalar weights 7
        String encoded = Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes.array(), bytes.position()));
        JsonObject json = JsonParser.parseString("""
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1]}],
                 "nodes":[{"name":"animated","mesh":0,"translation":[10,0,0],"scale":[2,3,4],"weights":[0.75,-0.25]},
                          {"name":"static","mesh":0,"translation":[-10,0,0]}],
                 "meshes":[{"weights":[0.25,0.5],"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},
                              "targets":[{"POSITION":2,"NORMAL":3},{"POSITION":4,"NORMAL":5}]}]}],
                 "animations":[{"name":"morph","samplers":[{"input":6,"output":7,"interpolation":"%s"}],
                                "channels":[{"sampler":0,"target":{"node":0,"path":"weights"}}]}],
                 "buffers":[{"byteLength":%d,"uri":"data:application/octet-stream;base64,%s"}]}
                """.formatted(interpolation, bytes.position(), encoded)).getAsJsonObject();
        json.add("bufferViews", views); json.add("accessors", accessors); return json;
    }

    private static void add(ByteBuffer bytes, JsonArray views, JsonArray accessors, int components, float... values) {
        int index = views.size(), offset = bytes.position();
        for (float value : values) bytes.putFloat(value);
        views.add(JsonParser.parseString("{\"buffer\":0,\"byteOffset\":" + offset + ",\"byteLength\":" + values.length * 4 + "}"));
        accessors.add(JsonParser.parseString("{\"bufferView\":" + index + ",\"componentType\":5126,\"count\":"
                + values.length / components + ",\"type\":\"" + (components == 1 ? "SCALAR" : "VEC3") + "\"}"));
    }
}
