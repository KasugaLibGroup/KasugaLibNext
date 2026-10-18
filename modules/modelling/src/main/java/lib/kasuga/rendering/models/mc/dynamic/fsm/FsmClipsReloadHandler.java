package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.reload.Decoded;
import lib.kasuga.registration.data_driven.reload.ReloadHandler;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;

import java.util.ArrayList;
import java.util.List;

/**
 * The reload-domain handler of animation clips — the {@code animation_clips} half of
 * {@link ReloadOrchestrator}, moved out of the orchestrator so the orchestrator no longer knows the type.
 *
 * <p>Index-only: {@link #globDirectories()} keeps the default empty set, so a clip file that no
 * manifest's {@code on_reload} array lists is never read (unlike {@code state_machines/}, there is no
 * {@code animation_clips/} glob). Registration delegates to
 * {@link AnimationClipLoader#register(FsmAnimationClips, AnimationClip)}, which writes the RESOURCE half
 * of the clip bucket and preserves "script wins". The decoder
 * {@link AnimationClipLoader#decodeFile} stays the pure file decoder and is not moved by this refactor.
 */
public final class FsmClipsReloadHandler implements ReloadHandler<AnimationClip> {

    private final FsmAnimationClips clips;

    /**
     * @param clips the clip bucket this handler populates (reload-sourced half)
     */
    public FsmClipsReloadHandler(FsmAnimationClips clips) {
        this.clips = clips;
    }

    @Override
    public String typeName() {
        return AnimationClipLoader.FIELD_ANIMATION_CLIPS;
    }

    @Override
    public String describeFile() {
        return "animation clip file";
    }

    @Override
    public void clearBucket() {
        // Only the reload-sourced half: script clips survive a reload ("script wins").
        clips.clearResource();
    }

    @Override
    public Decoded<AnimationClip> decode(JsonObject root) {
        AnimationClipLoader.DecodedFile decoded = AnimationClipLoader.decodeFile(root);
        List<Decoded.Entry<AnimationClip>> entries = new ArrayList<>(decoded.clips().size());
        for (AnimationClip clip : decoded.clips()) {
            entries.add(new Decoded.Entry<>(clip.id().toString(), clip));
        }
        return new Decoded<>(entries, decoded.errors());
    }

    @Override
    public void register(AnimationClip payload) {
        AnimationClipLoader.register(clips, payload);
    }
}
