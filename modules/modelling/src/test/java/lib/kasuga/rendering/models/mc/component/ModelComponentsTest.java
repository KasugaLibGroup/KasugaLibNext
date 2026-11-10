package lib.kasuga.rendering.models.mc.component;

import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelComponentsTest {
    private static final String DATA = """
      {"version":1,"bones":[
       {"id":"a","name":"base","parent":null,"pivot":[0,0,0],"components":{"physics":{"shape":"box","size":[2,4,6],"position":[16,0,0],"mass":2,"mode":"kinematic"}}},
       {"id":"b","name":"mid","parent":"a","pivot":[0,8,0],"components":{"physics":{"parent_body":"a","joint":{"rotation_min":[-30,-20,-10],"rotation_max":[30,20,10]}}}},
       {"id":"c","name":"tip","parent":"b","pivot":[0,16,0],"components":{"ik":{"target":"t","links":[{"bone":"a"},{"bone":"b","min":[-90,-45,-30],"max":[90,45,30]}]}}},
       {"id":"t","name":"target","parent":null,"pivot":[8,8,0],"components":{}}],"bindings":[{"element":0,"bone":"b"}],"emitters":[]}
      """;
    @Test void translatesUnitsAndResolvesActualBoneIdentities() {
        var data=ModelComponents.parse(JsonParser.parseString(DATA));
        Skeleton skeleton=data.createSkeleton(); data.apply(skeleton);
        var rig=skeleton.getDynamics();
        assertEquals(1,rig.ikChains().size());
        var chain=rig.ikChains().getFirst();
        assertSame(skeleton.getBoneMap().get("target"),chain.controller());
        assertSame(skeleton.getBoneMap().get("mid"),chain.links().get(1).bone());
        assertEquals(Math.PI/2,chain.links().get(1).limit().maximum().x,1e-6);
        assertEquals(0.5,skeleton.getBindingAbsolute(skeleton.getBoneMap().get("mid")).getPosition().y,1e-6);
        var body=rig.physics().bodies().getFirst();
        assertEquals(0.125,body.size().x,1e-6); assertEquals(1,body.position().x,1e-6);
        assertEquals(2,body.mass(),1e-6); assertEquals(0,body.mode());
        assertEquals(1,rig.physics().joints().size());
        assertEquals(Math.PI/6,rig.physics().joints().getFirst().rotationMax().x,1e-6);
        assertEquals("mid",data.elementBone(0));
        var second=data.createSkeleton();data.apply(second);
        assertNotSame(chain.controller(),second.getDynamics().ikChains().getFirst().controller());
    }
    @Test void rejectsBrokenVersionsReferencesHierarchyAndValues() {
        for(String bad:new String[]{DATA.replace("\"version\":1","\"version\":2"),DATA.replace("\"target\":\"t\"","\"target\":\"missing\""),DATA.replace("\"parent\":null","\"parent\":\"c\""),DATA.replace("\"mass\":2","\"mass\":-1"),DATA.replace("\"name\":\"target\",\"parent\":null","\"name\":\"target\",\"parent\":\"a\""),DATA.replace("\"bone\":\"b\"","\"bone\":\"missing\"")}) {
            assertThrows(IllegalArgumentException.class,()->{var d=ModelComponents.parse(JsonParser.parseString(bad));d.apply(d.createSkeleton());});
        }
    }
    @Test void missingExtensionIsEmptyAndInputIsNeverMutated(){
        assertTrue(ModelComponents.parse(null).isEmpty());
        var input=JsonParser.parseString(DATA);var snapshot=input.deepCopy();var data=ModelComponents.parse(input);data.apply(data.createSkeleton());assertEquals(snapshot,input);
    }
}
