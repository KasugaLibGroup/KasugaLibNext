package lib.kasuga.rendering.models.mc.typo.pmx_entry;

import com.google.gson.JsonParser;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZipHelperTest {

    @Test
    void normalizesMmdTexturePaths() {
        assertEquals("tex/skin.png", ZipHelper.normalizeEntryName(".\\tex\\SKIN.PNG"));
        assertEquals("sph/gold.jpg", ZipHelper.normalizeEntryName("models/../sph//gold.jpg"));
    }

    @Test
    void parsesConfigurableModelScale() {
        ZipMeta scalar = new ZipMeta(JsonParser.parseString("{\"model_scale\":0.1}").getAsJsonObject());
        assertEquals(new Vector3f(0.1f), scalar.getModelScale());

        ZipMeta perAxis = new ZipMeta(JsonParser.parseString(
                "{\"model_scale\":[0.1,0.2,0.3]}").getAsJsonObject());
        assertEquals(new Vector3f(0.1f, 0.2f, 0.3f), perAxis.getModelScale());

        ZipMeta defaults = new ZipMeta(JsonParser.parseString("{}").getAsJsonObject());
        assertEquals(new Vector3f(ZipMeta.DEFAULT_MODEL_SCALE), defaults.getModelScale());
    }
}
