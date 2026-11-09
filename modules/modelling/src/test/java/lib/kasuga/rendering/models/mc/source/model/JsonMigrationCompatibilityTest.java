package lib.kasuga.rendering.models.mc.source.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollConfig;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEModelData;
import lib.kasuga.rendering.models.mc.source.model.assembly.AssemblyResourceDefinitions;
import org.joml.Vector3f;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipMeta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonMigrationCompatibilityTest {
    private static JsonObject json(String source) { return JsonParser.parseString(source).getAsJsonObject(); }
    private static final String BODY = "\"bodies\":[{\"rigid_body\":0,\"role\":\"pelvis\"}]";

    @Test void preservesInitialStateWireNameAndVectorValues() {
        var config = MinecraftRagdollConfig.fromJson(json("{" + BODY + ",\"initial_state\":{\"offset\":[1,2,3],"
                + "\"root_linear_velocity\":[4,5,6],\"root_angular_velocity\":[7,8,9]}}"));
        assertAll(() -> assertEquals(new Vector3f(1, 2, 3), config.initialState().offset()),
                () -> assertEquals(new Vector3f(4, 5, 6), config.initialState().rootLinearVelocity()),
                () -> assertEquals(new Vector3f(7, 8, 9), config.initialState().rootAngularVelocity()));
    }

    @Test void explicitNullDoesNotActLikeAnOmittedAssemblyOption() {
        assertThrows(RuntimeException.class, () -> AssemblyResourceDefinitions.parse(json(
                "{\"body\":{\"model\":\"test:body\"},\"bind_tolerance\":null}")));
    }

    @Test void explicitNullDoesNotDisableRagdollLimitValidation() {
        assertThrows(RuntimeException.class, () -> MinecraftRagdollConfig.fromJson(json(
                "{\"bodies\":[{\"rigid_body\":0,\"role\":\"pelvis\",\"rotation_min_degrees\":null}]}")));
    }

    @Test void optionalJavaModelFieldsRetainTypeFallbacks() {
        var model = JEModelData.fromJson(json("{\"ambientocclusion\":\"false\",\"gui_light\":17}"));
        assertAll(() -> assertTrue(model.isAmbientOcclusion()), () -> assertEquals("side", model.getGuiLight()));
    }

    @Test void malformedOptionalZipEncodingRetainsPlatformDefault() {
        assertEquals(java.nio.charset.Charset.defaultCharset(), new ZipMeta(json("{\"encoding\":{}}" )).getCharset());
    }
}
