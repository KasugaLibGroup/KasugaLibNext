package lib.kasuga.rendering.effect.builtin.weather;

import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Shared frame context passed to {@link WeatherOccluder#renderDepth(WeatherDepthPassContext)}
 * when capturing the top-down rain occlusion depth map around the camera.
 */
@Accessors(fluent = true)
public final class WeatherDepthPassContext {
    @Getter
    private final ClientLevel level;
    @Getter
    private final Vec3 camera;
    @Getter
    @Nullable
    private final Entity cameraEntity;
    @Getter
    private final float partialTick;
    @Getter
    private final double horizontalRadius;
    @Getter
    private final double minWorldY;
    @Getter
    private final double maxWorldY;
    @Getter
    private final PoseStack poseStack;
    @Getter
    private final WeatherDepthBuffers buffers;
    private final Matrix4f viewMatrix;
    private final Matrix4f projectionMatrix;

    public WeatherDepthPassContext(
            ClientLevel level,
            Vec3 camera,
            @Nullable Entity cameraEntity,
            float partialTick,
            double horizontalRadius,
            double minWorldY,
            double maxWorldY,
            PoseStack poseStack,
            WeatherDepthBuffers buffers,
            Matrix4fc viewMatrix,
            Matrix4fc projectionMatrix
    ) {
        this.level = level;
        this.camera = camera;
        this.cameraEntity = cameraEntity;
        this.partialTick = partialTick;
        this.horizontalRadius = horizontalRadius;
        this.minWorldY = minWorldY;
        this.maxWorldY = maxWorldY;
        this.poseStack = poseStack;
        this.buffers = buffers;
        this.viewMatrix = new Matrix4f(viewMatrix);
        this.projectionMatrix = new Matrix4f(projectionMatrix);
    }

    public Matrix4f viewMatrix() {
        return new Matrix4f(viewMatrix);
    }

    public Matrix4f projectionMatrix() {
        return new Matrix4f(projectionMatrix);
    }
}
