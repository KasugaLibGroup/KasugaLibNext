package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clip source bucketing: code/scripting registrations survive a reload clear, reload-sourced clips are
 * dropped, and code wins over a resource clip of the same id — the prerequisite for letting
 * {@code animation_clips} register from files without wiping code-registered animation on {@code /reload}.
 */
class FsmAnimationClipsTest {

    private static Id rl(String path) {
        return Id.fromNamespaceAndPath("test", path);
    }

    /** A resource-reload cycle: drop the reload bucket, then replay the file-sourced clips. */
    private static void reload(FsmAnimationClips clips) {
        clips.clearResource();
    }

    @Test
    void codeClipSurvivesReloadClear() {
        FsmAnimationClips clips = new FsmAnimationClips();
        Object data = new Object();
        clips.register(rl("wheel"), ClipSampler.INSTANCE, data);

        reload(clips);

        FsmAnimationClips.Entry entry = clips.get(rl("wheel"));
        assertNotNull(entry, "a code-registered clip must survive a reload clear");
        assertSame(data, entry.data());
        assertEquals(FsmAnimationClips.ClipSource.SCRIPT, entry.source());
    }

    @Test
    void resourceClipIsDroppedByReloadClear() {
        FsmAnimationClips clips = new FsmAnimationClips();
        clips.registerResource(rl("wheel"), ClipSampler.INSTANCE, new Object());
        assertEquals(FsmAnimationClips.ClipSource.RESOURCE, clips.get(rl("wheel")).source());

        reload(clips);

        assertNull(clips.get(rl("wheel")), "a reload-sourced clip must be dropped by a reload clear");
    }

    @Test
    void reloadReplaysResourceClipsAfterClear() {
        FsmAnimationClips clips = new FsmAnimationClips();
        clips.registerResource(rl("wheel"), ClipSampler.INSTANCE, new Object());
        reload(clips);
        assertNull(clips.get(rl("wheel")));

        // the reload path re-registers the file-sourced clip after clearing
        Object replayed = new Object();
        clips.registerResource(rl("wheel"), ClipSampler.INSTANCE, replayed);
        assertSame(replayed, clips.get(rl("wheel")).data());
    }

    @Test
    void codeRegistrationWinsOverResourceForSameId() {
        FsmAnimationClips clips = new FsmAnimationClips();
        Object resourceData = new Object();
        Object scriptData = new Object();
        clips.registerResource(rl("wheel"), ClipSampler.INSTANCE, resourceData);

        // script registration replaces the resource entry
        clips.register(rl("wheel"), ClipSampler.INSTANCE, scriptData);
        assertSame(scriptData, clips.get(rl("wheel")).data());

        // a later resource write must not clobber the script entry (script wins)
        clips.registerResource(rl("wheel"), ClipSampler.INSTANCE, new Object());
        assertSame(scriptData, clips.get(rl("wheel")).data());
        assertEquals(FsmAnimationClips.ClipSource.SCRIPT, clips.get(rl("wheel")).source());
    }

    @Test
    void scriptClipIsNotLostWhenReloadReplaysSameId() {
        FsmAnimationClips clips = new FsmAnimationClips();
        Object scriptData = new Object();
        clips.register(rl("wheel"), ClipSampler.INSTANCE, scriptData);

        // a full reload cycle: clear the reload bucket, then replay the file-sourced clip of the same id
        reload(clips);
        clips.registerResource(rl("wheel"), ClipSampler.INSTANCE, new Object());

        FsmAnimationClips.Entry entry = clips.get(rl("wheel"));
        assertSame(scriptData, entry.data(), "reload replay must not shadow the code-owned clip");
        assertEquals(FsmAnimationClips.ClipSource.SCRIPT, entry.source());
    }

    @Test
    void getReadSideIsUnchanged() {
        FsmAnimationClips clips = new FsmAnimationClips();
        Object data = new Object();
        clips.register(rl("wheel"), ClipSampler.INSTANCE, data);

        FsmAnimationClips.Entry entry = clips.get(rl("wheel"));
        assertSame(ClipSampler.INSTANCE, entry.sampler(), "the sampler accessor is untouched");
        assertSame(data, entry.data(), "the data accessor is untouched");
        assertNull(clips.get(rl("missing")), "an absent id reads as null");
        assertNull(clips.get(null), "a null id reads as null");
    }

    @Test
    void removeDropsEitherSourceAndReportsPresence() {
        FsmAnimationClips clips = new FsmAnimationClips();
        clips.register(rl("script"), ClipSampler.INSTANCE, new Object());
        clips.registerResource(rl("resource"), ClipSampler.INSTANCE, new Object());

        assertTrue(clips.remove(rl("script")));
        assertTrue(clips.remove(rl("resource")));
        assertNull(clips.get(rl("script")));
        assertNull(clips.get(rl("resource")));
        assertFalse(clips.remove(rl("script")), "removing an absent id is a no-op returning false");
    }

    @Test
    void clearAllDropsEverySource() {
        FsmAnimationClips clips = new FsmAnimationClips();
        clips.register(rl("script"), ClipSampler.INSTANCE, new Object());
        clips.registerResource(rl("resource"), ClipSampler.INSTANCE, new Object());

        clips.clearAll();

        assertNull(clips.get(rl("script")));
        assertNull(clips.get(rl("resource")));
    }

    @Test
    void clearIsNowResourceOnly() {
        FsmAnimationClips clips = new FsmAnimationClips();
        clips.register(rl("script"), ClipSampler.INSTANCE, new Object());
        clips.registerResource(rl("resource"), ClipSampler.INSTANCE, new Object());

        clips.clear();

        assertNotNull(clips.get(rl("script")), "clear() must no longer wipe code-registered clips");
        assertNull(clips.get(rl("resource")));
    }

    @Test
    void bothRegistrationPathsRejectNullArguments() {
        FsmAnimationClips clips = new FsmAnimationClips();
        assertThrows(IllegalArgumentException.class,
                () -> clips.register(null, ClipSampler.INSTANCE, new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> clips.register(rl("a"), null, new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> clips.register(rl("a"), ClipSampler.INSTANCE, null));
        assertThrows(IllegalArgumentException.class,
                () -> clips.registerResource(null, ClipSampler.INSTANCE, new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> clips.registerResource(rl("a"), null, new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> clips.registerResource(rl("a"), ClipSampler.INSTANCE, null));
    }
}
