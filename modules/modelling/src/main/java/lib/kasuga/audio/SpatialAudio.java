package lib.kasuga.audio;

import lib.kasuga.KasugaLib;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Owned, positional sound instances. Streaming/mono are properties of the registered Ogg resource. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class SpatialAudio {
    private static final Set<Voice> VOICES = new LinkedHashSet<>();
    private SpatialAudio() {}
    public static Voice play(SoundEvent sound, SoundSource category, Vec3 position, float gain) {
        if (!Float.isFinite(gain) || gain <= 0 || !Double.isFinite(position.lengthSqr()))
            throw new IllegalArgumentException("A spatial voice requires a finite position and positive gain");
        var voice = new Voice(sound, category, position, gain);
        VOICES.add(voice);
        Minecraft.getInstance().getSoundManager().play(voice);
        return voice;
    }
    public static int activeCount() { return VOICES.size(); }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        for (var voice : List.copyOf(VOICES)) if (voice.level != level || voice.isStopped()) voice.close();
    }

    public static final class Voice extends AbstractTickableSoundInstance implements AutoCloseable {
        private final Object level = Minecraft.getInstance().level;
        private int ticks;
        private boolean closed, observedPlaying;
        private Voice(SoundEvent sound, SoundSource category, Vec3 position, float gain) {
            super(sound, category, RandomSource.create());
            x = position.x; y = position.y; z = position.z;
            volume = gain; pitch = 1; looping = false; relative = false;
            attenuation = SoundInstance.Attenuation.LINEAR;
        }
        @Override public void tick() {
            if (closed || Minecraft.getInstance().isPaused()) return;
            ticks++;
            observedPlaying |= Minecraft.getInstance().getSoundManager().isActive(this);
            if (observedPlaying && !Minecraft.getInstance().getSoundManager().isActive(this)) close();
        }
        public float seconds() { return ticks / 20f; }
        public boolean isPlaying() { return !closed && Minecraft.getInstance().getSoundManager().isActive(this); }
        public boolean failedToStart() { return ticks > 60 && !observedPlaying; }
        public Vec3 position() { return new Vec3(x, y, z); }
        /** True when the sound engine applies listener-relative attenuation and panning. */
        public boolean spatial() { return !relative; }
        @Override public void close() {
            if (closed) return;
            closed = true; stop(); Minecraft.getInstance().getSoundManager().stop(this); VOICES.remove(this);
        }
    }
}
