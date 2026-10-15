package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/**
 * How a {@link Layer} composes with the layers below it. Distinct axis from
 * {@code MorphInstance.BlendMode} (per-morph color multiply/add).
 */
public enum BlendMode {
    /** Bottom layer; its pose is the base. */
    BASE("base"),
    /** Accumulates (adds) on top of the base within the {@link Blender}. */
    ADDITIVE("additive"),
    /** Masked replace: overrides base+additive for the masked channels. */
    OVERRIDE("override");

    public static final Codec<BlendMode> CODEC = Codec.STRING.comapFlatMap(
            s -> {
                BlendMode mode = byName(s);
                return mode != null ? DataResult.success(mode) : DataResult.error(() -> "Unknown blend mode: " + s);
            },
            m -> m.serialName);

    private final String serialName;

    BlendMode(String serialName) {
        this.serialName = serialName;
    }

    /** Lowercase serialized name (stable JSON form). */
    public String serialName() {
        return serialName;
    }

    /** Lookup by serialized name; {@code null} on unknown (the codec turns that into a decode error). */
    public static BlendMode byName(String name) {
        if (name == null) {
            return null;
        }
        for (BlendMode mode : values()) {
            if (mode.serialName.equals(name)) {
                return mode;
            }
        }
        return null;
    }
}

