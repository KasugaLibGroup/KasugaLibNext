package lib.kasuga.rendering.models.uml.dynamic.fsm;

import java.util.Arrays;
import java.util.Set;

/** Restricts a layer's bone-channel influence to a named subset (or all bones). */
public record BoneMask(Set<String> names, boolean wildcard) {

    public static BoneMask all() {
        return new BoneMask(Set.of(), true);
    }

    public static BoneMask only(String... names) {
        return new BoneMask(Set.of(names), false);
    }

    /** Parses a comma-separated bone name list; "*" (or empty) means all bones. */
    public static BoneMask of(String value) {
        if (value == null || value.isBlank() || value.equals("*")) {
            return all();
        }
        return new BoneMask(Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet()), false);
    }

    public BoneMask {
        names = names == null ? Set.of() : Set.copyOf(names);
    }

    public boolean matches(String bone) {
        return wildcard || names.contains(bone);
    }
}
