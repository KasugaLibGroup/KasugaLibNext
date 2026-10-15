package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.ParameterSpec;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link FsmPoseDriver} var-provider hook — the provider derives
 * parameters on the main thread (machine-internal write via {@code setInternal}) and projects the render
 * values into a {@code volatile} snapshot; the render thread injects them into the formula {@link Namespace}
 * so clip formula tracks read {@code query.*} per entity.
 *
 * <p>All three scenarios: (1) derived parameter written + render snapshot driving a formula clip,
 * (2) default no-provider falls back to identity without throwing, (3) two drivers
 * keep their parameter tables per-instance.
 */
class FsmPoseDriverVarProviderTest {

    private static final float DT = 1f / 20f;
    private static final float DELTA = 1e-2f;

    private static final ParameterSpec<Float> SPEED = ParameterSpec.<Float>parameter(
            Id.fromNamespaceAndPath("kasuga_lib", "var_provider/derived_speed"), Float.class, Codec.FLOAT)
            .defaultValue(0f)
            .externalWritable(false) // derived: machine-internal writers (provider) only
            .build();

    /** A formula clip whose only track rotates bone "b" by {@code query.speed / 60} degrees around Y. */
    private static AnimationClip speedClip() {
        return new AnimationClip(
                Id.fromNamespaceAndPath("kasuga_lib", "var_provider_speed"),
                12f,
                List.of(), List.of(), List.of(),
                List.of(new AnimationClip.FunctionTrack("b", AnimationClip.FunctionChannel.ROTATE,
                        "", "query.speed / 60", ""))
        );
    }

    /** A machine whose single active state references the formula clip (loop). */
    private static StateMachine<Object> machine() {
        return StateMachine.<Object>builder(new Object())
                .layer("loco", layer -> {
                    State<Object> spin = layer.state("spin").clip(ClipSampler.INSTANCE, speedClip(), true);
                    layer.initial(spin);
                })
                .build();
    }

    /** A {@link ModelInstance} whose skeleton carries a bone named {@code boneName} (so the pose can be read back). */
    private static ModelInstance instanceWithBone(String boneName) {
        Bone root = new Bone("root", new Transform(), null);
        Bone b = new Bone(boneName, new Transform(), null);
        b.setParent(root);
        root.setChildren(new Bone[]{b});
        Skeleton skeleton = new Skeleton(new Bone[]{root, b}, root, new Anchor[0], null, new Transform());
        Texture texture = new Texture("tex", 1f, 1f, null);
        Material material = new Material(new Texture[]{texture}, null);
        MaterialSet materialSet = new MaterialSet(texture, material);
        Model model = new Model(new Vertex[0], new Mesh[0], new Bone[]{root, b}, skeleton, materialSet,
                MeshMode.TRIANGLES, null, null);
        return new ModelInstance(model, null, null, null, new MaterialSetInstance(materialSet), null);
    }

    private static Quaternionf boneRotation(ModelInstance model, String boneName) {
        Bone bone = model.getSkeletonInstance().getSkeleton().getBoneMap().get(boneName);
        assertNotNull(bone, "skeleton must carry bone '" + boneName + "'");
        Transform t = model.getSkeletonInstance().getTransforms().get(bone);
        assertNotNull(t, "bone '" + boneName + "' must have been posed");
        return t.getRotation();
    }

    private static void assertRotationEquals(Quaternionf expected, Quaternionf actual, String label) {
        float dot = expected.normalize().dot(actual.normalize());
        // q and -q represent the same rotation; use |dot| to ignore sign flips from matrix extraction.
        assertTrue(Math.abs(dot) > 1f - DELTA,
                "rotation mismatch for " + label + ": expected " + expected + ", actual " + actual + " (dot " + dot + ")");
    }

