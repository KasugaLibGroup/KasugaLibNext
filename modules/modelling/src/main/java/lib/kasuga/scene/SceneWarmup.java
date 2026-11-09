package lib.kasuga.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A client-side readiness gate for revealing an authored scene. Block data arriving does not imply
 * that the corresponding terrain meshes have been uploaded. Call {@link #ready()} on the client
 * thread while a scene transition holds black, before exposing props or accepting scene input.
 * The bounded area must fit inside the client's loaded render distance.
 */
public final class SceneWarmup {
    private final ClientLevel level;
    private final BlockPos minimum, maximum;
    private final Map<BlockPos, BlockState> required = new LinkedHashMap<>();
    private boolean rebuilt;
    private String waitReason = "scene level";

    private SceneWarmup(ClientLevel level, BlockPos minimum, BlockPos maximum) {
        this.level = Objects.requireNonNull(level);
        this.minimum = minimum.immutable();
        this.maximum = maximum.immutable();
        if (minimum.getX() > maximum.getX() || minimum.getY() > maximum.getY()
                || minimum.getZ() > maximum.getZ())
            throw new IllegalArgumentException("Scene warmup bounds must be ordered");
    }

    public static SceneWarmup forArea(ClientLevel level, BlockPos minimum, BlockPos maximum) {
        return new SceneWarmup(level, minimum, maximum);
    }

    /** A sentinel authored block that must have arrived before rebuilding the scene's terrain. */
    public SceneWarmup requireBlock(BlockPos position, BlockState expected) {
        required.put(position.immutable(), Objects.requireNonNull(expected));
        return this;
    }

    /** Last unmet preparation condition, useful when the host's scene timeout expires. */
    public String waitReason() { return waitReason; }

    /** Uses the host renderer's public readiness queries, including Sodium's implementations. */
    public boolean ready() {
        RenderSystem.assertOnRenderThread();
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != level || minecraft.levelRenderer == null) {
            waitReason = "scene level";
            return false;
        }
        for (var entry : required.entrySet()) {
            if (!level.hasChunkAt(entry.getKey()) || !level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                waitReason = "authored block " + entry.getKey();
                return false;
            }
        }
        for (int x = minimum.getX() >> 4; x <= maximum.getX() >> 4; x++) {
            for (int z = minimum.getZ() >> 4; z <= maximum.getZ() >> 4; z++) {
                if (!level.hasChunk(x, z)) {
                    waitReason = "chunk " + x + ", " + z;
                    return false;
                }
            }
        }
        if (!rebuilt) {
            minecraft.levelRenderer.allChanged();
            rebuilt = true;
            waitReason = "renderer rebuild";
            return false;
        }
        var frustum = minecraft.levelRenderer.getFrustum();
        if (frustum == null) {
            waitReason = "first scene frame";
            return false;
        }
        for (int x = minimum.getX() >> 4; x <= maximum.getX() >> 4; x++) {
            for (int y = minimum.getY() >> 4; y <= maximum.getY() >> 4; y++) {
                for (int z = minimum.getZ() >> 4; z <= maximum.getZ() >> 4; z++) {
                    var origin = SectionPos.of(x, y, z).origin();
                    var area = new AABB(Math.max(origin.getX(), minimum.getX()),
                            Math.max(origin.getY(), minimum.getY()), Math.max(origin.getZ(), minimum.getZ()),
                            Math.min(origin.getX() + 16, maximum.getX() + 1),
                            Math.min(origin.getY() + 16, maximum.getY() + 1),
                            Math.min(origin.getZ() + 16, maximum.getZ() + 1));
                    // Terrain renderers deliberately leave off-camera sections uncompiled. Waiting
                    // for those meshes would hold black forever; data is still required everywhere.
                    if (frustum.isVisible(area) && !minecraft.levelRenderer.isSectionCompiled(origin)) {
                        waitReason = "visible terrain section " + origin;
                        return false;
                    }
                }
            }
        }
        boolean ready = minecraft.levelRenderer.hasRenderedAllSections();
        waitReason = ready ? "ready" : "terrain build queue";
        return ready;
    }
}
