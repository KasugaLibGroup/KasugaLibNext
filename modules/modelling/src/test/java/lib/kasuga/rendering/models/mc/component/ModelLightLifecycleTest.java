package lib.kasuga.rendering.models.mc.component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.rendering.lighting.LightFrame;
import lib.kasuga.rendering.models.mc.dynamic.ModelLightRuntime;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelLightLifecycleTest {
    private static final String DATA = """
        {"version":1,"bones":[{"id":"bone","name":"bone","parent":null,"pivot":[0,16,0],"components":{}}],
         "bindings":[],"emitters":[],"lights":[{"id":"lamp","bone":"bone","type":"area","position":[16,0,0],
         "rotation":[0,0,90],"color":"#ff8000","width":32,"height":16,"range":128,"intensity":12}]}
        """;

    @Test void modelOwnsLightModuleAndBoneMotionChangesTheSameAnchorFrame() {
        ModelComponents components = ModelComponents.parse(JsonParser.parseString(DATA));
        Skeleton skeleton = components.createSkeleton();
        components.apply(skeleton);
        Model model = new Model(new Vertex[0], new Mesh[0], skeleton.getBones(), skeleton,
                ModelInstanceFixture.minimal().getModel().getMaterialSet(), MeshMode.QUADS, new ModelComponentsData(components), null);
        ModelLightRuntime.LightModule module;
        try (ModelInstance instance = new ModelInstance(model, null, null, null, null, null)) {
            module = ModelLightRuntime.module(instance);
            assertNotNull(module);
            instance.tick(0);
            ModelComponents.LightDefinition definition = components.lights().getFirst();
            LightFrame initial = ModelLightRuntime.frame(instance.getSkeletonInstance(), definition);
            assertNotNull(initial);
            assertEquals(0, initial.position().distance(new Vector3d(1, 1, 0)), 1e-6);
            assertEquals(2, initial.source().width(), 1e-6);
            assertEquals(8, initial.source().range(), 1e-6);
            assertTrue(initial.source().color().green() < .3f, "HEX color must be converted to linear RGB");
            instance.getSkeletonInstance().transform("bone", new Transform().rotate(0, 0, 90, true));
            instance.tick(0);
            LightFrame moved = ModelLightRuntime.frame(instance.getSkeletonInstance(), definition);
            assertTrue(moved.position().distance(initial.position()) > .5);
            assertEquals(0, initial.position().distance(new Vector3d(1, 1, 0)), 1e-6, "Earlier samples must remain immutable");
        }
        assertTrue(module.closed());
    }

    @Test void invalidLightSettingsFailAndUnknownFieldsSurviveParsing() {
        JsonObject original = JsonParser.parseString(DATA).getAsJsonObject();
        JsonObject snapshot = original.deepCopy();
        ModelComponents components = ModelComponents.parse(original);
        assertEquals(snapshot, original);
        components.lights().getFirst().position().set(999);
        assertEquals(16, components.lights().getFirst().position().x);
        for (String invalid : new String[]{DATA.replace("\"type\":\"area\"", "\"type\":\"bad\""),
                DATA.replace("\"color\":\"#ff8000\"", "\"color\":\"red\""),
                DATA.replace("\"range\":128", "\"range\":0"), DATA.replace("\"bone\":\"bone\"", "\"bone\":\"missing\"")}) {
            assertThrows(IllegalArgumentException.class, () -> ModelComponents.parse(JsonParser.parseString(invalid)));
        }
    }
}
