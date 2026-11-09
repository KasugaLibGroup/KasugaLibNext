package lib.kasuga.rendering.effect.builtin.water;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.water.ShallowWaterGrid;
import lib.kasuga.rendering.water.WaterOptics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.function.DoubleBinaryOperator;

/**
 * An unbounded optical water plane with a moving window of simulated free-surface water.
 * Near-field waves are actual depth/momentum states, not an animated normal-map substitute.
 * A flat walkable bed is supplied by the host's world; this API supplies liquid and leg interaction.
 */
public final class WaterSurface implements AutoCloseable {
    final ClientLevel level;
    final ShallowWaterGrid grid;
    final float bedY;
    double centerX, centerZ;
    float seconds;
    private boolean closed, followPlayer, playerInteraction = true, renderEnabled = true;
    private WaterOptics optics = WaterOptics.DEFAULT;
    private DoubleBinaryOperator terrainHeight;
    private DynamicTexture texture;
    private Vec3 previousFeet;
    private float stepDistance;
    private int foot;
    private long revision, uploaded = -1;
    private long interactions;

    WaterSurface(ClientLevel level, double x, float bedY, double z, int resolution, float cellSize, float depth) {
        this.level = level; this.centerX = x; this.centerZ = z; this.bedY = bedY;
        grid = new ShallowWaterGrid(resolution, cellSize, depth);
    }
    public WaterSurface followPlayer(boolean enabled) { followPlayer = enabled; return this; }
    /** Enables or disables player-to-surface coupling for a choreographed scene. */
    public WaterSurface playerInteraction(boolean enabled) {
        if (playerInteraction != enabled) {
            playerInteraction = enabled;
            previousFeet = null;
            stepDistance = 0;
        }
        return this;
    }
    /** Enables or disables the optical surface while retaining the simulation state. */
    public WaterSurface renderEnabled(boolean enabled) {
        renderEnabled = enabled;
        return this;
    }
    /** Sets optical coefficients for this surface without changing other levels' water. */
    public WaterSurface optics(WaterOptics value) { optics = Objects.requireNonNull(value); return this; }
    public WaterOptics optics() { return optics; }
    /** Supplies the walkable terrain height in world coordinates; defaults to the flat {@code bedY}. */
    public WaterSurface terrainHeight(DoubleBinaryOperator value) {
        terrainHeight = Objects.requireNonNull(value);
        previousFeet = null;
        stepDistance = 0;
        return this;
    }
    public boolean renderEnabled() { return renderEnabled; }
    public ShallowWaterGrid simulation() { return grid; }
    public float surfaceY() { return bedY + grid.restDepth(); }
    public boolean playerInteractionEnabled() { return playerInteraction; }
    public long interactionCount() { return interactions; }
    public boolean closed() { return closed; }

    /** Applies a world-space water displacement and momentum transfer, e.g. a foot or rigid prop. */
    public void interact(Vec3 position, Vec3 velocity, float radius, float displacement) {
        if (closed) return;
        float x = (float) (position.x - centerX), z = (float) (position.z - centerZ);
        if (Math.abs(x) > grid.extent() * .45 || Math.abs(z) > grid.extent() * .45) return;
        grid.displace(x, z, radius, displacement);
        grid.impulse(x, z, radius, (float) velocity.x * .12f, (float) velocity.z * .12f);
        interactions++; revision++;
    }

    void tick() {
        if (closed) return;
        var player = Minecraft.getInstance().player;
        if (followPlayer && playerInteraction && player != null) {
            var feet = player.position();
            int dx = (int) ((feet.x - centerX) / grid.cellSize());
            int dz = (int) ((feet.z - centerZ) / grid.cellSize());
            if (Math.abs(dx) > 8 || Math.abs(dz) > 8) {
                grid.shift(dx, dz); centerX += dx * grid.cellSize(); centerZ += dz * grid.cellSize();
            }
            double bed = terrainHeight == null ? bedY : terrainHeight.applyAsDouble(feet.x, feet.z);
            if (!Double.isFinite(bed)) throw new IllegalStateException("Water terrain returned a non-finite height");
            if (feet.y >= bed - .15 && feet.y < surfaceY() + .06) {
                var movement = previousFeet == null ? Vec3.ZERO : feet.subtract(previousFeet);
                double distance = movement.horizontalDistance();
                if (previousFeet == null || previousFeet.y >= surfaceY() + .06)
                    interact(feet, Vec3.ZERO, .4f, .025f);
                if (distance > .005 && distance < 1.5) {
                    stepDistance += (float) distance;
                    if (stepDistance >= .27f) {
                        stepDistance %= .27f;
                        double yaw = Math.toRadians(player.getYRot()), side = (++foot % 2 == 0 ? 1 : -1) * .16;
                        var footPosition = feet.add(Math.cos(yaw) * side, 0, Math.sin(yaw) * side);
                        interact(footPosition, movement.scale(20), .4f, .024f);
                    }
                    var velocity = player.getDeltaMovement();
                    float waterX = grid.velocityX((float) (feet.x - centerX), (float) (feet.z - centerZ));
                    float waterZ = grid.velocityZ((float) (feet.x - centerX), (float) (feet.z - centerZ));
                    player.setDeltaMovement(velocity.x * .985 + waterX * .0005, velocity.y,
                            velocity.z * .985 + waterZ * .0005);
                }
            } else stepDistance = 0;
            previousFeet = feet;
        } else {
            previousFeet = null;
            stepDistance = 0;
        }
        grid.step(.05f); seconds += .05f; revision++;
    }

    int texture() {
        if (texture == null) {
            texture = new DynamicTexture(new NativeImage(grid.size(), grid.size(), false));
            texture.setFilter(true, false);
        }
        if (uploaded != revision) {
            var pixels = texture.getPixels();
            for (int z = 0; z < grid.size(); z++) for (int x = 0; x < grid.size(); x++) {
                int height = Math.round(Math.clamp((grid.cellDepth(x, z) - grid.restDepth() + .25f) * 2, 0, 1) * 65535);
                // ABGR image; red is the high byte, green is the low byte of the encoded displacement.
                pixels.setPixelRGBA(x, z, 0xFF000000 | (height & 255) << 8 | height >>> 8);
            }
            texture.upload(); uploaded = revision;
        }
        return texture.getId();
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        DynamicTexture retired = texture;
        texture = null;
        if (retired != null) {
            if (RenderSystem.isOnRenderThread()) retired.close();
            else RenderSystem.recordRenderCall(retired::close);
        }
        WaterEffects.remove(this);
    }
}
