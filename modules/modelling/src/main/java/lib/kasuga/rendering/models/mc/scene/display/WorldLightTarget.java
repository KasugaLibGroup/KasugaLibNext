package lib.kasuga.rendering.models.mc.scene.display;

import lib.kasuga.rendering.lighting.LightSource;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;

/** An owned light node in a WorldDisplayScene. Position and transform are relative to its parent. */
public final class WorldLightTarget extends WorldDisplayTarget {
    private LightSource source;

    public WorldLightTarget(LightSource source) { this.source = Objects.requireNonNull(source); }
    public LightSource source() { return source; }
    public WorldLightTarget source(LightSource value) { requireOpen(); source = Objects.requireNonNull(value); return this; }

    public WorldLightTarget position(Vec3 value) {
        return source(source.toBuilder().position(value.x, value.y, value.z).build());
    }

    public WorldLightTarget moveBy(double x, double y, double z) {
        return source(source.toBuilder().position(source.x() + x, source.y() + y, source.z() + z).build());
    }

    @Override protected Snapshot contentSnapshot(LayerLayout layout, float partialTick) { return null; }
    @Override protected List<Light> contentLights() { return List.of(new Light(this, source, new Matrix4f())); }
    @Override protected void closeContent() {}
}
