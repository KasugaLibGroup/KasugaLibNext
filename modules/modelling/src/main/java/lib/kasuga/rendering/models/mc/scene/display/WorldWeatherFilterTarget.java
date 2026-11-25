package lib.kasuga.rendering.models.mc.scene.display;

import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.weather.WeatherFilterVolume;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;

/**
 * An owned weather filter volume node in a {@link WorldDisplayScene}.
 * Position, orientation, and scale are inherited from its parent scene/host.
 */
public final class WorldWeatherFilterTarget extends WorldDisplayTarget {
    @Getter
    @Accessors(fluent = true)
    private WeatherFilterVolume volume;

    public WorldWeatherFilterTarget(WeatherFilterVolume volume) {
        this.volume = Objects.requireNonNull(volume, "volume");
    }

    public WorldWeatherFilterTarget volume(WeatherFilterVolume value) {
        requireOpen();
        this.volume = Objects.requireNonNull(value, "value");
        return this;
    }

    public WorldWeatherFilterTarget position(Vec3 value) {
        Objects.requireNonNull(value, "value");
        return volume(volume.toBuilder().position(value.x, value.y, value.z).build());
    }

    public WorldWeatherFilterTarget moveBy(double x, double y, double z) {
        return volume(volume.toBuilder()
                .position(volume.x() + x, volume.y() + y, volume.z() + z)
                .build());
    }

    public WorldWeatherFilterTarget strength(float value) {
        return volume(volume.toBuilder().strength(value).build());
    }

    @Override
    protected Snapshot contentSnapshot(LayerLayout layout, float partialTick) {
        return null;
    }

    @Override
    protected List<WeatherFilter> contentWeatherFilters() {
        return List.of(new WeatherFilter(this, volume, new Matrix4f()));
    }

    @Override
    protected void closeContent() {}
}
