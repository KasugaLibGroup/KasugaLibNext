package lib.kasuga.rendering.models.mc.dynamic.physics;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import lib.kasuga.rendering.lighting.DiffuseIrradiance;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses a stable vanilla-compatible light value for a moving block.
 *
 * <p>The ordinary path is deliberately the same packed light lookup used by
 * the vanilla block renderer. The neighbourhood path is only used when the
 * rendered volume actually intersects a solid world block. This keeps a
 * moving prop visually unchanged in normal space while preventing a zero
 * light sample from turning it into a black rectangle when Box3D temporarily
 * pushes it into a wall.</p>
 */
public final class PhysicsLightSampler {
    /** Contact tolerance: touching a wall is a valid physical contact, not an embedded body. */
    private static final float CONTACT_EPSILON = 0.002f;
    private static final int MAX_LIGHT = 15;

    private PhysicsLightSampler() {}

    /** Result consumed by both the vanilla fallback and the optional GI shader. */
    public record Sample(int packedLight, Vector3f indirectLight, boolean fallback, DiffuseIrradiance irradiance) {
        public Sample(int packedLight, Vector3f indirectLight, boolean fallback) {
            this(packedLight, indirectLight, fallback, new DiffuseIrradiance(
                    indirectLight, indirectLight, indirectLight, indirectLight, indirectLight, indirectLight));
        }
        public Sample {
            if (!Float.isFinite(indirectLight.x) || !Float.isFinite(indirectLight.y)
                    || !Float.isFinite(indirectLight.z)) {
                throw new IllegalArgumentException("Physics light sample must be finite");
            }
        }
    }

    /** At most one probe update per world tick, shared by all rendering views. */
    public static final class Tracker {
        private ClientLevel level;
        private long tick = Long.MIN_VALUE;
        private Sample cached;
        private Vec3 position;
        public Sample sample(ClientLevel level, Vec3 position, Vec3 extents, BlockPos anchor, int previous, Quaternionf rotation) {
            if (cached == null || this.level != level || tick != level.getGameTime() || position.distanceToSqr(this.position) > .0625) {
                cached = PhysicsLightSampler.sample(level, position, extents, anchor, previous, rotation);
                this.level = level; tick = level.getGameTime(); this.position = position;
            }
            return cached;
        }
    }

    /** Pure selection result used by JVM tests and by the world sampler. */
    public record Choice(int packedLight, float indirectStrength, boolean fallback) {
        public Choice {
            if (!Float.isFinite(indirectStrength) || indirectStrength < 0f || indirectStrength > 1f) {
                throw new IllegalArgumentException("Indirect light strength must be finite and in [0, 1]");
            }
        }
    }

    /**
     * Samples the world at a moving object's position. {@code previousLight}
     * is the last non-fallback sample and {@code anchor} is the object's
     * original block-space light anchor.
     */
    public static Sample sample(ClientLevel level, Vec3 position, Vec3 halfExtents,
                                BlockPos anchor, int previousLight) {
        return sample(level, position, halfExtents, anchor, previousLight, null);
    }

