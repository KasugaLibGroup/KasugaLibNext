package lib.kasuga.test.registration.data_driven;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.TypeHandlerRegistry;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.factory.FactoryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TestGenericFactory  {

    public static class TestEffectReg extends Reg<TestEffectReg, Object> {
        private int duration;
        private String description;

        public TestEffectReg(){}

        public void  setDuration(int duration) {
            this.duration = duration;
        }
        public int getDuration() {
            return duration;
        }
        public void setDescription(String description) {
            this.description = description;
        }
        public String getDescription() {
            return description;
        }

        @Override
        public Object getEntry() {
            return this;
        }
    }

    @BeforeEach
    void setup() {
        TypeHandlerRegistry.register(new TypeHandler<TestEffectReg>() {
            @Override
            public String getTypeName() {
                return "TestEffect";
            }

            @Override
            public int getPhase(){
                return PHASE_GROUPS;
            }

            @Override
            public TestEffectReg parse(JsonObject json){
                String id = json.get("id").getAsString();
                FactoryRegistry.GenericFactory factory = FactoryRegistry.getGeneric("TestEffect");
                if(factory == null)
                    throw new IllegalStateException("No Factory for TestEffect");
                return (TestEffectReg) factory.create(id, json);
            }

            @Override
            public void apply(TestEffectReg definition, BuildContext context) {}
        });

        FactoryRegistry.registerGeneric("TestEffect", ((id, params) -> {
            TestEffectReg reg = new TestEffectReg();
            if(params != null){
                if(params.has("duration"))  reg.setDuration(params.get("duration").getAsInt());
                if(params.has("description"))  reg.setDescription(params.get("description").getAsString());
            }
            return reg;
        }));
    }

    @Test
    void TestParseNewRegType(){
        String json= """
            {
                "TestEffect":[
                    { "id": "fire", "duration": 600, "description": "burns" },
                    { "id": "ice", "duration": 300 }
                ]
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        List<Reg<?, ?>> parsed = new ArrayList<>();
        for(TypeHandler<?> handle : TypeHandlerRegistry.all()){
            if(root.has(handle.getTypeName())){
                JsonArray arr =  root.getAsJsonArray(handle.getTypeName());
                for (JsonElement e : arr) parsed.add((Reg<?, ?>) handle.parse(e.getAsJsonObject()));
            }
        }

        assertEquals(2, parsed.size());
        TestEffectReg first = (TestEffectReg) parsed.get(0);
        assertEquals(600, first.getDuration());
        assertEquals("burns", first.getDescription());
        TestEffectReg second = (TestEffectReg) parsed.get(1);
        assertEquals(300, second.getDuration());
        assertNull(second.getDescription());

    }


}
