package lib.kasuga.rendering.models.mc.api.pbr;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Public registry for code-driven or config-backed PBR conversion rules. */
public final class PbrConversionRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CopyOnWriteArrayList<Entry> RULES = new CopyOnWriteArrayList<>();

    private PbrConversionRegistry() {}

    public static Registration register(ResourceLocation id, int priority, PbrConversionRule rule) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        unregister(id);
        RULES.add(new Entry(id, priority, rule));
        RULES.sort(Comparator.comparingInt(Entry::priority).thenComparing(entry -> entry.id().toString()));
        return () -> unregister(id);
    }

    public static boolean unregister(ResourceLocation id) {
        return RULES.removeIf(entry -> entry.id().equals(id));
    }

    public static PbrConversionSettings apply(PbrMaterialContext context, PbrConversionSettings defaults) {
        PbrConversionSettings current = Objects.requireNonNull(defaults, "defaults");
        for (Entry entry : RULES) {
            try {
                PbrConversionSettings result = entry.rule().apply(context, current);
                if (result != null) current = result;
                else LOGGER.warn("PBR conversion rule {} returned null; keeping previous settings", entry.id());
            } catch (RuntimeException exception) {
                LOGGER.warn("PBR conversion rule {} failed; keeping previous settings", entry.id(), exception);
            }
        }
        return current;
    }

    public static int size() {
        return RULES.size();
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private record Entry(ResourceLocation id, int priority, PbrConversionRule rule) {}
}
