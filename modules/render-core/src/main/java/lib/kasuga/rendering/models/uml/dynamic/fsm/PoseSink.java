package lib.kasuga.rendering.models.uml.dynamic.fsm;

/** Consumes the blended per-tick pose. The single seam to Minecraft's animation types (morph/skeleton/material). */
@FunctionalInterface
public interface PoseSink {

    /** Flush the composed {@link Blender}: exactly one write per morph id / bone / frame. */
    void apply(Blender blender);
}
