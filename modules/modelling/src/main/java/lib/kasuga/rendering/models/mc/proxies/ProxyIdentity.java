package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.KasugaLib;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Stable identity of one {@link ProxyInstance}, used as the instance key of the model pipeline.
 *
 * <p>The key always contains the dimension (or the owning system for external hosts), so two
 * proxies at the same coordinate in different dimensions never share a {@code ModelInstance}.
 * It is intentionally independent of the model location: re-binding a different model must not
 * change the identity of the running instance.
 *
 * <p>Available layouts, all under the library namespace:
 * <ul>
 *     <li>block: {@code kasuga_lib:proxy/<dim>/<x>/<y>/<z>}</li>
 *     <li>entity: {@code kasuga_lib:proxy/entity/<uuid>}</li>
 *     <li>external: {@code kasuga_lib:proxy/external/<namespace>/<path>/<discriminator>}</li>
 * </ul>
 *
 * <p>Equality is defined by {@link #key()} alone: {@link #origin()} is diagnostic text and never
 * participates in identity comparison.
 */
public final class ProxyIdentity {

    private final ResourceLocation key;
    private final long discriminator;
    private final String origin;

    private ProxyIdentity(ResourceLocation key, long discriminator, String origin) {
        this.key = key;
        this.discriminator = discriminator;
        this.origin = origin;
    }

    /** Position of a block entity. The dimension must already be set on {@code level}. */
    public static ProxyIdentity ofBlock(@NotNull Level level, @NotNull BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        String dim = level.dimension().location().toString().replace(':', '/');
        String origin = "block(%s,%d,%d,%d)".formatted(dim, pos.getX(), pos.getY(), pos.getZ());
        return new ProxyIdentity(
                build("proxy/%s/%d/%d/%d".formatted(dim, pos.getX(), pos.getY(), pos.getZ())),
                pos.asLong(),
                origin
        );
    }

    /** An entity, keyed by its UUID so identity survives unload/reload. */
    public static ProxyIdentity ofEntity(@NotNull Entity entity) {
        Objects.requireNonNull(entity, "entity");
        String type = entity.getType().builtInRegistryHolder().key().location().toString().replace(':', '/');
        java.util.UUID uuid = entity.getUUID();
        return new ProxyIdentity(
                build("proxy/entity/%s".formatted(uuid)),
                uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits(),
                "entity(%s,%s)".formatted(type, uuid)
        );
    }

    /**
     * A host owned by an external system (contraption, bogey, script, ...).
     * {@code discriminator} must be stable for the lifetime of the logical element.
     */
    public static ProxyIdentity ofExternal(@NotNull String namespace, @NotNull String path, long discriminator) {
        ResourceLocation owner = ResourceLocation.tryParse(namespace + ":" + path);
        if (owner == null) {
            throw new IllegalArgumentException("invalid proxy owner id: " + namespace + ":" + path);
        }
        return new ProxyIdentity(
                build("proxy/external/%s/%s/%d".formatted(owner.getNamespace(), owner.getPath(), discriminator)),
                discriminator,
                "external(%s,%d)".formatted(owner, discriminator)
        );
    }

    private static ResourceLocation build(String path) {
        ResourceLocation id = ResourceLocation.tryBuild(KasugaLib.MODID, path);
        if (id == null) {
            throw new IllegalStateException("cannot build proxy identity: " + KasugaLib.MODID + ":" + path);
        }
        return id;
    }

    /** The pipeline instance key. */
    public ResourceLocation key() {
        return key;
    }

    /**
     * Stable long for consumers that key by a number (e.g. {@code FsmSyncKey}):
     * {@code pos.asLong()} for blocks, UUID halves xor-ed for entities, the given value for external hosts.
     */
    public long discriminator() {
        return discriminator;
    }

    /** Human readable description for diagnostics; not part of equality. */
    public String origin() {
        return origin;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof ProxyIdentity other && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key + " " + origin;
    }
}
