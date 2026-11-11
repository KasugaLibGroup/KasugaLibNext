package lib.kasuga.scene;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Objects;

/** One attachment's local placement and its last captured world frame; owns no host resources. */
public final class ScenePlacement {
    private Vec3 position;
    private Matrix4f transform = new Matrix4f();
    private SceneHost host;
    private ScenePose lastFrame;
    private boolean expired;

    public ScenePlacement(Vec3 position) { position(position); }

    public ScenePlacement position(Vec3 value) {
        new ScenePose(value, new Matrix4f());
        position = value;
        return this;
    }

    public ScenePlacement transform(Matrix4fc value) {
        new ScenePose(Vec3.ZERO, value);
        transform = new Matrix4f(value);
        return this;
    }

    /** Position and transform are now relative to this host. A dead placement cannot be rebound. */
    public ScenePlacement follow(SceneHost value) {
        if (expired) throw new IllegalStateException("Scene placement has expired");
        host = Objects.requireNonNull(value);
        lastFrame = null;
        return this;
    }

    public boolean followsHost() { return host != null; }

    public boolean isAlive() {
        if (!expired && host != null && !host.isAlive()) expire();
        return !expired;
    }

    /** Captured once for drawing; UI and picking retain this same immutable result. */
    public @Nullable ScenePose capture(float partialTick) {
        return lastFrame = sample(partialTick);
    }

    /** Samples another view without replacing the main view's picking frame. */
    public @Nullable ScenePose sample(float partialTick) {
        if (!Float.isFinite(partialTick)) throw new IllegalArgumentException("Invalid partial tick");
        if (!isAlive()) return null;
        ScenePose next;
        if (host == null) next = new ScenePose(position, transform);
        else {
            var root = host.pose(partialTick);
            if (root == null) { expire(); return null; }
            next = root.compose(position, transform);
        }
        return next;
    }

    public @Nullable ScenePose lastFrame() { return lastFrame; }
    public void expire() { expired = true; lastFrame = null; }
}
