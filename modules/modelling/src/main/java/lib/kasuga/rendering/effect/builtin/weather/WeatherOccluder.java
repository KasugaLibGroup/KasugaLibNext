package lib.kasuga.rendering.effect.builtin.weather;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;

/**
 * Provides top-down rain occlusion geometry (for the GPU depth pass) and fast CPU
 * vertical roof queries (for rain splashes, muffled rain audio, and Iris fallback).
 */
public interface WeatherOccluder {
    boolean hasNearbyOccluders(
            ClientLevel level,
            Vec3 camera,
            @Nullable Entity cameraEntity,
            double horizontalRadius,
            double minWorldY,
            double maxWorldY,
            float partialTick
    );

    void renderDepth(WeatherDepthPassContext context);

    OptionalDouble sampleRoofHeight(
            ClientLevel level,
            @Nullable Entity cameraEntity,
            double worldX,
            double minY,
            double maxY,
            double worldZ,
            float partialTick
    );
}
