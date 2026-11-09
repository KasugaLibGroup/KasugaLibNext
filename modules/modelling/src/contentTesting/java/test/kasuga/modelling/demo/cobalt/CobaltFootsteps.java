package test.kasuga.modelling.demo.cobalt;

import lib.kasuga.audio.SpatialAudio;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Cobalt-only footstep emitter backed by the shared Kasuga positional audio API. */
final class CobaltFootsteps implements AutoCloseable {
    private static final double STEP_DISTANCE = .62;
    private final List<SpatialAudio.Voice> voices = new ArrayList<>();
    private Vec3 previousFeet;
    private double distance;
    private int foot;
    private int emitted;
    private SpatialAudio.Voice lastVoice;

    void tick(LocalPlayer player) {
        if (player == null || !player.isAlive() || !player.onGround()) {
            previousFeet = player == null ? null : player.position();
            distance = 0;
            return;
        }
        Vec3 feet = player.position();
        if (previousFeet != null) {
            Vec3 movement = feet.subtract(previousFeet);
            double travelled = movement.horizontalDistance();
            if (travelled > 0.001 && travelled < 1.5) {
                distance += travelled;
                while (distance >= STEP_DISTANCE) {
                    distance -= STEP_DISTANCE;
                    double yaw = Math.toRadians(player.getYRot());
                    double side = (++foot & 1) == 0 ? .16 : -.16;
                    Vec3 soundPosition = feet.add(Math.cos(yaw) * side, .02, Math.sin(yaw) * side);
                    lastVoice = SpatialAudio.play(SoundEvents.STONE_STEP, SoundSource.PLAYERS,
                            soundPosition, .34f);
                    voices.add(lastVoice);
                    emitted++;
                }
            }
        }
        previousFeet = feet;
        voices.removeIf(SpatialAudio.Voice::isStopped);
    }

    int emittedCount() { return emitted; }

    SpatialAudio.Voice lastVoice() { return lastVoice; }

    @Override public void close() {
        for (var voice : voices) voice.close();
        voices.clear(); previousFeet = null; distance = 0; foot = 0; emitted = 0; lastVoice = null;
    }
}
