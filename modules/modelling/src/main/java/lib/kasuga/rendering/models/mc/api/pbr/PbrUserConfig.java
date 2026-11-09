package lib.kasuga.rendering.models.mc.api.pbr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.JsonDeserializer;
import lib.kasuga.utils.json.GsonBindings;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Player-editable JSON adapter built on top of {@link PbrConversionRegistry}. */
public final class PbrUserConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = GsonBindings.builder().setPrettyPrinting().create();
    private static final ResourceLocation RULE_ID = ResourceLocation.parse("kasuga_lib:user_config");
    private static final String FILE_NAME = "kasuga-pbr.json";
    private static volatile ConfigInput config = GSON.fromJson(defaultConfig(), ConfigInput.class);

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
            ConfigInput parsed = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), ConfigInput.class);
            config = java.util.Objects.requireNonNull(parsed, "Root value must be a JSON object");
            LOGGER.info("Loaded player PBR conversion config from {}", path);
        } catch (Exception exception) {
            LOGGER.warn("Failed to load {}; keeping the previous PBR conversion config", path, exception);
        }
    }

    private static PbrConversionSettings apply(PbrMaterialContext context, PbrConversionSettings current) {
        ConfigInput snapshot = config;
        if (!snapshot.enabled) return current;
        if (snapshot.defaults != null) current = applyOverrides(snapshot.defaults, current);
        for (RuleInput rule : snapshot.rules == null ? java.util.List.<RuleInput>of() : snapshot.rules) {
            if (matches(rule, context)) current = applyOverrides(rule, current);
        }
        return current;
    }

    private static boolean matches(RuleInput rule, PbrMaterialContext context) {
        return rule.enabled && matchesGlob(rule.model, context.modelId().toString())
                && matchesGlob(rule.texture, context.textureId().toString())
                && matchesGlob(rule.localName, context.localName()) && matchesGlob(rule.englishName, context.englishName())
                && (rule.materialIndex == null || rule.materialIndex == context.materialIndex())
                && (rule.minShininess == null || context.shininess() >= rule.minShininess)
                && (rule.maxShininess == null || context.shininess() <= rule.maxShininess);
    }

    private static boolean matchesGlob(String pattern, String value) {
        return pattern == null || globMatches(pattern, value);
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

    private static PbrConversionSettings applyOverrides(Overrides input, PbrConversionSettings current) {
        return new PbrConversionSettings(input.smoothness == null ? current.smoothness() : input.smoothness,
                input.f0Code == null ? current.f0Code() : input.f0Code,
                input.subsurface == null ? current.subsurface() : input.subsurface,
                input.normalStrength == null ? current.normalStrength() : input.normalStrength,
                input.emission == null ? current.emission() : input.emission);
    }

    private static class Overrides { Float smoothness, subsurface, normalStrength, emission; Integer f0Code; }
    private static final class RuleInput extends Overrides {
        Boolean enabled = true;
        String model, texture, localName, englishName;
        Integer materialIndex;
        Float minShininess, maxShininess;
    }
    private static final class ConfigInput {
        Boolean enabled = true;
        @JsonAdapter(DefaultsAdapter.class) Overrides defaults;
        @JsonAdapter(RulesAdapter.class) java.util.List<RuleInput> rules = java.util.List.of();
    }
    private static final class DefaultsAdapter implements JsonDeserializer<Overrides> {
        @Override public Overrides deserialize(JsonElement json, java.lang.reflect.Type type,
                com.google.gson.JsonDeserializationContext context) {
            return json.isJsonObject() ? context.deserialize(json, Overrides.class) : new Overrides();
        }
    }
    private static final class RulesAdapter implements JsonDeserializer<java.util.List<RuleInput>> {
        @Override public java.util.List<RuleInput> deserialize(JsonElement json, java.lang.reflect.Type type,
                com.google.gson.JsonDeserializationContext context) {
            java.util.List<RuleInput> result = new java.util.ArrayList<>();
            if (json.isJsonArray()) for (JsonElement rule : json.getAsJsonArray()) {
                if (rule.isJsonObject()) result.add(context.deserialize(rule, RuleInput.class));
            }
            return result;
        }
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
