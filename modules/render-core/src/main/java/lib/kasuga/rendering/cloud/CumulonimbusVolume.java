package lib.kasuga.rendering.cloud;

import java.util.Objects;

/** Portable, tick-driven cloud instance. Rendering samples it without advancing its clock. */
public final class CumulonimbusVolume implements AutoCloseable {
    private CloudPose previous, current;
    private CloudSettings settings;
    private double time, previousTime, windX, windY, windZ;
    private boolean closed;

    public CumulonimbusVolume(CloudPose pose, CloudSettings settings) {
        previous = current = Objects.requireNonNull(pose, "pose");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public synchronized void tick(double seconds) {
        requireOpen();
        CloudPose.finite(seconds);
        if (seconds < 0) throw new IllegalArgumentException("Cloud time cannot move backwards");
        double nextTime = time + seconds;
        CloudPose.finite(nextTime);
        CloudPose next = current.moveBy(windX * seconds, windY * seconds, windZ * seconds);
        previous = current; current = next;
        previousTime = time; time = nextTime;
    }

    public synchronized Frame sample(float partialTick) {
        if (!Float.isFinite(partialTick)) throw new IllegalArgumentException("partialTick must be finite");
        float alpha = Math.clamp(partialTick, 0, 1);
        return new Frame(previous.interpolate(current, alpha), settings, previousTime + (time - previousTime) * alpha);
    }

    public synchronized CloudPose pose() { return current; }
    public synchronized void pose(CloudPose value) { requireOpen(); previous = current = Objects.requireNonNull(value); }
    public synchronized void moveTo(double x, double y, double z) { pose(current.moveTo(x, y, z)); }
    public synchronized void moveBy(double x, double y, double z) { pose(current.moveBy(x, y, z)); }
    public synchronized void rotateBy(float yaw) { pose(current.withYaw(current.yaw() + yaw)); }
    public synchronized void scale(float x, float y, float z) { pose(current.scale(x, y, z)); }
    public synchronized void settings(CloudSettings value) { requireOpen(); settings = Objects.requireNonNull(value); }
    public synchronized void wind(double x, double y, double z) {
        requireOpen(); CloudPose.finite(x); CloudPose.finite(y); CloudPose.finite(z);
        windX = x; windY = y; windZ = z;
    }
    public synchronized boolean isAlive() { return !closed; }
    @Override public synchronized void close() { closed = true; }
    private void requireOpen() { if (closed) throw new IllegalStateException("Cloud volume is closed"); }

    public record Frame(CloudPose pose, CloudSettings settings, double timeSeconds) {
        public Frame { Objects.requireNonNull(pose); Objects.requireNonNull(settings); CloudPose.finite(timeSeconds); }
    }
}
