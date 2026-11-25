package lib.kasuga.rendering.effect.builtin.weather;

import lib.kasuga.rendering.effect.RenderEffect;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.rendering.weather.WeatherFilterFrame;
import lib.kasuga.rendering.weather.WeatherFilterVolume;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.Objects;

/**
 * Level-scoped explicit weather filter volume. Publish immutable {@link WeatherFilterFrame}
 * updates to move, rotate, or change attenuation strength.
 */
@Accessors(fluent = true)
public final class WeatherFilterEffect implements RenderEffect, AutoCloseable {
    @Getter
    private final ClientLevel level;
    @Getter
    private volatile WeatherFilterFrame frame;
    private volatile boolean closed;

    public WeatherFilterEffect(ClientLevel level, WeatherFilterVolume volume) {
        this.level = Objects.requireNonNull(level, "level");
        this.frame = WeatherFilterFrame.world(volume);
    }

    public WeatherFilterVolume volume() {
        return frame.volume();
    }

    public void volume(WeatherFilterVolume value) {
        frame = new WeatherFilterFrame(value, frame.worldTransform());
    }

    public void frame(WeatherFilterFrame value) {
        frame = Objects.requireNonNull(value, "frame");
    }

    public void moveTo(double x, double y, double z) {
        Vector3d center = frame.center();
        moveBy(x - center.x, y - center.y, z - center.z);
    }

    public void moveBy(double x, double y, double z) {
        WeatherFilterFrame current = frame;
        Matrix4d translated = new Matrix4d().translation(x, y, z).mul(current.worldTransform());
        frame = new WeatherFilterFrame(current.volume(), translated);
    }

    public boolean matchesLevel(ClientLevel targetLevel) {
        return targetLevel == level || (MinecraftWorldViews.sourceLevel() == level
                && targetLevel != null && targetLevel.dimension().equals(level.dimension()));
    }

    @Override
    public void tick(ClientLevel currentLevel) {
        if (currentLevel != level) {
            close();
        }
    }

    @Override
    public boolean isAlive() {
        return !closed;
    }

    @Override
    public boolean isVisible(WorldRenderPipelineContext context, float partialTick) {
        return matchesLevel(context.level())
                && frame.volume().strength() > 0.0f
                && RenderEffect.super.isVisible(context, partialTick);
    }

    @Override
    public Vec3 position(float partialTick) {
        Vector3d center = frame.center();
        return new Vec3(center.x, center.y, center.z);
    }

    @Override
    public AABB bounds(float partialTick) {
        WeatherFilterFrame current = frame;
        return new AABB(
                current.minX(), current.minY(), current.minZ(),
                current.maxX(), current.maxY(), current.maxZ()
        );
    }

    @Override
    public void close() {
        closed = true;
    }
}
