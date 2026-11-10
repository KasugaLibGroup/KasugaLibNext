package lib.kasuga.rendering.models.mc.component;

import com.google.gson.*;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be.*;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je.JEModelLoader;
import lib.kasuga.rendering.models.uml.loaders.structural.*;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real geometry/structure pipeline; only texture IO is substituted for a context-free fixture. */
class ModelComponentLoaderTest {
    private static final String EXT = """
        {"version":1,"bones":[
          {"id":"base","name":"base","parent":null,"pivot":[2,0,0],"components":{"physics":{"mode":"kinematic","shape":"box","position":[3,4,5],"size":[2,3,4]}}},
          {"id":"tip","name":"tip","parent":"base","pivot":[2,8,0],"components":{"ik":{"target":"target","links":[{"bone":"base"}]}}},
          {"id":"target","name":"target","parent":null,"pivot":[10,8,0],"components":{}}],
          "bindings":[{"element":0,"bone":"tip"}],"emitters":[]}
        """;
    private static <T> Layer<T> textureStub() {
        return new Layer<>() {
            @Override public void process(T input, Context context) {
                var builder=context.getLoader().getMaterialSetBuilder();
                builder.registerTexture("root",new Texture("root",16,16,null));
                builder.beginMaterial().useTexture("root").addSpriteBuildingFunc((materials,sprites,material)->
                        ((lib.kasuga.rendering.models.uml.loaders.SpriteSetBuilder<String>)sprites).textureId("root")
                                .rectangularUVs(new org.joml.Vector2f(),new org.joml.Vector2f(1)).endSprite()).endMaterial();
                context.setData("material",builder.getMaterials().getFirst());
            }
        };
    }
    @Test void javaBindsExportedElementIndicesWithoutMutatingBaseJson() {
        JsonObject json=JsonParser.parseString("""
            {"elements":[{"from":[0,0,0],"to":[4,8,4],"faces":{"north":{"uv":[0,0,4,8],"texture":"#root"}}}]}
            """).getAsJsonObject();
        json.add("kasuga_lib",JsonParser.parseString(EXT));
        var snapshot=json.deepCopy();var loader=new JEModelLoader("test");loader.registerProcessor("texture_layer",textureStub());
        var id=ResourceLocation.parse("test:model");Model model=loader.load(id,json).get(id);
        assertEquals(snapshot,json);assertEquals(8,model.getVertices().length);
        var tip=model.getSkeleton().getBoneMap().get("tip");
        for(var vertex:model.getVertices()) {assertEquals(1,vertex.getBinding().getWeights().length);assertSame(tip,vertex.getBinding().getWeights()[0].getFirst());assertEquals(1,vertex.getBinding().getWeights()[0].getSecond(),1e-7);}
        assertEquals(1,model.getSkeleton().getDynamics().ikChains().size());
        JsonObject plain=snapshot.deepCopy();plain.remove("kasuga_lib");var old=new JEModelLoader("plain");old.registerProcessor("texture_layer",textureStub());
        assertEquals(1,old.load(id,plain).get(id).getSkeleton().getBones().length);
    }
    @Test void bedrockKeepsNativeBonesAndAddsAnExtensionOnlyControllerAndLocator() {
        JsonObject geometry=JsonParser.parseString("""
            {"description":{"identifier":"geometry.demo","texture_width":16,"texture_height":16,"visible_bounds_offset":[0,0,0]},"bones":[
             {"name":"base","pivot":[-2,0,0]},
             {"name":"tip","parent":"base","pivot":[-2,8,0],"locators":{"emitter":[-3,12,5]},"cubes":[{"origin":[-4,8,0],"size":[4,8,4],"uv":[0,0]}]}]}
            """).getAsJsonObject();
        geometry.add("kasuga_lib",JsonParser.parseString(EXT));
        JsonObject root=new JsonObject();root.addProperty("format_version","1.12.0");root.add("textures",new JsonObject());JsonArray geometries=new JsonArray();geometries.add(geometry);root.add("minecraft:geometry",geometries);
        var snapshot=root.deepCopy();var loader=new BEModelLoader("bedrock","test");loader.registerProcessor("texture_layer",new Layer<JsonObject>() {
            @Override public void process(JsonObject ignored,Context context){context.setData("textures",new HashMap<>(Map.of("root",lib.kasuga.structure.Pair.of(null,null))));}
        });
        var id=ResourceLocation.parse("test:model");var loaded=loader.load(id,root);Model model=loaded.get(id);
        assertNotNull(model);assertEquals(snapshot,root);assertEquals(4,model.getSkeleton().getBones().length);
        assertEquals(0.625,model.getSkeleton().getBindingAbsolute(model.getSkeleton().getBoneMap().get("target")).getPosition().x,1e-6);
        var body=model.getSkeleton().getDynamics().physics().bodies().getFirst();assertEquals(5.0/16,body.position().x,1e-6);
        assertEquals(3.0/16,model.getSkeleton().getAnchor("emitter").getTransform().getPosition().x,1e-6);
        assertFalse(model.getSkeleton().getDynamics().ikChains().isEmpty());
        for(var vertex:model.getVertices())assertEquals(1,vertex.getBinding().getWeights()[0].getSecond(),1e-7);
    }
}