    /**
     * Provider path: the provider writes the derived {@code SPEED} through {@code setInternal} (single source
     * of truth) and projects {@code speed → 540} into the render snapshot; {@code sample} injects it into the
     * formula namespace, so the clip's bone "b" rotates {@code query.speed / 60 = 9°}.
     */
    @Test
    void providerWritesDerivedAndProjectsIntoFormula() {
        StateMachine<Object> machine = machine();
        machine.declare(SPEED);
        FsmPoseDriver driver = new FsmPoseDriver(machine, instanceWithBone("b"), (m, dt, out) -> {
            m.setInternal(SPEED, 540f);
            out.put("speed", 540f);
        });

        driver.tick(DT);
        assertEquals(540f, machine.get(SPEED), "provider wrote the derived parameter via setInternal");

        driver.sample(0f); // render thread: assign snapshot → compose with formula ns → flush to skeleton
        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 9f, 0f), boneRotation(driver.model(), "b"), "b");
    }

    /**
     * Direct compose path (same as {@code FsmPoseDriverTest}): a warmed + assigned namespace is injected into
     * {@code compose(Blender, PoseTarget, float, Namespace)} — the render projection is supplied by the caller.
     */
    @Test
    void composeWithWarmedNamespaceInjectsFormulaVars() {
        StateMachine<Object> machine = machine();
        machine.declare(SPEED);
        FsmPoseDriver driver = new FsmPoseDriver(machine, ModelInstanceFixture.minimal());
        driver.tick(DT);

        Namespace ns = new Namespace(Code.ROOT_NAMESPACE);
        ClipSampler.INSTANCE.sample(speedClip(), 0f, ns); // warm-up: decode registers query.speed
        ns.assign("query.speed", 540f);

        Blender blender = new Blender();
        driver.compose(blender, driver.currentTarget(), 0f, ns);

        Blender.BoneAccum accum = blender.bones().get("b");
        assertNotNull(accum, "formula clip must pose bone 'b'");
        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 9f, 0f), accum.base.getRotation(), "b");
    }

    /**
     * Default (no provider): the formula variable is never assigned, so the track falls back to its identity
     * value (0°) — and sampling must not throw.
     */
    @Test
    void noProviderFallsBackToIdentityWithoutThrowing() {
        StateMachine<Object> machine = machine();
        machine.declare(SPEED);
        FsmPoseDriver driver = new FsmPoseDriver(machine, instanceWithBone("b")); // default NoopVarProvider

        driver.tick(DT);
        driver.sample(0f); // must not throw; query.speed unassigned → 0/60 = 0

        assertRotationEquals(new Quaternionf(), boneRotation(driver.model(), "b"), "b (no provider)");
    }

    /**
     * Frame-rate smoothing: the provider integrates on the main thread at 20 Hz, so the render thread must
     * interpolate the projected vars between the previous and current tick by {@code partialTick}. With
     * tick1→speed=0, tick2→speed=60, {@code sample(0.5)} must inject 30 — not 60 — or the fan would visibly
     * step at the game-tick rate.
     */
    @Test
    void sampleInterpolatesVarsBetweenTicks() {
        StateMachine<Object> machine = machine();
        machine.declare(SPEED);
        float[] ramp = {0f, 60f};
        int[] call = {0};
        FsmPoseDriver driver = new FsmPoseDriver(machine, instanceWithBone("b"), (m, dt, out) -> {
            float speed = ramp[Math.min(call[0]++, 1)];
            m.setInternal(SPEED, speed);
            out.put("speed", speed);
        });

        driver.tick(DT); // tick 1 → speed 0
        driver.tick(DT); // tick 2 → speed 60
        driver.sample(0.5f); // half-way into tick 2 → speed 30 → rotation 30/60 = 0.5°

        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 0.5f, 0f), boneRotation(driver.model(), "b"),
                "b (interpolated 30°/60 → 0.5°)");

        // partialTick=1 lands exactly on the current tick value (60 → 1°)
        driver.sample(1f);
        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 1f, 0f), boneRotation(driver.model(), "b"),
                "b (partialTick=1 → current tick)");
    }

    /**
     * Per-instance isolation: each driver owns its machine + provider + namespace/snapshot, so two entities
     * with different derived speeds render different rotations (180/60=3° vs 540/60=9°).
     */
    @Test
    void twoDriversKeepProjectionsPerInstance() {
        StateMachine<Object> machineA = machine();
        StateMachine<Object> machineB = machine();
        machineA.declare(SPEED);
        machineB.declare(SPEED);

        FsmPoseDriver driverA = new FsmPoseDriver(machineA, instanceWithBone("b"), (m, dt, out) -> {
            m.setInternal(SPEED, 180f);
            out.put("speed", 180f);
        });
        FsmPoseDriver driverB = new FsmPoseDriver(machineB, instanceWithBone("b"), (m, dt, out) -> {
            m.setInternal(SPEED, 540f);
            out.put("speed", 540f);
        });

        driverA.tick(DT);
        driverB.tick(DT);
        driverA.sample(0f);
        driverB.sample(0f);

        assertEquals(180f, machineA.get(SPEED));
        assertEquals(540f, machineB.get(SPEED));
        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 3f, 0f), boneRotation(driverA.model(), "b"), "A.b");
        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 9f, 0f), boneRotation(driverB.model(), "b"), "B.b");
    }
}