package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;

/** How a bone-channel target is applied when flushing a blended pose to the skeleton. */
public enum ApplyMode {
    /** Replace the bone's transform entirely. */
    REPLACE("replace"),
    /** Multiply onto the current transform (additive bone delta). */
    MULTIPLY("multiply"),
    /** Add a translation delta onto the current transform. */
    ADD("add");

    public static final Codec<ApplyMode> CODEC = Codec.STRING.xmap(ApplyMode::byName, m -> m.serialName);

    private final String serialName;

    ApplyMode(String serialName) {
        this.serialName = serialName;
    }

    /** Lowercase serialized name (stable JSON form). */
    public String serialName() {
        return serialName;
    }

    /** Lookup by serialized name; {@code null}/unknown falls back to {@link #REPLACE} (the default apply mode). */
    public static ApplyMode byName(String name) {
        if (name == null) {
            return REPLACE;
        }
        for (ApplyMode mode : values()) {
            if (mode.serialName.equals(name)) {
                return mode;
            }
        }
        return REPLACE;
    }
}
