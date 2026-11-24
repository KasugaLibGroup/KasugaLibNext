package lib.kasuga.rendering.models.mc.proxies;

import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.uml.backend.ModelLightingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProxyModelBlockFactoriesTest {
    @Test
    void lightingIsOptInAndAllowsAnExplicitPbrOverrideOfContentDefaults() {
        var input = JsonParser.parseString("""
                {"model":"example:models/block/test.bbmodel"}
                """).getAsJsonObject();
        assertNull(ProxyModelBlockFactories.parseModelSettings(input).lightingMode());
        input.addProperty("lighting_mode", "vanilla_block");
        assertEquals(ModelLightingMode.BLOCK,
                ProxyModelBlockFactories.parseModelSettings(input).lightingMode());
        input.addProperty("lighting_mode", "pbr");
        assertEquals(ModelLightingMode.PBR,
                ProxyModelBlockFactories.parseModelSettings(input).lightingMode());
    }

    @Test
    void malformedLightingModesFailDuringRegistrationInsteadOfSilentlyChoosingPbr() {
        for (String value : java.util.List.of("\"unknown\"", "true", "1", "null", "{}", "[]")) {
            var input = JsonParser.parseString("""
                    {"model":"example:models/block/test.bbmodel", "lighting_mode":%s}
                    """.formatted(value)).getAsJsonObject();
            assertThrows(IllegalArgumentException.class,
                    () -> ProxyModelBlockFactories.parseModelSettings(input), value);
        }
    }

    @Test
    void parsesStateSelectedModels() {
        var params = ProxyModelBlockFactories.ModelParams.parse(JsonParser.parseString("""
                {
                  "model": "example:models/block/left.bbmodel",
                  "model_by_state": {
                    "hinge": {
                      "left": "example:models/block/left.bbmodel",
                      "right": "example:models/block/right.bbmodel"
                    }
                  }
                }
                """).getAsJsonObject());

        assertEquals("example:models/block/left.bbmodel",
                params.selection.defaultGroup().parts().getFirst().model().toString());
        assertEquals(3, params.selection.groups().size());
    }

    @Test
    void compilesMultiPartModelGroupsAndResolvesSelectorsByGroupId() {
        var params = ProxyModelBlockFactories.ModelParams.parse(JsonParser.parseString("""
                {
                  "default_model": "left",
                  "model_groups": {
                    "left": {
                      "parts": [
                        {
                          "id": "body",
                          "model": "example:models/block/body.bbmodel"
                        },
                        {
                          "id": "wheel",
                          "model": "example:models/block/wheel.bbmodel",
                          "local": [
                            { "translate": [0, 1, 2] }
                          ]
                        }
                      ]
                    },
                    "right": {
                      "parts": [
                        {
                          "id": "body",
                          "model": "example:models/block/body.bbmodel"
                        },
                        {
                          "id": "wheel",
                          "model": "example:models/block/wheel.bbmodel",
                          "local": [
                            { "translate": [0, 1, -2] }
                          ]
                        }
                      ]
                    }
                  },
                  "model_by_state": {
                    "hinge": {
                      "left": "left",
                      "right": "right"
                    }
                  }
                }
                """).getAsJsonObject());

        assertEquals("left", params.selection.defaultGroup().id());
        assertEquals(2, params.selection.groups().size());
        assertEquals(
                java.util.List.of("body", "wheel"),
                params.selection.defaultGroup().partIds()
        );
    }

    @Test
    void rejectsPropertyToClipAnimationMapping() {
        var params = JsonParser.parseString("""
                {
                  "animation": {
                    "name": "deploy",
                    "property": "extended",
                    "active_when": true
                  }
                }
                """).getAsJsonObject();

        assertThrows(
                IllegalArgumentException.class,
                () -> ProxyModelBlockFactories.ModelParams.parse(params)
        );
    }

    @Test
    void rejectsBusinessBehaviorInGenericBlockParams() {
        var params = JsonParser.parseString("""
                {"toggle_property":"extended"}
                """).getAsJsonObject();

        assertThrows(
                IllegalArgumentException.class,
                () -> ProxyModelBlockFactories.validateBlockParams(params)
        );
    }

    @Test
    void rejectsStateGroupsThatWouldChangeStablePartAddresses() {
        var params = JsonParser.parseString("""
                {
                  "default_model": "left",
                  "model_groups": {
                    "left": {
                      "parts": [
                        {
                          "id": "body",
                          "model": "example:models/block/left.bbmodel"
                        }
                      ]
                    },
                    "right": {
                      "parts": [
                        {
                          "id": "different",
                          "model": "example:models/block/right.bbmodel"
                        }
                      ]
                    }
                  },
                  "model_by_state": {
                    "hinge": {
                      "left": "left",
                      "right": "right"
                    }
                  }
                }
                """).getAsJsonObject();

        assertThrows(
                IllegalArgumentException.class,
                () -> ProxyModelBlockFactories.ModelParams.parse(params)
        );
    }

}
