package test.kasuga.modelling.demo.nebula;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationTimeline;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraAnimationPlayer;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Bounded local lightning simulation. One shared Kasuga clock drives cloud motion and strike decay. */
public final class NebulaSimulation {
    public static final int MAX_BOLTS = 2;
    public static final int SEGMENTS_PER_BOLT = 32;
    public static final float MAX_FREQUENCY = 2;
    public static final float DEFAULT_FREQUENCY = .25f;
    public static final float LIFETIME = .95f;
    private final AnimationTimeline timeline = new AnimationTimeline();
    private WorldCameraView cameraBase;
    private final CameraAnimationPlayer lensPlayer = new CameraAnimationPlayer(() -> cameraBase);
    private NebulaWarpSequence warpSequence;
    private final ArrayList<Bolt> bolts = new ArrayList<>();
    private final List<Bolt> view = java.util.Collections.unmodifiableList(bolts);
    private final long seed;
    private Random random;
    private float frequency = DEFAULT_FREQUENCY;
    private double credit;
    private long emitted;
    private float warpPhaseOffset;

    public NebulaSimulation(long seed) { this.seed = seed; reset(); }

    public void reset() {
        random = new Random(seed);
        bolts.clear();
        credit = 0;
        emitted = 0;
        warpPhaseOffset = 0;
        frequency = DEFAULT_FREQUENCY;
        timeline.play(86_400, true);
        lensPlayer.follow(NebulaFlight.ENDING_LENS, timeline);
        warpSequence = new NebulaWarpSequence(this::warpCycleTime);
    }

    public void frequency(float hz) {
        if (!Float.isFinite(hz)) throw new IllegalArgumentException("Frequency must be finite");
        frequency = Math.clamp(hz, 0, MAX_FREQUENCY);
        if (frequency == 0) credit = 0;
    }

    public float frequency() { return frequency; }
    public long emitted() { return emitted; }
    public List<Bolt> bolts() { return view; }
    public float time(float partialTick) { return timeline.snapshot().sampleTime(partialTick); }
    public float warpTime(float partialTick) { return time(partialTick) + warpPhaseOffset; }

    WorldCameraView camera(NebulaFlight.Pose craft, NebulaFlight.WarpState warp, int width, int height,
                           float azimuth, float elevation, float partialTick) {
        cameraBase = NebulaFlight.cameraBase(craft, warp, time(partialTick), width, height, azimuth, elevation);
        return lensPlayer.sample(partialTick);
    }

    public boolean triggerWarp() {
        warpSequence.update(0);
        var phase = warpSequence.phase();
        if (!phase.canTrigger) return false;
        double skip = Math.nextUp(5.15f) - warpCycleTime();
        if (phase == NebulaWarpSequence.Phase.READY) skip += NebulaFlight.WARP_CYCLE_SECONDS;
        warpPhaseOffset += (float) skip;
        warpSequence.update(0);
        return true;
    }

    private double warpCycleTime() { return warpTime(1) % NebulaFlight.WARP_CYCLE_SECONDS; }
    NebulaWarpSequence.Phase warpPhase() { return warpSequence.phase(); }

    public void paused(boolean paused) { if (paused) timeline.pause(); else timeline.resume(); }

    public void tick() {
        advance(.05f,1.0/20);
    }

    /** Offline capture owns this clock instead of client ticks; the no-argument form keeps the 30 FPS default. */
    public void captureFrame() {
        captureFrame(30);
    }

    /** Advance one offline capture frame at the requested output frame rate. */
    public void captureFrame(float frameRate) {
        if (!Float.isFinite(frameRate) || frameRate <= 0) {
            throw new IllegalArgumentException("Capture frame rate must be finite and positive");
        }
        advance(1f / frameRate, 1.0 / frameRate);
    }

    private void advance(float delta, double seconds) {
        if (!timeline.isPlaying()) return;
        timeline.tick(delta);
        warpSequence.update(delta);
        float now = time(1);
        bolts.removeIf(bolt -> now - bolt.born() > LIFETIME + .05f);
        credit += frequency * seconds;
        if (credit + 1e-9 >= 1) {
            credit -= 1;
            if (bolts.size() == MAX_BOLTS) bolts.removeFirst();
            bolts.add(strike(now));
            emitted++;
        }
    }

    private Bolt strike(float now) {
        var start = insideCloud(new Vector3f(signed(11), signed(6), signed(7)));
        var end = emitted % 3 == 0 ? NebulaFlight.sample(now).position()
                : insideCloud(new Vector3f(-start.x * .55f + signed(4), -start.y * .6f + signed(3), signed(7)));
        var segments = new ArrayList<Segment>(SEGMENTS_PER_BOLT);
        var points = new ArrayList<Vector3f>();
        var direction = new Vector3f(end).sub(start);
        float length = direction.length();
        if (length > .00001f) direction.div(length);
        else direction.set(0,0,1);
        var side = new Vector3f(direction).cross(0,1,0);
        if (side.lengthSquared() < .00001f) side.set(1,0,0);
        side.normalize();
        var up = new Vector3f(side).cross(direction).normalize();
        float phase = random.nextFloat() * 6.283185f;
        float jitter = Math.min(.25f,length/22f*.22f);
        points.add(start);
        for (int i = 1; i <= 22; i++) {
            float t = i / 22f;
            var point = new Vector3f(start).lerp(end, t);
            if (i < 22) {
                float taper = (float) Math.sin(Math.PI*t);
                point.fma(taper*length*.04f*(float) Math.sin(t*11+phase)+signed(jitter),side)
                        .fma(taper*length*.025f*(float) Math.sin(t*17+phase*.7f)+signed(jitter),up);
            }
            insideCloud(point);
            segments.add(new Segment(new Vector3f(points.getLast()), point, 1));
            points.add(point);
        }
        for (int branch = 0; branch < 2; branch++) {
            var base = new Vector3f(points.get(7 + branch * 6));
            var tip = insideCloud(new Vector3f(base).add(signed(4), signed(4), signed(3)));
            for (int i = 1; i <= 5; i++) {
                var point = new Vector3f(points.get(7 + branch * 6)).lerp(tip, i / 5f);
                if (i < 5) point.add(signed(.10f), signed(.10f), signed(.10f));
                insideCloud(point);
                segments.add(new Segment(new Vector3f(base), point, .55f));
                base = point;
            }
        }
        return new Bolt(now, new Vector3f(start).lerp(end, .5f), List.copyOf(segments));
    }

    private float signed(float radius) { return (random.nextFloat() * 2 - 1) * radius; }

    private static Vector3f insideCloud(Vector3f point) {
        float radius = (float) Math.sqrt(point.x * point.x / (21 * 21) + point.y * point.y / (12 * 12)
                + point.z * point.z / (15 * 15));
        if (radius > .85f) point.mul(.85f / radius);
        return point;
    }

    public record Segment(Vector3f start, Vector3f end, float width) {}
    public record Bolt(float born, Vector3f center, List<Segment> segments) {
        public float strength(float time) {
            float age = time - born;
            if (age < 0 || age > LIFETIME) return 0;
            // A bright leader, a short re-strike, then a smooth ionized afterglow.
            return (float) ((Math.exp(-age * 14) + .85 * Math.exp(-Math.pow((age - .17) / .04, 2))
                    + .45 * Math.exp(-Math.pow((age - .31) / .055, 2)) + .14 * Math.exp(-age * 3))
                    * Math.max(0, 1 - age / LIFETIME));
        }
    }
}
