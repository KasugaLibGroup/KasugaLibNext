package lib.kasuga.registration.data_driven.property;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.utils.json.GsonBindings;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.Function;

public class JsonItemParser {

    public static final JsonItemParser INSTANCE = new JsonItemParser();
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, ItemPropertyParser> parsers;

    private JsonItemParser() {
        parsers = new LinkedHashMap<>();

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

    @FunctionalInterface
    public interface ItemPropertyParser {
        Function<Item.Properties, Item.Properties> parse(String key, JsonElement value);
    }
}
