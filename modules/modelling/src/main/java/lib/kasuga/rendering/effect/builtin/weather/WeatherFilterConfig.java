package lib.kasuga.rendering.effect.builtin.weather;

import jakarta.inject.Singleton;
import lib.kasuga.KasugaLib;
import lib.kasuga.inject.auto_configure.Configurable;
import lib.kasuga.inject.class_loader.BeanOnlyIn;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client configuration for world-space 3D weather occlusion and optional entity-type filtering.
 * Falls back safely to defaults when queried outside a bootstrapped Micronaut/ModConfig environment.
 */
@Singleton
@BeanOnlyIn.Client
public final class WeatherFilterConfig implements Configurable {
    private static volatile @Nullable WeatherFilterConfig instance;

    private @Nullable ModConfigSpec.BooleanValue enableWeatherFilter;
    private @Nullable ModConfigSpec.ConfigValue<List<? extends String>> includedEntityTypes;
    private @Nullable ModConfigSpec.ConfigValue<List<? extends String>> excludedEntityTypes;

    private volatile @Nullable List<? extends String> cachedIncludedSource;
    private volatile Set<ResourceLocation> cachedIncludedIds = Set.of();
    private volatile @Nullable List<? extends String> cachedExcludedSource;
    private volatile Set<ResourceLocation> cachedExcludedIds = Set.of();

    public WeatherFilterConfig() {
        instance = this;
    }

    @Nullable
    private static WeatherFilterConfig resolve() {
        WeatherFilterConfig current = instance;
        if (current != null) {
            return current;
        }
        try {
            if (KasugaLib.isRunning()) {
                current = KasugaLib.getBean(WeatherFilterConfig.class);
                instance = current;
                return current;
            }
        } catch (RuntimeException ignored) {
            // Plain JVM unit tests run without Micronaut / FML config bootstrapping.
        }
        return null;
    }

    public static boolean isWeatherFilterEnabled() {
        WeatherFilterConfig config = resolve();
        if (config == null || config.enableWeatherFilter == null) {
            return true;
        }
        try {
            return config.enableWeatherFilter.getAsBoolean();
        } catch (IllegalStateException ignored) {
            return true;
        }
    }

    public static boolean isIncluded(@Nullable ResourceLocation entityTypeId) {
        if (entityTypeId == null) {
            return false;
        }
        WeatherFilterConfig config = resolve();
        if (config == null || config.includedEntityTypes == null) {
            return false;
        }
        return config.includedIds().contains(entityTypeId);
    }

    public static boolean isExcluded(@Nullable ResourceLocation entityTypeId) {
        if (entityTypeId == null) {
            return false;
        }
        WeatherFilterConfig config = resolve();
        if (config == null || config.excludedEntityTypes == null) {
            return false;
        }
        return config.excludedIds().contains(entityTypeId);
    }

    public static boolean hasTypeListsConfigured() {
        WeatherFilterConfig config = resolve();
        return config != null && (!config.includedIds().isEmpty() || !config.excludedIds().isEmpty());
    }

    private Set<ResourceLocation> includedIds() {
        ModConfigSpec.ConfigValue<List<? extends String>> spec = this.includedEntityTypes;
        if (spec == null) {
            return Set.of();
        }
        try {
            List<? extends String> raw = spec.get();
            if (raw == cachedIncludedSource) {
                return cachedIncludedIds;
            }
            Set<ResourceLocation> parsed = parseResourceIds(raw);
            cachedIncludedSource = raw;
            cachedIncludedIds = parsed;
            return parsed;
        } catch (IllegalStateException ignored) {
            return Set.of();
        }
    }

    private Set<ResourceLocation> excludedIds() {
        ModConfigSpec.ConfigValue<List<? extends String>> spec = this.excludedEntityTypes;
        if (spec == null) {
            return Set.of();
        }
        try {
            List<? extends String> raw = spec.get();
            if (raw == cachedExcludedSource) {
                return cachedExcludedIds;
            }
            Set<ResourceLocation> parsed = parseResourceIds(raw);
            cachedExcludedSource = raw;
            cachedExcludedIds = parsed;
            return parsed;
        } catch (IllegalStateException ignored) {
            return Set.of();
        }
    }

    private static Set<ResourceLocation> parseResourceIds(List<? extends String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<ResourceLocation> result = new HashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            ResourceLocation parsed = ResourceLocation.tryParse(entry.trim());
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return Set.copyOf(result);
    }

    private static boolean isValidResourceId(Object value) {
        return value instanceof String text && ResourceLocation.tryParse(text.trim()) != null;
    }

    @Override
    public void configureClient(ModConfigSpec.Builder builder) {
        enableWeatherFilter = builder
                .comment("Enable 3D weather occlusion for indoor entity/scene spaces (rain, snow, splashes, and muffled rain audio).")
                .define("enable_weather_filter", true);
        includedEntityTypes = builder
                .comment("Optional entity type IDs (e.g. 'minecraft:boat') that should also occlude rain and snow.")
                .defineListAllowEmpty("included_entity_types", new ArrayList<>(), () -> "minecraft:boat", WeatherFilterConfig::isValidResourceId);
        excludedEntityTypes = builder
                .comment("Entity type IDs excluded from automatic weather occlusion.")
                .defineListAllowEmpty("excluded_entity_types", new ArrayList<>(), () -> "create:carriage_contraption", WeatherFilterConfig::isValidResourceId);
    }
}
