package lib.kasuga.rendering.models.uml.typo.gltf;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Render-sampled glTF TRS animation player for a converted UML model. */
public final class GltfAnimationPoseDriver implements PoseDriver {
    private final Map<String, GltfAsset.AnimationClip> clips = new HashMap<>();
    private final GltfSampler sampler;
    private final AnimationPlayer<GltfAsset.AnimationClip> player;

    public GltfAnimationPoseDriver(ModelInstance instance) {
        Objects.requireNonNull(instance, "instance");
        if (!(instance.getModel().getModelData() instanceof GltfModelData gltf)) {
            throw new IllegalArgumentException("model was not created by GltfModelConverter");
        }
        for (GltfAsset.AnimationClip clip : gltf.asset().animations()) clips.put(clip.name(), clip);
        sampler = new GltfSampler(gltf);
        player = new AnimationPlayer<>(instance);
    }

    public boolean hasClip(String name) { return clips.containsKey(name); }
    public String currentClip() {
        GltfAsset.AnimationClip clip = player.currentData();
        return clip == null ? null : clip.name();
    }

    public boolean play(String name, boolean loop) {
        GltfAsset.AnimationClip clip = clips.get(name);
        if (clip == null) return false;
        player.play(sampler, clip, loop);
        return true;
    }

    public void stop() { player.stop(); }

    public void setSpeed(float speed) { player.setSpeed(speed); }

    @Override
    public void tick(float dt) { player.tick(dt); }

    @Override
    public void sample(float partialTick) { player.sample(partialTick); }
}