package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the data-driven fan clip file against the clip the code fixture builds, so the two stay
 * field-by-field identical.
 *
 * <p>The contentTesting clip factory ({@code FanAnimationClipFactory.fanClip()}) is not on the test
 * classpath, so {@link #expectedClip()} mirrors it here: the same duration (12s) and the same three
 * formula tracks (group {@code y=query.angle}, fan {@code y=11 * query.angle}, cover
 * {@code z=sin(rad(2 * query.angle)) * 30}), only under the data-driven id. If the code clip changes,
 * this mirror changes with it — and a drift of the shipped file then fails {@link
 * #clipFileDecodesToTheCodeClip()}.
 */
class FanDataDrivenClipTest {

    private static final Id CLIP_ID = Id.fromNamespaceAndPath("kasuga_lib", "fan_fsm_data_driven");

    @Test
    void clipFileDecodesToTheCodeClip() {
        AnimationClipLoader.DecodedFile decoded =
                AnimationClipLoader.decodeFile(ModellingContentFiles.readJson(ModellingContentFiles.CLIP));

        assertEquals(List.of(), decoded.errors(), "the shipped clip file must decode without errors");
        assertEquals(1, decoded.clips().size());
        assertEquals(expectedClip(), decoded.clips().get(0),
                "the shipped clip must match the code clip (id swapped for the data-driven one)");
    }

    /**
     * The shipped file is the codec's own encoding: encoding the code clip and comparing against the
     * raw file element freezes the file's exact shape (key names, omitted-default axes, ordering) so a
     * hand edit that still decodes but changes the canonical form is caught.
     */
    @Test
    void clipFileIsInCanonicalEncodedShape() {
        JsonElement canonical = AnimationClip.CODEC.encodeStart(JsonOps.INSTANCE, expectedClip()).getOrThrow();
        JsonObject root = ModellingContentFiles.readJson(ModellingContentFiles.CLIP).getAsJsonObject();
        JsonArray clips = root.getAsJsonArray(AnimationClipLoader.FIELD_ANIMATION_CLIPS);

        assertEquals(canonical, clips.get(0),
                "the clip file must be exactly what AnimationClip.CODEC encodes");
    }

    /** The codec round-trip the file loader relies on: decode(encode(clip)) == clip. */
    @Test
    void animationClipCodecRoundTrips() {
        AnimationClip clip = expectedClip();
        JsonElement encoded = AnimationClip.CODEC.encodeStart(JsonOps.INSTANCE, clip).getOrThrow();

        assertEquals(clip, AnimationClip.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    /** Mirror of {@code FanAnimationClipFactory.fanClip()} with the data-driven id. */
    private static AnimationClip expectedClip() {
        return new AnimationClip(
                CLIP_ID,
                12f,
                List.of(), List.of(), List.of(),
                List.of(
                        new AnimationClip.FunctionTrack("group", AnimationClip.FunctionChannel.ROTATE,
                                "", "query.angle", ""),
                        new AnimationClip.FunctionTrack("fan", AnimationClip.FunctionChannel.ROTATE,
                                "", "11 * query.angle", ""),
                        new AnimationClip.FunctionTrack("cover", AnimationClip.FunctionChannel.ROTATE,
                                "", "", "sin(rad(2 * query.angle)) * 30")
                )
        );
    }
}
