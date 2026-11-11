package lib.kasuga.rendering.models.mc.typo.bbmodel;

import com.google.gson.*;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ModelInstancePoseSink;
import lib.kasuga.rendering.models.uml.structure.material.*;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional private-asset parity check; private model data never belongs in test resources. */
class QjDemoParityTest {
    @Test void matchesBlockbenchEndpointsAcrossTheWholeSpeedInputCycle() throws Exception {
        String fixture = System.getenv("KASUGA_QJ_FIXTURE");
        assumeTrue(fixture != null, "KASUGA_QJ_FIXTURE is an external generated fixture directory");
        Path directory = Path.of(fixture);
        var definition = BbModelDefinition.parse(Files.readString(directory.resolve("qj_bogey_main.bbmodel")));
        Texture texture = new Texture("fixture", 16f, 16f, null);
        Material material = new Material(new Texture[]{texture}, null);
        Map<Integer,Material> materials = new HashMap<>();
        for (int i = -1; i < 64; i++) materials.put(i,material);
        var model = KsgBbModelLoader.buildSkeletonAndGeometry(definition,materials,new MaterialSet(texture,material));
        ModelInstance instance = new ModelInstance(model,null,null,null,null,null);
        var sink = new ModelInstancePoseSink(instance);
        var clip = model.getAnimations().get("qj.running");
        JsonArray expected = JsonParser.parseString(Files.readString(directory.resolve("blockbench-poses.json"))).getAsJsonArray(), actual = new JsonArray();
        float maximum = 0;
        String worst = "";
        for (JsonElement entry : expected) {
            JsonObject sample = entry.getAsJsonObject();
            float time = sample.get("time").getAsFloat();
            sink.applyPose(clip.sample(time));
            instance.updateImmediate();
            JsonObject positions = new JsonObject();
            for (var pair : sample.getAsJsonObject("positions").entrySet()) {
                var bone = model.getSkeleton().getBoneMap().get(pair.getKey());
                Vector3f position = instance.getSkeletonInstance().getAbsoluteTransforms().get(bone).getPosition().mul(16);
                JsonArray array = new JsonArray();array.add(position.x);array.add(position.y);array.add(position.z); positions.add(pair.getKey(),array);
                var e=pair.getValue().getAsJsonArray();
                float error = position.distance(new Vector3f(e.get(0).getAsFloat(),e.get(1).getAsFloat(),e.get(2).getAsFloat()));
                if(error>maximum){maximum=error;worst=time+"/"+pair.getKey();}
            }
            JsonObject out = new JsonObject();out.addProperty("time",time);out.add("positions",positions);
            out.addProperty("solver",instance.getSkeletonInstance().ikSolveResults().toString());actual.add(out);
        }
        Files.writeString(directory.resolve("game-poses.json"),new GsonBuilder().setPrettyPrinting().create().toJson(actual));
        System.out.println("QJ parity max="+maximum+"px at "+worst);
        assertTrue(maximum < 0.1f,"Blockbench/game pose mismatch: "+maximum+"px at "+worst);
        Path geometryFile=directory.resolve("blockbench-geometry.json");
        if(Files.exists(geometryFile)) {
            sink.applyPose(clip.sample(4));instance.updateImmediate();
            var geometry=JsonParser.parseString(Files.readString(geometryFile)).getAsJsonObject();
            Map<String,List<Vector3f>> byBone=new HashMap<>();
            for(var vertex:model.getVertices()) {
                var bone=vertex.getBinding().getWeights()[0].getFirst();
                Vector3f p=model.getSkeleton().getBindingAbsolute(bone).invertTransform().transformPosition(new Vector3f(vertex.getPosition()));
                p=instance.getSkeletonInstance().getAbsoluteTransforms().get(bone).apply(p).mul(16);
                byBone.computeIfAbsent(bone.getName(),k->new ArrayList<>()).add(p);
            }
            float maxGeometry=0;String worstGeometry="";
            for(var e:geometry.entrySet()) {
                var points=byBone.get(e.getKey());assertNotNull(points,"Missing geometry "+e.getKey());
                for(var point:e.getValue().getAsJsonArray()) {
                    var a=point.getAsJsonArray();var p=new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());
                    float nearest=Float.POSITIVE_INFINITY;for(var q:points)nearest=Math.min(nearest,p.distance(q));
                    if(nearest>maxGeometry){maxGeometry=nearest;worstGeometry=e.getKey()+" "+p;}
                }
            }
            System.out.println("QJ geometry max="+maxGeometry+"px at "+worstGeometry);
            assertTrue(maxGeometry<0.15f,"Geometry mismatch "+maxGeometry+"px at "+worstGeometry);
        }
        instance.close();
    }
}
