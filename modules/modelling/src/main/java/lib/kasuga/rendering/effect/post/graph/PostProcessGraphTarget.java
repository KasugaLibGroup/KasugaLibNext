package lib.kasuga.rendering.effect.post.graph;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Logical render target used to declare graph resource access. */
public record PostProcessGraphTarget(Kind kind, @Nullable ResourceLocation id) {
    private static final PostProcessGraphTarget MAIN = new PostProcessGraphTarget(Kind.MAIN, null);

    public PostProcessGraphTarget {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.MAIN && id != null) {
            throw new IllegalArgumentException("The main target does not have a managed target ID");
        }
        if (kind == Kind.MANAGED && id == null) {
            throw new IllegalArgumentException("A managed target requires an ID");
        }
    }

    public static PostProcessGraphTarget main() {
        return MAIN;
    }

    public static PostProcessGraphTarget managed(ResourceLocation id) {
        return new PostProcessGraphTarget(Kind.MANAGED, Objects.requireNonNull(id, "id"));
    }

    public boolean isMain() {
        return kind == Kind.MAIN;
    }

    @Override
    public String toString() {
        return isMain() ? "main" : Objects.requireNonNull(id).toString();
    }

    public enum Kind {
        MAIN,
        MANAGED
    }
}
