package lib.kasuga.create.registration.track;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record TrackModelDescriptor(
        Mode mode,
        ResourceLocation tie,
        ResourceLocation leftSegment,
        ResourceLocation rightSegment
) {
    public enum Mode {
        DEFAULT,
        STANDARD,
        CUSTOM
    }

    public TrackModelDescriptor {
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.CUSTOM) {
            Objects.requireNonNull(tie, "tie");
            Objects.requireNonNull(leftSegment, "leftSegment");
            Objects.requireNonNull(rightSegment, "rightSegment");
        }
    }

    public static TrackModelDescriptor defaultModels() {
        return new TrackModelDescriptor(Mode.DEFAULT, null, null, null);
    }

    public static TrackModelDescriptor standardModels() {
        return new TrackModelDescriptor(Mode.STANDARD, null, null, null);
    }

    public static TrackModelDescriptor custom(
            ResourceLocation tie,
            ResourceLocation leftSegment,
            ResourceLocation rightSegment
    ) {
        return new TrackModelDescriptor(Mode.CUSTOM, tie, leftSegment, rightSegment);
    }
}
