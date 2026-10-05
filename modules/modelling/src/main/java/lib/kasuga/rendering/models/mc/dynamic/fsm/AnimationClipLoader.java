package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.registration.data_driven.structure.ContentStructure;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes data-driven animation clip files of the reload domain, and registers their clips. Like
 * {@link StateMachineDefinitionLoader} it is a pure file-layer component: no listener and no clear of
 * its own — {@link ReloadOrchestrator} owns the reload cycle and turns the returned diagnostics into
 * paired log + bucket entries.
 *
 * <p>File shape: a wrapper object that carries the top-level key {@value #FIELD_ANIMATION_CLIPS},
 * mapping to an array of clips — {@code {"animation_clips": [ <clip>, ... ]}}. Each element is
 * decoded with {@link AnimationClip#CODEC}, which carries the clip's {@code id} itself (a required
 * {@code id} field, spelled with the same {@link lib.kasuga.rendering.models.uml.dynamic.fsm.Id} the
 * {@code states[].clip} reference uses), so no separate id key is extracted and an element is exactly
 * a clip. Keys the {@code AnimationClip} record does not name are ignored by the codec, so an author
 * may annotate an element without breaking the decode.
 *
 * <p>The file is only ever reached through an index manifest's {@code on_reload} array — there is no
 * directory glob for {@code animation_clips/}, unlike {@code state_machines/}.
 *
 * <p>Failure isolation mirrors the state machine decoder: a shape violation (non-object body, missing
 * top-level key, non-array value) rejects the file; a malformed array element drops only that element
 * while its siblings still load. An extra top-level key is not this decoder's concern — the reload
 * orchestrator reports it through its unknown-key path.
 *
 * <p>A decoded clip is registered through {@link #register(FsmAnimationClips, AnimationClip)}, which
 * writes the <em>reload</em> bucket ({@link FsmAnimationClips#registerResource}) so a SCRIPT clip of
 * the same id survives the reload ("script wins"); the file path never uses
 * {@link FsmAnimationClips#register} (the code/scripting bucket).
 */
public final class AnimationClipLoader {

    /** The single top-level key every animation clip file is allowed to carry. */
    public static final String FIELD_ANIMATION_CLIPS = "animation_clips";

    /** The expected file shape, quoted verbatim in decode diagnostics. */
    private static final String EXPECTED_SHAPE = "{\"" + FIELD_ANIMATION_CLIPS + "\": [ <clip>, ... ]}";

    /**
     * Outcome of decoding one animation clip file.
     *
     * @param clips  the clips that decoded, in file order; empty when the whole file was rejected
     * @param errors human-readable diagnostics for a rejected file or a skipped array element; empty
     *               when the file decoded cleanly
     */
    public record DecodedFile(List<AnimationClip> clips, List<String> errors) {}

    /**
     * Decodes one animation clip file into its clips. The top-level shape is strict, mirroring the
     * state machine decoder and the content-file top-level field check of the registration domain: the
     * body must be an object carrying {@value #FIELD_ANIMATION_CLIPS}, holding an array. A violation
     * rejects the file. Elements are decoded one by one with {@link AnimationClip#CODEC}
     * so a single malformed element is skipped without dropping its siblings.
     *
     * @param json the parsed file body
     * @return the successfully decoded clips plus any diagnostics
     */
    public static DecodedFile decodeFile(JsonElement json) {
        List<String> errors = new ArrayList<>();
        if (json == null || !json.isJsonObject()) {
            errors.add("expected an object of shape " + EXPECTED_SHAPE + ", got "
                    + ContentStructure.describe(json));
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        JsonObject root = json.getAsJsonObject();
        if (!root.has(FIELD_ANIMATION_CLIPS)) {
            errors.add("missing top-level key '" + FIELD_ANIMATION_CLIPS + "' (expected shape "
                    + EXPECTED_SHAPE + ")");
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        ContentStructure.Field field = ContentStructure.field(root, FIELD_ANIMATION_CLIPS);
        for (ContentStructure.Issue issue : field.issues()) {
            if (issue.kind() == ContentStructure.Kind.VALUE_NOT_ARRAY) {
                errors.add("top-level key '" + FIELD_ANIMATION_CLIPS + "' must be an array, got "
                        + ContentStructure.describe(issue.actual()));
                return new DecodedFile(List.of(), List.copyOf(errors));
            }
            if (issue.kind() == ContentStructure.Kind.ELEMENT_NOT_OBJECT) {
                errors.add(FIELD_ANIMATION_CLIPS + "[" + issue.index() + "] must be an object, got "
                        + ContentStructure.describe(issue.actual()) + "; element skipped");
            }
        }
        List<AnimationClip> decoded = new ArrayList<>();
        for (ContentStructure.Element element : field.elements()) {
            AnimationClip.CODEC.parse(JsonOps.INSTANCE, element.body())
                    .resultOrPartial(error -> errors.add(FIELD_ANIMATION_CLIPS + "[" + element.fileIndex()
                            + "] failed to decode: " + error + "; element skipped"))
                    .ifPresent(decoded::add);
        }
        return new DecodedFile(List.copyOf(decoded), List.copyOf(errors));
    }

    /**
     * Registers one decoded clip into the reload bucket of {@code clips}. Always the RESOURCE path:
     * {@link FsmAnimationClips#registerResource} does not clobber a SCRIPT clip of the same id, so a
     * file clip can never shadow one registered by a script, a content test or a game test. A
     * {@code null} bucket or clip is a no-op.
     *
     * @param clips the bucket to register into
     * @param clip  the clip to register; {@link ClipSampler} is the sampler for its data
     */
    public static void register(FsmAnimationClips clips, AnimationClip clip) {
        if (clips == null || clip == null) {
            return;
        }
        clips.registerResource(clip.id(), ClipSampler.INSTANCE, clip);
    }

    private AnimationClipLoader() {}
}
