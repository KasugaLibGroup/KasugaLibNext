package lib.kasuga.audio;

import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Resource-pack replaceable metadata for a registered positional music event. */
public record SceneTrack(String title, String artist, String album, float durationSeconds,
                         ResourceLocation sound, ResourceLocation audioFile) {
    public SceneTrack {
        if (title == null || artist == null || album == null || sound == null || audioFile == null
                || !Float.isFinite(durationSeconds) || durationSeconds <= 0)
            throw new IllegalArgumentException("Track metadata is incomplete");
    }
    public static SceneTrack load(ResourceLocation metadata) {
        var resources = Minecraft.getInstance().getResourceManager();
        try (var input = resources.open(metadata); var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            return new SceneTrack(json.get("title").getAsString(), json.get("artist").getAsString(),
                    json.get("album").getAsString(), json.get("duration_seconds").getAsFloat(),
                    ResourceLocation.parse(json.get("sound").getAsString()), ResourceLocation.parse(json.get("audio_file").getAsString()));
        } catch (IOException | RuntimeException failure) { throw new IllegalStateException("Cannot load scene track " + metadata, failure); }
    }
    public boolean available() {
        return BuiltInRegistries.SOUND_EVENT.containsKey(sound)
                && Minecraft.getInstance().getResourceManager().getResource(audioFile).isPresent();
    }
    public SpatialAudio.Voice playAt(Vec3 position, float gain) {
        if (!available()) throw new IllegalStateException("Scene music is missing: " + audioFile);
        return SpatialAudio.play(BuiltInRegistries.SOUND_EVENT.get(sound), SoundSource.RECORDS, position, gain);
    }
}
