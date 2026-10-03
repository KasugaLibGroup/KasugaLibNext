package lib.kasuga.rendering.models.uml.dynamic.animation.function;

public final class RampMath {
    public static final float T_REF = 1.5f;

    public record Ramp(float fromV, float toV, float startAngle, float startElapsed, float delta, float decelTime) {
        public static Ramp sCurve(float fromV, float toV, float startAngle, float startElapsed) {
            return new Ramp(fromV, toV, startAngle, startElapsed, 0f, 0f);
        }
        public static Ramp easeOut(float fromV, float startAngle, float startElapsed, float delta, float decelTime) {
            return new Ramp(fromV, 0f, startAngle, startElapsed, delta, decelTime);
        }
    }

    /** ease_in_out_cubic: e(0)=0, e(1)=1, zero derivative at both ends. */
    public static float easeInOutCubic(float u) {
        u = Math.max(0f, Math.min(1f, u));
        return u < 0.5f ? 4f * u * u * u : 1f - (float) Math.pow(-2f * u + 2f, 3) / 2f;
    }

    /** Indefinite integral of ease_in_out_cubic over [0,u]: E(0)=0, E(1)=0.5. */
    public static float easeInOutCubicIntegral(float u) {
        u = Math.max(0f, Math.min(1f, u));
        return u <= 0.5f ? u * u * u * u : u + (float) Math.pow(u - 1f, 4) - 0.5f;
    }

    /** S-curve velocity at elapsed t (seconds since ramp start). */
    public static float sCurveVelocity(float v0, float v1, float t) {
        return v0 + (v1 - v0) * easeInOutCubic(t / T_REF);
    }

    /** S-curve angle at elapsed t: θ = θ0 + v0·t + (v1−v0)·T·E(t/T). */
    public static float sCurveAngle(float theta0, float v0, float v1, float t) {
        float T = T_REF;
        float u = t / T;
        return theta0 + v0 * t + (v1 - v0) * T * easeInOutCubicIntegral(u);
    }

    /** Arrival angle of an S-curve ramp: θ0 + T·(v0+v1)/2 (average velocity × T). */
    public static float sCurveArrivalAngle(float theta0, float v0, float v1) {
        return theta0 + T_REF * (v0 + v1) / 2f;
    }

    /**
     * Plan the decel-to-stop ramp: Δ = round(θ0 + 0.75·v0·T_REF) − θ0 (integer landing, always forward,
     * ≥ 0); T' = Δ / (0.75·v0). If v0 ≤ 0, Δ=0, T'=0 (already stopped).
     */
    public static float[] easeOutPlan(float theta0, float v0) {
        if (v0 <= 0f) return new float[]{0f, 0f};
        float target = (float) Math.round(theta0 + 0.75f * v0 * T_REF);
        float delta = target - theta0;
        float decelTime = delta / (0.75f * v0);
        return new float[]{delta, decelTime};
    }

    /** ease_out velocity: v0·(1 − u³), u = t/T', clamped to [0,1]. */
    public static float easeOutVelocity(float v0, float decelTime, float t) {
        if (decelTime <= 0f) return 0f;
        float u = Math.max(0f, Math.min(1f, t / decelTime));
        return v0 * (1f - u * u * u);
    }

    /** ease_out angle: θ0 + (4Δ/3)·(u − u⁴/4), u = t/T' clamped; completes to θ0+Δ. */
    public static float easeOutAngle(float theta0, float v0, float delta, float decelTime, float t) {
        if (decelTime <= 0f) return theta0 + delta;
        float u = Math.max(0f, Math.min(1f, t / decelTime));
        return theta0 + (4f * delta / 3f) * (u - u * u * u * u / 4f);
    }

    /** Angle at elapsed t for a Ramp, including post-completion continuation (cruise at toV). */
    public static float angleAt(Ramp ramp, float t) {
        float toV = ramp.toV();
        if (toV == 0f) {
            // decel to stop: complete to the integer landing, then frozen
            if (ramp.fromV() <= 0f) return ramp.startAngle();
            return easeOutAngle(ramp.startAngle(), ramp.fromV(), ramp.delta(), ramp.decelTime(), t);
        }
        // gear target: S-curve until T, then continue linearly at toV
        float T = T_REF;
        if (t <= T) return sCurveAngle(ramp.startAngle(), ramp.fromV(), toV, t);
        return sCurveArrivalAngle(ramp.startAngle(), ramp.fromV(), toV) + toV * (t - T);
    }

    /** Velocity at elapsed t for a Ramp (cruise after completion). */
    public static float velocityAt(Ramp ramp, float t) {
        float toV = ramp.toV();
        if (toV == 0f) return ramp.fromV() <= 0f ? 0f : easeOutVelocity(ramp.fromV(), ramp.decelTime(), t);
        return t <= T_REF ? sCurveVelocity(ramp.fromV(), toV, t) : toV;
    }

    /** Arrival angle of the target cruise (what the BE persists as angleDeg). */
    public static float arrivalAngle(Ramp ramp) {
        return ramp.toV() == 0f
                ? ramp.startAngle() + ramp.delta()
                : sCurveArrivalAngle(ramp.startAngle(), ramp.fromV(), ramp.toV());
    }
}