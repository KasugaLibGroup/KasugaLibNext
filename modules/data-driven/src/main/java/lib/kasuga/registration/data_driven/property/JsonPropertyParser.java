package lib.kasuga.registration.data_driven.property;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.utils.json.GsonBindings;
import com.mojang.logging.LogUtils;
import lib.kasuga.registration.data_driven.property.compiler.PropertyCompiler;
import lib.kasuga.registration.data_driven.property.compiler.RLCompiler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

public class JsonPropertyParser {

    public static final JsonPropertyParser INSTANCE = new JsonPropertyParser();
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<String, SoundType> soundTypes;
    private final List<PropertyCompiler> compilers;
    private final Map<String, Predicate<JsonElement>> strictExtensions;

    public List<Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> parseBlockProperties(JsonObject json) {
        if (json == null) return List.of();
        List<Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> result = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            Function<BlockBehaviour.Properties, BlockBehaviour.Properties> mod = parseBlockProperty(entry.getKey(), entry.getValue());
            if (mod != null) {
                result.add(mod);
            }
        }
        return result;
    }

    /**
     * Validates block properties without invoking legacy modifiers or discarding failures as WARN.
     */
    public List<StrictPropertyIssue> validateStrict(JsonObject json) {
        if (json == null) return List.of();
        List<StrictPropertyIssue> issues = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            String pointer = "/" + JsonPointers.escape(key);
            Predicate<JsonElement> extension = strictExtensions.get(key.toLowerCase(Locale.ROOT));
            if (extension != null) {
                if (!extension.test(value)) {
                    issues.add(new StrictPropertyIssue(
                            pointer,
                            "Extended block property '" + key + "' has an invalid value"
                    ));
                }
                continue;
            }
            validateStrictBuiltin(key, value, pointer, issues);
        }
        return List.copyOf(issues);
    }

    private void validateStrictBuiltin(
            String key,
            JsonElement value,
            String pointer,
            List<StrictPropertyIssue> issues
    ) {
        switch (key) {
            case "no_occlusion", "no_collision", "no_collission",
                    "requires_correct_tool", "replaceable", "dynamic_shape",
                    "random_ticks", "no_loot_table", "ignited_by_lava", "liquid",
                    "force_solid_on", "air", "no_terrain_particles" -> {
                if (StrictPropertyValues.exactBoolean(value) == null) {
                    issue(issues, pointer, "Expected a JSON boolean");
                }
            }
            case "friction", "speed_factor", "jump_factor", "destroy_time",
                    "explosion_resistance" -> {
                if (StrictPropertyValues.finiteFloat(value) == null) {
                    issue(issues, pointer, "Expected a finite JSON number");
                }
            }
            case "strength" -> validateStrictStrength(value, pointer, issues);
            case "map_color" -> {
                String name = StrictPropertyValues.exactString(value);
                if (name == null || DyeColor.byName(name, null) == null) {
                    issue(issues, pointer, "Unknown or non-string map_color");
                }
            }
            case "sound_type" -> {
                String name = StrictPropertyValues.exactString(value);
                if (name == null || !soundTypes.containsKey(name)) {
                    issue(issues, pointer, "Unknown or non-string sound_type");
                }
            }
            case "light_emission" -> {
                Integer level = StrictPropertyValues.exactInt(value);
                if (level == null || level < 0 || level > 15) {
                    issue(issues, pointer, "light_emission must be an integer from 0 to 15");
                }
            }
            default -> issue(issues, pointer, "Unknown block property '" + key + "'");
        }
    }

    private static void validateStrictStrength(
            JsonElement value,
            String pointer,
            List<StrictPropertyIssue> issues
    ) {
        if (value != null && value.isJsonArray()) {
            var values = StrictPropertyValues.exactArray(value, 2);
            if (values == null) {
                issue(issues, pointer, "strength must contain exactly two numbers");
                return;
            }
            for (int index = 0; index < values.size(); index++) {
                if (StrictPropertyValues.finiteFloat(values.get(index)) == null) {
                    issue(issues, pointer + "/" + index, "Expected a finite JSON number");
                }
            }
            return;
        }
        if (StrictPropertyValues.finiteFloat(value) == null) {
            issue(issues, pointer, "Expected a finite JSON number or a two-number array");
        }
    }

    private static void issue(
            List<StrictPropertyIssue> issues,
            String pointer,
            String message
    ) {
        issues.add(new StrictPropertyIssue(pointer, message));
    }

    private Function<BlockBehaviour.Properties, BlockBehaviour.Properties> parseBlockProperty(String key, JsonElement value) {
        try {
            boolean matched = false;
            Function<BlockBehaviour.Properties, BlockBehaviour.Properties> mod = null;
            for (PropertyCompiler compiler : compilers) {
                if (!compiler.valid(key, value)) continue;
                matched = true;
                mod = compiler.getSupplier().apply(key, value);
                break;
            }
            if (!matched) {
                LOGGER.warn("Unknown block property: {}", key);
            }
            return mod;
        } catch (Exception e) {
            LOGGER.warn("Failed to parse block property '{}': {}", key, e.getMessage());
            return null;
        }
    }

    private static boolean bool(JsonElement value) {
        return GsonBindings.GSON.fromJson(value, Boolean.class);
    }

    private Function<BlockBehaviour.Properties, BlockBehaviour.Properties> parseStrength(JsonElement value) {
        if (value.isJsonArray()) {
            float[] values = GsonBindings.GSON.fromJson(value, float[].class);
            if (values.length == 2) {
                return props -> { props.strength(values[0], values[1]); return props; };
            }
        }
        float strength = GsonBindings.GSON.fromJson(value, Float.class);
        return props -> { props.strength(strength); return props; };
    }

    private Function<BlockBehaviour.Properties, BlockBehaviour.Properties> parseMapColor(JsonElement value) {
        String name = GsonBindings.GSON.fromJson(value, String.class);
        DyeColor dye = DyeColor.byName(name, null);
        if (dye == null) {
            LOGGER.warn("Unknown map color: {}", name);
            return null;
        }
        return props -> { props.mapColor(dye); return props; };
    }

    private Function<BlockBehaviour.Properties, BlockBehaviour.Properties> parseSoundType(JsonElement value) {
        String name = GsonBindings.GSON.fromJson(value, String.class);
        SoundType type = soundTypes.get(name);
        if (type == null) {
            LOGGER.warn("Unknown sound type: {}", name);
            return null;
        }
        return props -> { props.sound(type); return props; };
    }

    private Map<String, SoundType> buildSoundTypeMap() {
        Map<String, SoundType> map = new HashMap<>();
        addSound(map, "stone", SoundType.STONE);
        addSound(map, "wood", SoundType.WOOD);
        addSound(map, "gravel", SoundType.GRAVEL);
        addSound(map, "grass", SoundType.GRASS);
        addSound(map, "sand", SoundType.SAND);
        addSound(map, "metal", SoundType.METAL);
        addSound(map, "glass", SoundType.GLASS);
        addSound(map, "wool", SoundType.WOOL);
        addSound(map, "wet_grass", SoundType.WET_GRASS);
        addSound(map, "coral_block", SoundType.CORAL_BLOCK);
        addSound(map, "lantern", SoundType.LANTERN);
        addSound(map, "stem", SoundType.STEM);
        addSound(map, "sweet_berry_bush", SoundType.SWEET_BERRY_BUSH);
        addSound(map, "crop", SoundType.CROP);
        addSound(map, "nether_wood", SoundType.NETHER_WOOD);
        addSound(map, "nether_ore", SoundType.NETHER_ORE);
        addSound(map, "nether_wart", SoundType.NETHER_WART);
        addSound(map, "basalt", SoundType.BASALT);
        addSound(map, "soul_sand", SoundType.SOUL_SAND);
        addSound(map, "bone_block", SoundType.BONE_BLOCK);
        addSound(map, "netherrack", SoundType.NETHERRACK);
        addSound(map, "nether_bricks", SoundType.NETHER_BRICKS);
        addSound(map, "shroomlight", SoundType.SHROOMLIGHT);
        addSound(map, "wart_block", SoundType.WART_BLOCK);
        addSound(map, "anvil", SoundType.ANVIL);
        addSound(map, "chain", SoundType.CHAIN);
        addSound(map, "copper", SoundType.COPPER);
        addSound(map, "calcite", SoundType.CALCITE);
        addSound(map, "tuff", SoundType.TUFF);
        addSound(map, "dripstone_block", SoundType.DRIPSTONE_BLOCK);
        addSound(map, "pointed_dripstone", SoundType.POINTED_DRIPSTONE);
        addSound(map, "moss_carpet", SoundType.MOSS_CARPET);
        addSound(map, "hanging_roots", SoundType.HANGING_ROOTS);
        addSound(map, "rooted_dirt", SoundType.ROOTED_DIRT);
        addSound(map, "big_dripleaf", SoundType.BIG_DRIPLEAF);
        addSound(map, "small_dripleaf", SoundType.SMALL_DRIPLEAF);
        addSound(map, "spore_blossom", SoundType.SPORE_BLOSSOM);
        addSound(map, "cave_vines", SoundType.CAVE_VINES);
        addSound(map, "powder_snow", SoundType.POWDER_SNOW);
        addSound(map, "amethyst_cluster", SoundType.AMETHYST_CLUSTER);
        addSound(map, "sponge", SoundType.SPONGE);
        addSound(map, "lily_pad", SoundType.LILY_PAD);
        addSound(map, "cobweb", SoundType.COBWEB);
        addSound(map, "suspicious_sand", SoundType.SUSPICIOUS_SAND);
        addSound(map, "suspicious_gravel", SoundType.SUSPICIOUS_GRAVEL);
        // TODO: 这里应该加入一个事件，使得其他用户可以在这里加入自己的声音类型
        return map;
    }

    public List<PropertyCompiler> buildCompilers() {
        List<PropertyCompiler> compilers = new ArrayList<>();
        addCompiler(compilers, "no_occlusion", constructFunction(JsonPropertyParser::bool, props -> { props.noOcclusion(); return props; }, null));
        addCompiler(compilers, "no_collision", constructFunction(JsonPropertyParser::bool, props -> { props.noCollission(); return props; }, null));
        addCompiler(compilers, "no_collission", constructFunction(JsonPropertyParser::bool, props -> { props.noCollission(); return props; }, null));
        addCompiler(compilers, "requires_correct_tool", constructFunction(JsonPropertyParser::bool, props -> { props.requiresCorrectToolForDrops(); return props; }, null));
        addCompiler(compilers, "replaceable", constructFunction(JsonPropertyParser::bool, props -> { props.replaceable(); return props; }, null));
        addCompiler(compilers, "dynamic_shape", constructFunction(JsonPropertyParser::bool, props -> { props.dynamicShape(); return props; }, null));
        addCompiler(compilers, "random_ticks", constructFunction(JsonPropertyParser::bool, props -> { props.randomTicks(); return props; }, null));
        addCompiler(compilers, "no_loot_table", constructFunction(JsonPropertyParser::bool, props -> { props.noLootTable(); return props; }, null));
        addCompiler(compilers, "ignited_by_lava", constructFunction(JsonPropertyParser::bool, props -> { props.ignitedByLava(); return props; }, null));
        addCompiler(compilers, "liquid", constructFunction(JsonPropertyParser::bool, props -> { props.liquid(); return props; }, null));
        addCompiler(compilers, "force_solid_on", constructFunction(JsonPropertyParser::bool, props -> { props.forceSolidOn(); return props; }, null));
        addCompiler(compilers, "air", constructFunction(JsonPropertyParser::bool, props -> { props.air(); return props; }, null));
        addCompiler(compilers, "no_terrain_particles", constructFunction(JsonPropertyParser::bool, props -> { props.noTerrainParticles(); return props; }, null));
        addCompiler(compilers, "friction", (k, v) -> { float f = GsonBindings.GSON.fromJson(v, Float.class); return props -> { props.friction(f); return props; }; });
        addCompiler(compilers, "speed_factor", (k, v) -> { float f = GsonBindings.GSON.fromJson(v, Float.class); return props -> { props.speedFactor(f); return props; }; });
        addCompiler(compilers, "jump_factor", (k, v) -> { float f = GsonBindings.GSON.fromJson(v, Float.class); return props -> { props.jumpFactor(f); return props; }; });
        addCompiler(compilers, "destroy_time", (k, v) -> { float f = GsonBindings.GSON.fromJson(v, Float.class); return props -> { props.destroyTime(f); return props; }; });
        addCompiler(compilers, "explosion_resistance", (k, v) -> { float f = GsonBindings.GSON.fromJson(v, Float.class); return props -> { props.explosionResistance(f); return props; }; });
        addCompiler(compilers, "strength", (k, v) -> parseStrength(v));
        addCompiler(compilers, "map_color", (k, v) -> parseMapColor(v));
        addCompiler(compilers, "sound_type", (k, v) -> parseSoundType(v));
        addCompiler(compilers, "light_emission", (k, v) -> { int level = GsonBindings.GSON.fromJson(v, Integer.class); return props -> { props.lightLevel(state -> level); return props; }; });
        // TODO: 这里应该加入一个事件，使得其他用户可以在这里加入自己的编译器类型
        return compilers;
    }

    public static @NotNull BiFunction<String, JsonElement, Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> constructFunction(
            @NotNull Predicate<JsonElement> predicate,
            @NotNull Function<BlockBehaviour.Properties, BlockBehaviour.Properties> prop,
            @Nullable Function<BlockBehaviour.Properties, BlockBehaviour.Properties> defaultProp) {
        return (k, v) -> predicate.test(v) ? prop : defaultProp;
    }

    private static void addSound(Map<String, SoundType> map, String name, SoundType type) {
        map.put(name, type);
    }

    private static void addCompiler(List<PropertyCompiler> list, String name,
                                    BiFunction<String, JsonElement, Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> supplier) {
        list.add(new RLCompiler(ResourceLocation.tryParse(name.toLowerCase(Locale.ROOT)), supplier));
    }

    private JsonPropertyParser() {
        compilers = buildCompilers();
        soundTypes = buildSoundTypeMap();
        strictExtensions = new HashMap<>();
    }

    public void registerCompiler(PropertyCompiler compiler) {
        compilers.add(compiler);
    }

    public void registerCompiler(String key, BiFunction<String, JsonElement, Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> supplier) {
        addCompiler(compilers, key, supplier);
    }

    public void registerStrictCompiler(String key, Predicate<JsonElement> validator) {
        strictExtensions.put(
                Objects.requireNonNull(key, "key").toLowerCase(Locale.ROOT),
                Objects.requireNonNull(validator, "validator")
        );
    }

    protected void clearCompilers() {
        compilers.clear();
    }

    public void removeCompiler(PropertyCompiler compiler) {
        compilers.remove(compiler);
    }

    public int compilerSize() {
        return compilers.size();
    }

    public static JsonPropertyParser getInstance() {
        return INSTANCE;
    }
}
