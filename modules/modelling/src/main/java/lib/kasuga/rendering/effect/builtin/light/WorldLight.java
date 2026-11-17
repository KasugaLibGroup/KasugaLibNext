package lib.kasuga.rendering.effect.builtin.light;

import lib.kasuga.rendering.effect.RenderEffect;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.lighting.LightSource;
import lib.kasuga.rendering.lighting.LightFrame;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Level-scoped light instance. Publish immutable settings to move or animate it. */
public final class WorldLight implements RenderEffect, AutoCloseable {
    private final ClientLevel level;
    private volatile LightFrame frame;
    private volatile boolean closed;

    public WorldLight(ClientLevel level, LightSource source) {
        this.level = Objects.requireNonNull(level, "level");
        this.frame = LightFrame.world(source);
    }

    public LightSource source() { return frame.source(); }
    public void source(LightSource value) { frame = new LightFrame(value, frame.transform()); }
    public LightFrame frame() { return frame; }
    public void frame(LightFrame value) { frame = Objects.requireNonNull(value, "frame"); }
    public void moveTo(double x, double y, double z) {
        Vector3d position = frame.position();
        moveBy(x - position.x, y - position.y, z - position.z);
    }
    public void moveBy(double x, double y, double z) {
        LightFrame current = frame;
        Matrix4d translated = new Matrix4d().translation(x, y, z).mul(current.transform());
        frame = new LightFrame(current.source(), translated);
    }
    @Override public void tick(ClientLevel currentLevel) { if (currentLevel != level) close(); }
    @Override public boolean isAlive() { return !closed; }

    @Override public boolean isVisible(WorldRenderPipelineContext context, float partialTick) {
        boolean sameWorld = context.level() == level || (MinecraftWorldViews.sourceLevel() == level
                && context.level().dimension().equals(level.dimension()));
        return sameWorld && source().intensity() > 0 && RenderEffect.super.isVisible(context, partialTick);
    }

    @Override public Vec3 position(float partialTick) {
        Vector3d position = frame.position();
        return new Vec3(position.x, position.y, position.z);
    }

    @Override public AABB bounds(float partialTick) {
        LightFrame current = frame;
        Vector3d position = current.position();
        double radius = current.source().boundsRadius() * current.rangeScale();
        return new AABB(position.x - radius, position.y - radius, position.z - radius,
                position.x + radius, position.y + radius, position.z + radius);
    }

    @Override public void close() { closed = true; }
}
