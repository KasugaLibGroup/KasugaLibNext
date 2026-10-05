package lib.kasuga.rendering.effect.builtin.cloud;

import lib.kasuga.rendering.cloud.CloudPose;
import lib.kasuga.rendering.cloud.CloudSettings;
import lib.kasuga.rendering.cloud.CumulonimbusVolume;
import lib.kasuga.rendering.effect.RenderEffect;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A placeable, removable world effect. Tick ownership belongs to the existing effect runtime. */
public final class CumulonimbusCloud implements RenderEffect, AutoCloseable {
    private final CumulonimbusVolume volume;

    public CumulonimbusCloud(CloudPose pose, CloudSettings settings) { volume = new CumulonimbusVolume(pose, settings); }
    public CumulonimbusCloud(Vec3 baseCenter) { this(CloudPose.at(baseCenter.x, baseCenter.y, baseCenter.z), CloudSettings.CUMULONIMBUS); }
    public CumulonimbusVolume volume() { return volume; }
    public CloudPose pose() { return volume.pose(); }
    public void moveTo(double x, double y, double z) { volume.moveTo(x, y, z); }
    public void moveBy(double x, double y, double z) { volume.moveBy(x, y, z); }
    public void rotateBy(float yaw) { volume.rotateBy(yaw); }
    public void scale(float x, float y, float z) { volume.scale(x, y, z); }
    public void wind(double x, double y, double z) { volume.wind(x, y, z); }
    public void settings(CloudSettings value) { volume.settings(value); }
    @Override public void tick(ClientLevel level) { volume.tick(1.0 / 20); }
    @Override public boolean isAlive() { return volume.isAlive(); }
    @Override public boolean isVisible(WorldRenderPipelineContext context, float partialTick) {
        if (!CloudEffects.isEnabled()) return false;
        var camera = context.camera().getPosition();
        return volume.sample(partialTick).pose().isVisible(
                new org.joml.Matrix4f(context.projectionMatrix()).mul(context.modelViewMatrix()), camera.x, camera.y, camera.z);
    }
    @Override public Vec3 position(float partialTick) {
        var pose = volume.sample(partialTick).pose();
        return new Vec3(pose.x(), pose.y() + pose.height() * .5, pose.z());
    }
    @Override public AABB bounds(float partialTick) {
        var bounds = volume.sample(partialTick).pose().bounds();
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }
    @Override public void close() { volume.close(); }
}
