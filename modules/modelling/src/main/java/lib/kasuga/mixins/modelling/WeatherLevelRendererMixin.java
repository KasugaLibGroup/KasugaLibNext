package lib.kasuga.mixins.modelling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import lib.kasuga.rendering.effect.builtin.weather.WeatherFilters;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(LevelRenderer.class)
abstract class WeatherLevelRendererMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Nullable private ClientLevel level;

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"))
    private void kasuga$beginWeatherFilter(
            LightTexture lightTexture,
            float partialTick,
            double camX,
            double camY,
            double camZ,
            CallbackInfo ci
    ) {
        ClientLevel targetLevel = this.level != null ? this.level : this.minecraft.level;
        if (targetLevel != null && targetLevel.getRainLevel(partialTick) > 0.0F) {
            WeatherFilters.beginWeatherRender(targetLevel, camX, camY, camZ, partialTick);
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("RETURN"))
    private void kasuga$endWeatherFilter(
            LightTexture lightTexture,
            float partialTick,
            double camX,
            double camY,
            double camZ,
            CallbackInfo ci
    ) {
        WeatherFilters.endWeatherRender();
    }

    @WrapOperation(
            method = "renderSnowAndRain",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V"
            )
    )
    private void kasuga$useWeatherFilterShader(Supplier<ShaderInstance> supplier, Operation<Void> original) {
        original.call(WeatherFilters.resolveWeatherShader(supplier));
    }

    @WrapOperation(
            method = "renderSnowAndRain",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"
            )
    )
    private int kasuga$clampRainColumnForCpuFallback(
            Level level,
            Heightmap.Types heightmapType,
            int x,
            int z,
            Operation<Integer> original,
            @Local(argsOnly = true, ordinal = 1) double camY,
            @Local(ordinal = 3) int range
    ) {
        int vanillaHeight = original.call(level, heightmapType, x, z);
        if (!WeatherFilters.shouldUseCpuColumnClamp() || !(level instanceof ClientLevel clientLevel)) {
            return vanillaHeight;
        }
        int maxRainY = Mth.floor(camY) + range;
        return WeatherFilters.effectiveMotionBlockingHeight(clientLevel, x, z, vanillaHeight, maxRainY);
    }

    @WrapOperation(
            method = "tickRain",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/LevelReader;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"
            )
    )
    private BlockPos kasuga$raiseRainSplashHeightmap(
            LevelReader reader,
            Heightmap.Types heightmapType,
            BlockPos pos,
            Operation<BlockPos> original,
            @Local(argsOnly = true) Camera camera
    ) {
        BlockPos vanillaPos = original.call(reader, heightmapType, pos);
        return WeatherFilters.adjustRainHeightmapPos(reader, pos, vanillaPos, camera.getPosition());
    }

    @WrapOperation(
            method = "tickRain",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"
            )
    )
    private void kasuga$filterRainParticles(
            ClientLevel level,
            ParticleOptions options,
            double x,
            double y,
            double z,
            double xSpeed,
            double ySpeed,
            double zSpeed,
            Operation<Void> original,
            @Local(argsOnly = true) Camera camera
    ) {
        if (!WeatherFilters.shouldSpawnVanillaRainSplash(level, x, y, z, camera.getPosition().y + 10.0)) {
            return;
        }
        original.call(level, options, x, y, z, xSpeed, ySpeed, zSpeed);
    }

    @WrapOperation(
            method = "tickRain",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"
            )
    )
    private void kasuga$muffleShelteredRainSound(
            ClientLevel level,
            BlockPos pos,
            SoundEvent sound,
            SoundSource source,
            float volume,
            float pitch,
            boolean distanceDelay,
            Operation<Void> original,
            @Local(argsOnly = true) Camera camera
    ) {
        if (sound == SoundEvents.WEATHER_RAIN && WeatherFilters.isPositionSheltered(level, camera.getPosition())) {
            original.call(level, pos, SoundEvents.WEATHER_RAIN_ABOVE, source, 0.1F, 0.5F, distanceDelay);
            return;
        }
        original.call(level, pos, sound, source, volume, pitch, distanceDelay);
    }
}
