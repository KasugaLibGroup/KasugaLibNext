package lib.kasuga.rendering.models.mc.compat.voxy;

import net.minecraft.client.renderer.LevelRenderer;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Optional;

/** Optional NeoForge Voxy integration, scoped to the current view's renderer. */
public final class VoxyCompat {
    private static final ClassValue<Optional<Method>> SYSTEM = new ClassValue<>() {
        @Override protected Optional<Method> computeValue(Class<?> renderer) {
            try {
                return Optional.of(renderer.getMethod("getVoxyRenderSystem"));
            } catch (NoSuchMethodException | LinkageError ignored) {
                return Optional.empty();
            }
        }
    };

    private VoxyCompat() {}

    public static boolean hasTerrainPass(LevelRenderer renderer) {
        ModList mods = ModList.get();
        if (renderer == null || mods == null || !mods.isLoaded("voxy")) return false;
        Method getter = SYSTEM.get(renderer.getClass()).orElse(null);
        // Other ports may render terrain without exposing this getter.
        if (getter == null) return true;
        try {
            return getter.invoke(renderer) != null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return true;
        }
    }
}
