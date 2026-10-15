package lib.kasuga.rendering.models.mc.api.pbr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Player-editable JSON adapter built on top of {@link PbrConversionRegistry}. */
public final class PbrUserConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ResourceLocation RULE_ID = ResourceLocation.parse("kasuga_lib:user_config");
    private static final String FILE_NAME = "kasuga-pbr.json";
    private static volatile JsonObject config = defaultConfig();

    static {
        PbrConversionRegistry.register(RULE_ID, 1_000, PbrUserConfig::apply);
    }

    private PbrUserConfig() {}

    public static void reload(Path configDirectory) {
        Path path = configDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(configDirectory);
            if (Files.notExists(path)) {
                Files.writeString(path, GSON.toJson(defaultConfig()), StandardCharsets.UTF_8);
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("Root value must be a JSON object");
            config = parsed.getAsJsonObject();
            LOGGER.info("Loaded player PBR conversion config from {}", path);
        } catch (Exception exception) {
            LOGGER.warn("Failed to load {}; keeping the previous PBR conversion config", path, exception);
        }
    }

    private static PbrConversionSettings apply(PbrMaterialContext context, PbrConversionSettings current) {
        JsonObject snapshot = config;
        if (snapshot.has("enabled") && !snapshot.get("enabled").getAsBoolean()) return current;
        if (snapshot.has("defaults") && snapshot.get("defaults").isJsonObject()) {
            current = applyOverrides(snapshot.getAsJsonObject("defaults"), current);
        }
        if (!snapshot.has("rules") || !snapshot.get("rules").isJsonArray()) return current;
        for (JsonElement element : snapshot.getAsJsonArray("rules")) {
            if (!element.isJsonObject()) continue;
            JsonObject rule = element.getAsJsonObject();
            if (matches(rule, context)) current = applyOverrides(rule, current);
        }
        return current;
    }

    private static boolean matches(JsonObject rule, PbrMaterialContext context) {
        if (rule.has("enabled") && !rule.get("enabled").getAsBoolean()) return false;
        if (!matchesGlob(rule, "model", context.modelId().toString())) return false;
        if (!matchesGlob(rule, "texture", context.textureId().toString())) return false;
        if (!matchesGlob(rule, "local_name", context.localName())) return false;
        if (!matchesGlob(rule, "english_name", context.englishName())) return false;
        if (rule.has("material_index") && rule.get("material_index").getAsInt() != context.materialIndex()) return false;
        if (rule.has("min_shininess") && context.shininess() < rule.get("min_shininess").getAsFloat()) return false;
        return !rule.has("max_shininess") || context.shininess() <= rule.get("max_shininess").getAsFloat();
    }

    private static boolean matchesGlob(JsonObject rule, String key, String value) {
        return !rule.has(key) || globMatches(rule.get(key).getAsString(), value);
    }

    static boolean globMatches(String pattern, String value) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String v = value.toLowerCase(Locale.ROOT);
        int pIndex = 0;
        int vIndex = 0;
        int star = -1;
        int retry = -1;
        while (vIndex < v.length()) {
            if (pIndex < p.length() && (p.charAt(pIndex) == '?' || p.charAt(pIndex) == v.charAt(vIndex))) {
                pIndex++;
                vIndex++;
            } else if (pIndex < p.length() && p.charAt(pIndex) == '*') {
                star = pIndex++;
                retry = vIndex;
            } else if (star >= 0) {
                pIndex = star + 1;
                vIndex = ++retry;
            } else {
                return false;
            }
        }
        while (pIndex < p.length() && p.charAt(pIndex) == '*') pIndex++;
        return pIndex == p.length();
    }

    private static PbrConversionSettings applyOverrides(JsonObject object, PbrConversionSettings current) {
        float smoothness = getFloat(object, "smoothness", current.smoothness());
        int f0Code = getInt(object, "f0_code", current.f0Code());
        float subsurface = getFloat(object, "subsurface", current.subsurface());
        float normalStrength = getFloat(object, "normal_strength", current.normalStrength());
        float emission = getFloat(object, "emission", current.emission());
        return new PbrConversionSettings(smoothness, f0Code, subsurface, normalStrength, emission);
    }

    private static float getFloat(JsonObject object, String key, float fallback) {
        return object.has(key) ? object.get(key).getAsFloat() : fallback;
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private static JsonObject defaultConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_documentation", "Rules are applied top-to-bottom. Match fields support * and ?. Override fields: smoothness, f0_code, subsurface, normal_strength, emission.");
        root.addProperty("enabled", true);
        root.add("defaults", new JsonObject());
        JsonArray rules = new JsonArray();
        JsonObject example = new JsonObject();
        example.addProperty("enabled", false);
        example.addProperty("model", "kasuga_lib:models/pmx/*");
        example.addProperty("texture", "*");
        example.addProperty("local_name", "*");
        example.addProperty("material_index", 0);
        example.addProperty("smoothness", 0.65f);
        example.addProperty("f0_code", PbrMetalPreset.GOLD.f0Code());
        example.addProperty("subsurface", 0.0f);
        example.addProperty("normal_strength", 0.08f);
        example.addProperty("emission", 0.0f);
        rules.add(example);
        root.add("rules", rules);
        return root;
    }
}