    /**
     * Samples a moving volume with its current orientation. The orientation is
     * only used by the embedded fallback path; the open-space path samples the
     * moving block's center so a touching face cannot sample the wall block
     * simply because the body's minimum corner lies on its boundary.
     */
    public static Sample sample(ClientLevel level, Vec3 position, Vec3 halfExtents,
                                BlockPos anchor, int previousLight, Quaternionf rotation) {
        if (level == null || position == null || halfExtents == null || anchor == null
                || !finite(position) || !finite(halfExtents)
                || halfExtents.x <= 0 || halfExtents.y <= 0 || halfExtents.z <= 0) {
            throw new IllegalArgumentException("Physics light sample arguments are invalid");
        }
        Quaternionf normalizedRotation = normalize(rotation);
        BlockPos directPosition = BlockPos.containing(position);
        int direct = LevelRenderer.getLightColor(level, directPosition);
        if (!intersectsSolid(level, position, halfExtents, normalizedRotation)) {
            return new Sample(direct, new Vector3f(), false);
        }

        // A single corner probe can turn dark as soon as a rotating body
        // crosses a block boundary. Include the center, oriented faces and
        // corners so an exposed part of the body remains the light baseline.
        List<Integer> probes = new ArrayList<>(16);
        probes.add(direct);
        probes.add(LevelRenderer.getLightColor(level, BlockPos.containing(position)));
        addOrientedProbes(level, position, halfExtents, normalizedRotation, probes);

        List<Integer> neighbours = new ArrayList<>(26 + probes.size());
        neighbours.addAll(probes);
        BlockPos base = BlockPos.containing(position);
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            if (x == 0 && y == 0 && z == 0) continue;
            BlockPos candidate = base.offset(x, y, z);
            BlockState state = level.getBlockState(candidate);
            if (state.isAir() || !state.isSolidRender(level, candidate)) {
                neighbours.add(LevelRenderer.getLightColor(level, candidate));
            }
        }
        int anchorLight = LevelRenderer.getLightColor(level, anchor);
        Choice choice = choose(direct, anchorLight, previousLight, neighbours, true);
        var irradiance = surfaceIrradiance(level, position, halfExtents, normalizedRotation);
        return new Sample(choice.packedLight(), irradiance.average(), true, irradiance);
    }

    /** Bounded single-bounce diffuse gather. First-hit geometry supplies both
     * visibility and map reflectance; its exposed lightmap supplies incident
     * energy. A dark scene therefore contributes exactly zero, and a wall
     * blocks a source. This is a local surface probe, not a full Lumen scene. */
    private static DiffuseIrradiance surfaceIrradiance(ClientLevel level, Vec3 center, Vec3 extents, Quaternionf rotation) {
        Vector3f[] basis = axes(rotation), lobes = new Vector3f[6];
        float daylight = Math.clamp(level.getSkyDarken(1f), 0, 1);
        for (int face = 0; face < 6; face++) {
            var normal = new Vector3f(); normal.setComponent(face / 2, face % 2 == 0 ? 1 : -1);
            float support = Math.abs(normal.dot(basis[0])) * (float) extents.x
                    + Math.abs(normal.dot(basis[1])) * (float) extents.y + Math.abs(normal.dot(basis[2])) * (float) extents.z;
            Vec3 origin = center.add(normal.x * (support + .04), normal.y * (support + .04), normal.z * (support + .04));
            var bounce = lobes[face] = new Vector3f();
            BlockPos originBlock = BlockPos.containing(origin);
            if (level.getBlockState(originBlock).isSolidRender(level, originBlock)) continue;
            var tangent = normal.cross(Math.abs(normal.y) > .9 ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0), new Vector3f()).normalize();
            var bitangent = normal.cross(tangent, new Vector3f());
            for (int ray = 0; ray < 6; ray++) {
                // Cosine-weighted hemisphere. The PDF cancels the receiver
                // cosine and Lambert 1/pi, so average the returned radiance.
                float r = (float) Math.sqrt((ray + .5) / 6.0), phi = ray * 2.39996323f;
                var direction = new Vector3f(normal).mul((float) Math.sqrt(1 - r * r))
                        .fma(r * (float) Math.cos(phi), tangent).fma(r * (float) Math.sin(phi), bitangent);
                Vec3 end = origin.add(direction.x * 3, direction.y * 3, direction.z * 3);
                var hit = level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                        net.minecraft.world.phys.shapes.CollisionContext.empty()));
                if (hit.getType() != HitResult.Type.BLOCK) continue;
                BlockPos surface = hit.getBlockPos(), exterior = surface.relative(hit.getDirection());
                var material = level.getBlockState(surface);
                int light = LevelRenderer.getLightColor(level, exterior);
                float block = lightBlock(light) / 15f, sky = lightSky(light) / 15f;
                float energy = Math.min(1, block * block + sky * sky * daylight);
                float distance = (float) hit.getLocation().distanceTo(origin);
                float weight = energy * (1 - distance / 3) * (1 - distance / 3) * .18f / 6;
                int color = material.getMapColor(level, surface).col;
                bounce.add((float) Math.pow(((color >> 16) & 255) / 255f, 2.2) * weight,
                        (float) Math.pow(((color >> 8) & 255) / 255f, 2.2) * weight,
                        (float) Math.pow((color & 255) / 255f, 2.2) * weight);
            }
        }
        return new DiffuseIrradiance(lobes);
    }

    private static void addOrientedProbes(ClientLevel level, Vec3 position, Vec3 halfExtents,
                                          Quaternionf rotation, List<Integer> probes) {
        Vector3f[] axes = axes(rotation);
        float[] extents = {(float) halfExtents.x, (float) halfExtents.y, (float) halfExtents.z};
        for (int axis = 0; axis < 3; axis++) {
            for (int sign : new int[]{-1, 1}) {
                Vector3f offset = new Vector3f(axes[axis]).mul(sign * (extents[axis] + .04f));
                probes.add(lightAt(level, position, offset));
            }
        }
        for (int sx : new int[]{-1, 1}) for (int sy : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            Vector3f offset = new Vector3f(
                    sx * extents[0], sy * extents[1], sz * extents[2]);
            offset.rotate(rotation).mul(1.04f);
            probes.add(lightAt(level, position, offset));
        }
    }

    private static int lightAt(ClientLevel level, Vec3 position, Vector3f offset) {
        return LevelRenderer.getLightColor(level, BlockPos.containing(
                position.x + offset.x, position.y + offset.y, position.z + offset.z));
    }

    /**
     * Pure, deterministic packed-light choice. Channel maxima are taken
     * independently because a wall can hide sky light while leaving block
     * light visible, or vice versa.
     */
    public static Choice choose(int directLight, int anchorLight, int previousLight,
                                List<Integer> neighbours, boolean embedded) {
        if (!embedded) return new Choice(directLight, 0f, false);
        // The direct sample remains a candidate even when the body overlaps a
        // solid block. Discarding it made the render jump as soon as a body
        // touched a wall, even when its exposed face still had valid light.
        int block = lightBlock(directLight), sky = lightSky(directLight);
        for (int light : neighbours == null ? List.<Integer>of() : neighbours) {
            block = Math.max(block, lightBlock(light));
            sky = Math.max(sky, lightSky(light));
        }
        // The previous sample is a temporal safety net, not another light
        // source. Once any current surface/anchor probe has a usable channel,
        // retaining a bright old sample makes the prop visibly pop when it
        // enters a darker room. Use it only when every current probe is zero.
        if (block == 0 && sky == 0) {
            block = Math.max(lightBlock(anchorLight), lightBlock(previousLight));
            sky = Math.max(lightSky(anchorLight), lightSky(previousLight));
        }
        // No fabricated sky floor: a zero-energy scene must remain dark.
        int packed = LightTexture.pack(block, sky);
        // Diagnostic energy bound only. Actual shader irradiance comes from
        // first-hit surface probes rather than this scalar.
        float energy = Math.max(block, sky) / 15f;
        float strength = energy * (1 - energy) * .18f;
        return new Choice(packed, strength, true);
    }

    public static int lightBlock(int packedLight) {
        return Math.clamp((packedLight >>> 4) & 0xF, 0, MAX_LIGHT);
    }

    public static int lightSky(int packedLight) {
        return Math.clamp((packedLight >>> 20) & 0xF, 0, MAX_LIGHT);
    }

    private static boolean intersectsSolid(ClientLevel level, Vec3 position, Vec3 halfExtents,
                                           Quaternionf rotation) {
        Vector3f[] axes = axes(rotation);
        float radiusX = Math.abs(axes[0].x) * (float) halfExtents.x
                + Math.abs(axes[1].x) * (float) halfExtents.y
                + Math.abs(axes[2].x) * (float) halfExtents.z;
        float radiusY = Math.abs(axes[0].y) * (float) halfExtents.x
                + Math.abs(axes[1].y) * (float) halfExtents.y
                + Math.abs(axes[2].y) * (float) halfExtents.z;
        float radiusZ = Math.abs(axes[0].z) * (float) halfExtents.x
                + Math.abs(axes[1].z) * (float) halfExtents.y
                + Math.abs(axes[2].z) * (float) halfExtents.z;
        int minX = floor(position.x - radiusX + CONTACT_EPSILON);
        int minY = floor(position.y - radiusY + CONTACT_EPSILON);
        int minZ = floor(position.z - radiusZ + CONTACT_EPSILON);
        int maxX = floor(position.x + radiusX - CONTACT_EPSILON);
        int maxY = floor(position.y + radiusY - CONTACT_EPSILON);
        int maxZ = floor(position.z + radiusZ - CONTACT_EPSILON);
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            BlockPos block = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(block);
            VoxelShape collision = state.getCollisionShape(level, block);
            if (collision.isEmpty()) continue;
            final boolean[] embedded = {false};
            collision.forAllBoxes((shapeMinX, shapeMinY, shapeMinZ, shapeMaxX, shapeMaxY, shapeMaxZ) -> {
                if (!embedded[0] && overlapDepth(position, halfExtents, axes,
                        block.getX() + shapeMinX, block.getY() + shapeMinY, block.getZ() + shapeMinZ,
                        block.getX() + shapeMaxX, block.getY() + shapeMaxY, block.getZ() + shapeMaxZ)
                        > CONTACT_EPSILON) {
                    embedded[0] = true;
                }
            });
            if (embedded[0]) return true;
        }
        return false;
    }

    /** Separating-axis test for an oriented prop box against one solid block. */
    static boolean overlapsBlock(Vec3 center, Vec3 extents, Quaternionf rotation, BlockPos block) {
        return overlapsBlock(center, extents, axes(normalize(rotation)), block);
    }

    private static boolean overlapsBlock(Vec3 center, Vec3 extents, Vector3f[] boxAxes, BlockPos block) {
        return overlapDepth(center, extents, boxAxes,
                block.getX(), block.getY(), block.getZ(),
                block.getX() + 1d, block.getY() + 1d, block.getZ() + 1d)
                > CONTACT_EPSILON;
    }

    /** Returns the minimum separating-axis penetration in world units. */
    private static float overlapDepth(Vec3 center, Vec3 extents, Vector3f[] boxAxes,
                                      double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ) {
        Vector3f[] worldAxes = {
                new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)
        };
        Vector3f translation = new Vector3f(
                (float) ((minX + maxX) * .5 - center.x),
                (float) ((minY + maxY) * .5 - center.y),
                (float) ((minZ + maxZ) * .5 - center.z));
        float[] a = {(float) ((maxX - minX) * .5),
                (float) ((maxY - minY) * .5),
                (float) ((maxZ - minZ) * .5)};
        float[] b = {(float) extents.x, (float) extents.y, (float) extents.z};
        float penetration = Float.POSITIVE_INFINITY;

        for (int i = 0; i < 3; i++) {
            float distance = Math.abs(component(translation, i));
            float radius = a[i];
            for (int j = 0; j < 3; j++) radius += b[j] * Math.abs(worldAxes[i].dot(boxAxes[j]));
            float depth = radius - distance;
            if (!(depth > 0f)) return 0f;
            penetration = Math.min(penetration, depth);
        }
        for (int j = 0; j < 3; j++) {
            Vector3f axis = boxAxes[j];
            float distance = Math.abs(translation.dot(axis));
            float radius = b[j];
            for (int i = 0; i < 3; i++) radius += a[i] * Math.abs(worldAxes[i].dot(axis));
            float depth = radius - distance;
            if (!(depth > 0f)) return 0f;
            penetration = Math.min(penetration, depth);
        }
        for (Vector3f worldAxis : worldAxes) for (Vector3f boxAxis : boxAxes) {
            Vector3f axis = new Vector3f(worldAxis).cross(boxAxis);
            if (axis.lengthSquared() <= 1e-8f) continue;
            axis.normalize();
            float distance = Math.abs(translation.dot(axis));
            float radius = a[0] * Math.abs(axis.x) + a[1] * Math.abs(axis.y) + a[2] * Math.abs(axis.z);
            radius += b[0] * Math.abs(boxAxes[0].dot(axis))
                    + b[1] * Math.abs(boxAxes[1].dot(axis))
                    + b[2] * Math.abs(boxAxes[2].dot(axis));
            float depth = radius - distance;
            if (!(depth > 0f)) return 0f;
            penetration = Math.min(penetration, depth);
        }
        return penetration;
    }

    private static float component(Vector3f value, int index) {
        return index == 0 ? value.x : index == 1 ? value.y : value.z;
    }

    private static Vector3f[] axes(Quaternionf rotation) {
        return new Vector3f[]{
                new Vector3f(1, 0, 0).rotate(rotation),
                new Vector3f(0, 1, 0).rotate(rotation),
                new Vector3f(0, 0, 1).rotate(rotation)
        };
    }

    private static Quaternionf normalize(Quaternionf rotation) {
        if (rotation == null) return new Quaternionf();
        if (!Float.isFinite(rotation.x) || !Float.isFinite(rotation.y)
                || !Float.isFinite(rotation.z) || !Float.isFinite(rotation.w)) {
            throw new IllegalArgumentException("Physics rotation must be finite");
        }
        float lengthSquared = rotation.x * rotation.x + rotation.y * rotation.y
                + rotation.z * rotation.z + rotation.w * rotation.w;
        if (!(lengthSquared > 1e-8f) || !Float.isFinite(lengthSquared)) return new Quaternionf();
        return new Quaternionf(rotation).normalize();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
