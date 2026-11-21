package lib.kasuga.registration.data_driven.property;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.utils.json.GsonBindings;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.Function;

public class JsonItemParser {

    public static final JsonItemParser INSTANCE = new JsonItemParser();
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, ItemPropertyParser> parsers;
    private final Map<String, java.util.function.Predicate<JsonElement>> strictExtensions;

    private JsonItemParser() {
        parsers = new LinkedHashMap<>();
        strictExtensions = new LinkedHashMap<>();

        parsers.put("stacks_to", (k, v) -> props -> { props.stacksTo(GsonBindings.GSON.fromJson(v, Integer.class)); return props; });
        parsers.put("rarity", (k, v) -> {
            try {
                Rarity rarity = Rarity.valueOf(GsonBindings.GSON.fromJson(v, String.class).toUpperCase(Locale.ROOT));
                return props -> { props.rarity(rarity); return props; };
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Unknown rarity: {}", GsonBindings.GSON.fromJson(v, String.class));
                return null;
            }
        });
        parsers.put("fire_resistant", (k, v) -> GsonBindings.GSON.fromJson(v, Boolean.class) ? props -> { props.fireResistant(); return props; } : null);
        parsers.put("durability", (k, v) -> props -> { props.durability(GsonBindings.GSON.fromJson(v, Integer.class)); return props; });
        parsers.put("no_repair", (k, v) -> GsonBindings.GSON.fromJson(v, Boolean.class) ? props -> { props.setNoRepair(); return props; } : null);
    }

    public List<Function<Item.Properties, Item.Properties>> parseItemProperties(JsonObject json) {
        if (json == null) return List.of();
        List<Function<Item.Properties, Item.Properties>> result = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            Function<Item.Properties, Item.Properties> mod = parseItemProperty(entry.getKey(), entry.getValue());
            if (mod != null) {
                result.add(mod);
            }
        }
        return result;
    }

    /**
     * Validates item properties without invoking legacy modifiers or discarding failures as WARN.
     */
    public List<StrictPropertyIssue> validateStrict(JsonObject json) {
        if (json == null) return List.of();
        List<StrictPropertyIssue> issues = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = entry.getKey();
            String pointer = "/" + JsonPointers.escape(key);
            JsonElement value = entry.getValue();
            if ("tab".equals(key)) {
                String tab = StrictPropertyValues.exactString(value);
                if (tab == null
                        || tab.indexOf(':') <= 0
                        || ResourceLocation.tryParse(tab) == null) {
                    issues.add(new StrictPropertyIssue(
                            pointer,
                            "tab must be a valid namespace-qualified resource location"
                    ));
                }
                continue;
            }
            var extension = strictExtensions.get(key);
            if (extension != null) {
                if (!extension.test(value)) {
                    issues.add(new StrictPropertyIssue(
                            pointer,
                            "Extended item property '" + key + "' has an invalid value"
                    ));
                }
                continue;
            }
            switch (key) {
                case "stacks_to" -> {
                    Integer stack = StrictPropertyValues.exactInt(value);
                    if (stack == null || stack < 1 || stack > 99) {
                        issues.add(new StrictPropertyIssue(
                                pointer,
                                "stacks_to must be an integer from 1 to 99"
                        ));
                    }
                }
                case "rarity" -> {
                    String rarity = StrictPropertyValues.exactString(value);
                    if (rarity == null || !validRarity(rarity)) {
                        issues.add(new StrictPropertyIssue(pointer, "Unknown or non-string rarity"));
                    }
                }
                case "fire_resistant", "no_repair" -> {
                    if (StrictPropertyValues.exactBoolean(value) == null) {
                        issues.add(new StrictPropertyIssue(pointer, "Expected a JSON boolean"));
                    }
                }
                case "durability" -> {
                    Integer durability = StrictPropertyValues.exactInt(value);
                    if (durability == null || durability < 1) {
                        issues.add(new StrictPropertyIssue(
                                pointer,
                                "durability must be a positive integer"
                        ));
                    }
                }
                default -> issues.add(new StrictPropertyIssue(
                        pointer,
                        "Unknown item property '" + key + "'"
                ));
            }
        }
        return List.copyOf(issues);
    }

    private static boolean validRarity(String value) {
        try {
            Rarity.valueOf(value.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private Function<Item.Properties, Item.Properties> parseItemProperty(String key, JsonElement value) {
        // Skip "tab" — handled separately by JsonTreeBuilder for creative tab registration
        if ("tab".equals(key)) return null;

        ItemPropertyParser parser = parsers.get(key);
        if (parser != null) {
            try {
                return parser.parse(key, value);
            } catch (Exception e) {
                LOGGER.warn("Failed to parse item property '{}': {}", key, e.getMessage());
                return null;
            }
        }
        LOGGER.warn("Unknown item property: {}", key);
        return null;
    }

    public void registerParser(String key, ItemPropertyParser parser) {
        parsers.put(key, parser);
    }

    public void registerStrictParser(String key, java.util.function.Predicate<JsonElement> validator) {
        strictExtensions.put(
                Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(validator, "validator")
        );
    }

    @FunctionalInterface
    public interface ItemPropertyParser {
        Function<Item.Properties, Item.Properties> parse(String key, JsonElement value);
    }
}
