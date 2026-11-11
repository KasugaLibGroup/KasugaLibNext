package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.math.Transform;

/** Shared composition for evaluated formula transforms. */
final class FormulaTransforms {
    private FormulaTransforms() {
    }

    /**
     * Composes translation, intrinsic Z-Y-X rotation, and scale.
     *
     * @param tx translation X
     * @param ty translation Y
     * @param tz translation Z
     * @param rx rotation around X in degrees
     * @param ry rotation around Y in degrees
     * @param rz rotation around Z in degrees
     * @param sx scale X
     * @param sy scale Y
     * @param sz scale Z
     * @return the composed local transform
     */
    static Transform build(
            float tx,
            float ty,
            float tz,
            float rx,
            float ry,
            float rz,
            float sx,
            float sy,
            float sz
    ) {
        return new Transform()
                .translate(tx, ty, tz)
                .rotate(0f, 0f, rz, true)
                .rotate(0f, ry, 0f, true)
                .rotate(rx, 0f, 0f, true)
                .scale(sx, sy, sz);
    }
}
