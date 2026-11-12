package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of formula-driven transforms for rigid parts and bones.
 *
 * <p>Part-root transforms and bone transforms occupy separate maps because model hosts write them
 * through different render slots. Transforms are copied on input and output.
 */
public final class FormulaTransformPose {

    private final Map<String, Transform> parts;
    private final Map<BoneTarget, Transform> bones;

    /**
     * Creates a defensive snapshot.
     *
     * @param parts whole-part transforms keyed by part ID
     * @param bones bone transforms keyed by part and bone ID
     */
    public FormulaTransformPose(
            Map<String, Transform> parts,
            Map<BoneTarget, Transform> bones
    ) {
        this.parts = copyParts(parts);
        this.bones = copyBones(bones);
    }

    /**
     * Returns the transform for one rigid part.
     *
     * @param part part ID
     * @return a defensive transform copy, or {@code null}
     */
    public @Nullable Transform part(String part) {
        Transform transform = parts.get(part);
        return transform == null ? null : transform.copy();
    }

    /**
     * Returns the transform for one bone.
     *
     * @param part part ID, or {@code null} for the clip's single model instance
     * @param bone bone ID
     * @return a defensive transform copy, or {@code null}
     */
    public @Nullable Transform bone(@Nullable String part, String bone) {
        Transform transform = bones.get(new BoneTarget(part, bone));
        return transform == null ? null : transform.copy();
    }

    /**
     * Returns every whole-part transform as defensive copies.
     *
     * @return immutable part-transform map
     */
    public Map<String, Transform> parts() {
        return copyParts(parts);
    }

    /**
     * Returns every bone transform as defensive copies.
     *
     * @return immutable bone-transform map
     */
    public Map<BoneTarget, Transform> bones() {
        return copyBones(bones);
    }

    /**
     * Returns the whole-part target IDs.
     *
     * @return immutable target set
     */
    public Set<String> partNames() {
        return parts.keySet();
    }

    /**
     * Returns the bone targets.
     *
     * @return immutable target set
     */
    public Set<BoneTarget> boneTargets() {
        return bones.keySet();
    }

    /**
     * Returns whether this snapshot contains no transforms.
     *
     * @return {@code true} when both maps are empty
     */
    public boolean isEmpty() {
        return parts.isEmpty() && bones.isEmpty();
    }

    /**
     * One bone target, optionally scoped to a model part instance.
     *
     * @param part optional part ID
     * @param bone bone ID
     */
    public record BoneTarget(@Nullable String part, String bone) {

        /** Normalizes the optional part and requires a bone. */
        public BoneTarget {
            part = part == null || part.isBlank() ? null : part;
            if (bone == null || bone.isBlank()) {
                throw new IllegalArgumentException("Formula bone target must not be blank");
            }
        }
    }

    private static Map<String, Transform> copyParts(Map<String, Transform> source) {
        Objects.requireNonNull(source, "parts");
        Map<String, Transform> copy = new LinkedHashMap<>(source.size());
        source.forEach((part, transform) -> {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException("Formula part ID must not be blank");
            }
            copy.put(part, Objects.requireNonNull(transform, "transform").copy());
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<BoneTarget, Transform> copyBones(Map<BoneTarget, Transform> source) {
        Objects.requireNonNull(source, "bones");
        Map<BoneTarget, Transform> copy = new LinkedHashMap<>(source.size());
        source.forEach((target, transform) -> copy.put(
                Objects.requireNonNull(target, "target"),
                Objects.requireNonNull(transform, "transform").copy()
        ));
        return Collections.unmodifiableMap(copy);
    }
}
