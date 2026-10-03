package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.dynamic.morph.MorphInstance;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link PoseSink} that flushes a {@link Blender} into the existing {@link MorphInstance} /
 * {@link SkeletonInstance} / {@link MaterialSetInstance}. The host calls {@code ModelInstance.update()}
 * once afterwards; this sink only writes.
 *
 * <p><b>Channel reset:</b> the underlying {@link MorphInstance}/{@link SkeletonInstance} state is persistent
 * (writes accumulate; nothing auto-clears per frame). To keep the displayed pose equal to the FSM's current
 * pose, the sink records the morph/bone channels it wrote last frame and <em>neutralizes</em> any it does not
 * write this frame — morphs via {@link MorphInstance#deactivateMorph} and bones via
 * {@link SkeletonInstance#reset(String)} (which drops the override and restores the bind pose). Without this,
 * a channel posed by a previous state would linger indefinitely. Frames are intentionally not reset
 * ({@code setCurrentMatFrame} is idempotent; there is no neutral frame index).
 *
 * <p>For the reset to reach the sink on a frame whose pose is empty (so the {@link Blender} is empty too),
 * {@link StateMachine#tick(float)} flushes whenever a sink is attached — not only when the blender is non-empty.
 */
public final class ModelInstancePoseSink implements PoseSink {

    private final ModelInstance model;
    private final MaterialResolver materials;
    private final Set<Object> lastMorphs = new HashSet<>();
    private final Set<String> lastBones = new HashSet<>();
    private final Blender poseBlender = new Blender();

    public ModelInstancePoseSink(ModelInstance model, MaterialResolver materials) {
        this.model = Objects.requireNonNull(model, "model");
        this.materials = materials != null ? materials : MaterialResolver.forInstance(model);
    }

    public ModelInstancePoseSink(ModelInstance model) {
        this(model, MaterialResolver.forInstance(model));
    }

    public ModelInstance model() {
        return model;
    }

    public MaterialResolver materials() {
        return materials;
    }

    @Override
    public void apply(Blender blender) {
        flush(blender);
    }

    /**
     * Flush a single {@link Pose} as a BASE layer (weight 1, full bone mask) through the shared
     * write path. The unified animation write end — every animation {@code PoseDriver} (e.g.
     * {@code AnimationPlayer}) converges here instead of writing the skeleton directly.
     *
     * <p>Even an empty (or null) pose reaches the sink so the channel-reset can neutralize channels
     * posed last frame but absent this frame (the same contract {@link #apply(Blender)} has).
     */
    public void applyPose(Pose pose) {
        if (pose == null) {
            pose = Pose.empty();
        }
        poseBlender.reset();
        poseBlender.applyLayer(BlendMode.BASE, pose, 1f, BoneMask.all());
        flush(poseBlender);
    }

    /**
     * Shared write core: resolve the {@link Blender}'s accumulators into skeleton / morph / material
     * writes, with channel reset (channels posed last frame but absent this frame are neutralized).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void flush(Blender blender) {
        MorphInstance morph = model.getMorph();
        SkeletonInstance skeleton = model.getSkeletonInstance();
        MaterialSetInstance materialSet = model.getMaterialInstance();

        Set<Object> morphsNow = blender.morphs().keySet();
        // morph channels posed last frame but absent this frame → neutral (value 0)
        for (Object id : lastMorphs) {
            if (!morphsNow.contains(id)) {
                morph.deactivateMorph(id);
            }
        }
        for (Map.Entry<Object, Blender.MorphAccum> entry : blender.morphs().entrySet()) {
            Blender.MorphAccum accum = entry.getValue();
            morph.activateMorph(entry.getKey(), accum.value(), accum.factor());
        }

        Set<String> bonesNow = blender.bones().keySet();
        // bone channels posed last frame but absent this frame → bind pose (drop the override)
        for (String name : lastBones) {
            if (!bonesNow.contains(name)) {
                skeleton.reset(name);
            }
        }
        for (Map.Entry<String, Blender.BoneAccum> entry : blender.bones().entrySet()) {
            for (Blender.BoneWrite write : Blender.resolveBoneWrites(entry.getValue())) {
                applyBoneWrite(entry.getKey(), write, skeleton);
            }
        }

        if (materialSet != null) {
            for (Map.Entry<Object, Blender.FrameAccum> entry : blender.frames().entrySet()) {
                int frame = entry.getValue().frame();
                if (frame < 0) {
                    continue;
                }
                Material material = materials.resolve(entry.getKey());
                if (material != null) {
                    materialSet.setCurrentMatFrame(material, frame);
                }
            }
        }

        // advance the recorded channels to this frame
        lastMorphs.clear();
        lastMorphs.addAll(morphsNow);
        lastBones.clear();
        lastBones.addAll(bonesNow);
    }

    /** Dispatch one resolved bone write by its {@link ApplyMode} onto the skeleton. */
    private static void applyBoneWrite(String name, Blender.BoneWrite write, SkeletonInstance skeleton) {
        switch (write.mode()) {
            case REPLACE -> skeleton.transform(name, write.transform());
            case MULTIPLY -> skeleton.mulTransform(name, write.transform());
            case ADD -> skeleton.offset(name, write.transform().getPosition());
        }
    }
}
