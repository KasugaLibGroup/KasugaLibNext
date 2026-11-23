package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/**
 * Creates a command applier after a Scope has constructed its playback control port.
 *
 * <p>Proxy compositions expose this factory instead of a pre-bound applier so reusable
 * implementations can mutate the owning Scope's playback graph.
 */
@FunctionalInterface
public interface PlaybackCommandApplierFactory {

    PlaybackCommandApplier create(PlaybackControlPort playbackPort);
}
