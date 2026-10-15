package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-driven Clip-as-State: a state definition's {@code clip} field resolves against
 * {@link FsmAnimationClips} (via the factory), and the built machine plays the referenced clip on its
 * own clock. Covers the object form ({@code loop}) and the plain-string form, plus unknown-clip degradation.
 */
class ClipStateDataDrivenTest {

    private static final float DT = 1f / 20f;

    private static AnimationClip wheelClip() {
        return new AnimationClip(Id.parse("test:wheel"), 1f,
                List.of(new AnimationClip.BoneTrack("root", List.of(
                        new AnimationClip.Keyframe(0f,
                                new TransformDefinition(new Vector3f(), new Vector3f(), new Vector3f(1f, 1f, 1f)), null),
                        new AnimationClip.Keyframe(1f,
                                new TransformDefinition(new Vector3f(), new Vector3f(0f, 180f, 0f), new Vector3f(1f, 1f, 1f)), null)))),
                List.of(), List.of(), List.of());
    }

    private static StateMachineDefinition decode(String json) {
        return StateMachineDefinition.CODEC
                .decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .resultOrPartial(error -> {
                    throw new IllegalStateException("definition decode failed: " + error);
                }).orElseThrow().getFirst();
    }

    private static StateMachine<Object> build(FsmRegistries regs, String json, PoseSink sink) {
        return new DefinitionStateMachineFactory<Object>(new FsmFunctionLibrary(), new StateVarRegistry(), regs.clips())
                .build(new Object(), decode(json), sink);
    }

    static final class RecordingSink implements PoseSink {
        Blender last;

        @Override
        public void apply(Blender blender) {
            last = blender;
        }
    }

    @Test
    void objectFormClipPlaysThroughTheMachine() {
        FsmRegistries regs = FsmRegistries.create();
        regs.clips().register(Id.parse("test:wheel"), ClipSampler.INSTANCE, wheelClip());
        RecordingSink sink = new RecordingSink();
        StateMachine<Object> machine = build(regs, """
                { "id": "test:clip_machine", "layers": [ {
                    "id": "l", "mode": "base", "initial_state": "spin",
                    "states": [ { "id": "spin", "clip": { "id": "test:wheel", "loop": true } } ],
                    "transitions": []
                } ] }
                """, sink);

        State<Object> spin = machine.layer("l").active();
        assertTrue(spin.hasClip(), "the factory must resolve the clip reference onto the state");
        assertTrue(spin.clipLoop(), "loop=true must survive resolution");

        machine.tick(DT);
        machine.tick(DT);
        assertTrue(sink.last.bones().containsKey("root"), "the clip pose must be flushed into the blender");
        assertTrue(sink.last.bones().get("root").base.getRotation().angle() > 0f,
                "the resolved clip must be sampled (not the static identity)");
    }

    @Test
    void plainStringClipResolvesWithLoopFalse() {
        FsmRegistries regs = FsmRegistries.create();
        regs.clips().register(Id.parse("test:wheel"), ClipSampler.INSTANCE, wheelClip());
        StateMachine<Object> machine = build(regs, """
                { "id": "test:clip_machine", "layers": [ {
                    "id": "l", "mode": "base", "initial_state": "spin",
                    "states": [ { "id": "spin", "clip": "test:wheel" } ],
                    "transitions": []
                } ] }
                """, null);

        State<Object> spin = machine.layer("l").active();
        assertTrue(spin.hasClip());
        assertTrue(!spin.clipLoop(), "the plain-string clip form defaults loop to false");
    }

    @Test
    void unknownClipDegradesToStaticPose() {
        FsmRegistries regs = FsmRegistries.create();
        RecordingSink sink = new RecordingSink();
        // the referenced clip is never registered → the state builds but carries no clip (static pose only)
        StateMachine<Object> machine = build(regs, """
                { "id": "test:clip_machine", "layers": [ {
                    "id": "l", "mode": "base", "initial_state": "spin",
                    "states": [ { "id": "spin", "clip": "test:missing", "pose": { "morphs": { "m": 0.25 } } } ],
                    "transitions": []
                } ] }
                """, sink);

        State<Object> spin = machine.layer("l").active();
        assertTrue(!spin.hasClip(), "an unresolved clip must degrade to static pose");
        machine.tick(DT);
        assertNotNull(sink.last.morphs().get("m"), "the static pose still applies");
        assertNull(sink.last.bones().get("root"), "no clip sample lands in the blender");
        assertEquals(0.25f, sink.last.morphs().get("m").value(), 1e-4f);
    }
}