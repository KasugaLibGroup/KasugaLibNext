package lib.kasuga.create.client.model;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.scene.SceneHost;
import lib.kasuga.scene.ScenePose;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Objects;
import java.util.Optional;

/** Optional client-side Aeronautics/Sable hosts. Companion is supplied by Sable, not bundled here.
 * Third-party symbols are isolated in Bridge so ordinary Create works without Companion installed.
 */
public final class SableSceneHosts {
    private static final boolean COMPANION_PRESENT = companionPresent();

    private SableSceneHosts() {}

    /** Whether the optional API can be linked; a containing sublevel is still required. */
    public static boolean isAvailable() { return COMPANION_PRESENT; }

    private static boolean companionPresent() {
        try {
            Class.forName("dev.ryanhcode.sable.companion.SableCompanion", false, SableSceneHosts.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) { return false; }
    }

    /** Anchor is the block's plot/storage position, not its projected world position.
     * Local (0,0,0) is the block's lower corner. Breaking or remapping it expires the attachment.
     */
    public static Optional<SceneHost> block(ClientLevel level, BlockPos anchor) {
        if (!COMPANION_PRESENT) return Optional.empty();
        return Bridge.block(Objects.requireNonNull(level), Objects.requireNonNull(anchor).immutable());
    }

    /** A normal Create contraption inside a Sable plot: parent ship pose followed by child pose once. */
    public static Optional<SceneHost> contraption(AbstractContraptionEntity entity) {
        if (!COMPANION_PRESENT) return Optional.empty();
        Objects.requireNonNull(entity);
        if (!(entity.level() instanceof ClientLevel level)) throw new IllegalArgumentException("A client contraption is required");
        return Bridge.contraption(level, entity);
    }

    static final class Bridge {
        private Bridge() {}

        static Optional<SceneHost> block(ClientLevel level, BlockPos anchor) {
            java.util.function.Supplier<dev.ryanhcode.sable.companion.ClientSubLevelAccess> resolver = () -> {
                if (MinecraftWorldViews.sourceLevel() != level) return null;
                var current = dev.ryanhcode.sable.companion.SableCompanion.INSTANCE.getContaining(level, anchor);
                if (!(current instanceof dev.ryanhcode.sable.companion.ClientSubLevelAccess client)
                        || !level.hasChunkAt(anchor) || level.getBlockState(anchor).isAir()) return null;
                return client;
            };
            if (resolver.get() == null) return Optional.empty();
            return Optional.of(track(resolver, Vec3.atLowerCornerOf(anchor), null));
        }

        static Optional<SceneHost> contraption(ClientLevel level, AbstractContraptionEntity entity) {
            java.util.function.Supplier<dev.ryanhcode.sable.companion.ClientSubLevelAccess> resolver = () -> {
                if (MinecraftWorldViews.sourceLevel() != level) return null;
                var current = dev.ryanhcode.sable.companion.SableCompanion.INSTANCE.getContaining(entity);
                return current instanceof dev.ryanhcode.sable.companion.ClientSubLevelAccess client ? client : null;
            };
            if (resolver.get() == null) return Optional.empty();
            return Optional.of(track(resolver, Vec3.ZERO, CreateSceneHosts.contraption(entity)));
        }

        static ScenePose anchored(dev.ryanhcode.sable.companion.math.Pose3dc pose, Vec3 anchor) {
            // Project the distant storage anchor in doubles before reducing to a nearby float basis.
            Vec3 origin = pose.transformPosition(anchor);
            var orientation = pose.orientation();
            var scale = pose.scale();
            var basis = new Matrix4f().rotation(new org.joml.Quaternionf(orientation))
                    .scale((float) scale.x(), (float) scale.y(), (float) scale.z());
            return new ScenePose(origin, basis);
        }

        static SceneHost track(java.util.function.Supplier<dev.ryanhcode.sable.companion.ClientSubLevelAccess> resolver,
                               Vec3 anchor, SceneHost child) {
            var original = Objects.requireNonNull(resolver.get(), "No loaded client sublevel at anchor");
            var identity = Objects.requireNonNull(original.getUniqueId(), "Sublevel has no UUID");
            return new SceneHost() {
                private boolean expired;

                @Override public boolean isAlive() {
                    if (!expired && (child != null && !child.isAlive() || resolver.get() != original
                            || !identity.equals(original.getUniqueId()))) expired = true;
                    return !expired;
                }

                @Override public ScenePose pose(float partialTick) {
                    if (!Float.isFinite(partialTick)) throw new IllegalArgumentException("Invalid partial tick");
                    if (!isAlive()) return null;
                    var parent = original.renderPose(partialTick);
                    if (child == null) return anchored(parent, anchor);
                    var local = child.pose(partialTick);
                    if (local == null) { expired = true; return null; }
                    var atChild = anchored(parent, local.origin());
                    return new ScenePose(atChild.origin(), new Matrix4f(atChild.transform()).mul(local.transform()));
                }
            };
        }
    }
}
