package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Map;
import java.util.Objects;

/**
 * Standard command applier for a Scope-owned playback graph.
 *
 * <p>Definitions supply each clip's source window and default boundary policy. Commands may
 * override placement start and speed when starting a root or child playback. The applier stages
 * the complete registry and restores it when a logical step aborts.
 */
public final class ScopedPlaybackCommandApplier implements PlaybackCommandApplier {

    private final PlaybackControlPort playbackPort;
    private final PlaybackBindingProvider bindings;

    private ScopedPlaybackCommandApplier(
            PlaybackControlPort playbackPort,
            PlaybackBindingProvider bindings
    ) {
        this.playbackPort = Objects.requireNonNull(playbackPort, "playbackPort");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    public static PlaybackCommandApplierFactory factory(Map<String, PlaybackBinding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        Map<String, PlaybackBinding> definitions = Map.copyOf(bindings);
        return port -> new ScopedPlaybackCommandApplier(port, definitions::get);
    }

    public static PlaybackCommandApplierFactory factory(PlaybackBindingProvider bindings) {
        return port -> new ScopedPlaybackCommandApplier(port, bindings);
    }

    @Override
    public CommandApplyResult apply(PlaybackCommand command) {
        return applyCommand(command);
    }

    @Override
    public Stage begin() {
        PlaybackRegistrySnapshot checkpoint = playbackPort.snapshot();
        return new Stage() {
            private boolean finished;

            @Override
            public CommandApplyResult apply(PlaybackCommand command) {
                if (finished) {
                    throw new IllegalStateException("playback command stage is closed");
                }
                return applyCommand(command);
            }

            @Override
            public void commit() {
                finished = true;
            }

            @Override
            public void discard() {
                if (finished) {
                    return;
                }
                finished = true;
                playbackPort.restore(checkpoint);
            }
        };
    }

    private CommandApplyResult applyCommand(PlaybackCommand command) {
        Objects.requireNonNull(command, "command");
        try {
            return switch (command) {
                case PlaybackCommand.Play value -> play(value);
                case PlaybackCommand.Stop value -> {
                    playbackPort.stopSubtree(value.instanceId());
                    yield CommandApplyResult.accepted();
                }
                case PlaybackCommand.Pause value -> {
                    playbackPort.pauseSubtree(value.instanceId());
                    yield CommandApplyResult.accepted();
                }
                case PlaybackCommand.Resume value -> {
                    playbackPort.resumeSubtree(value.instanceId());
                    yield CommandApplyResult.accepted();
                }
                case PlaybackCommand.Seek value -> {
                    playbackPort.seek(value.instanceId(), value.sourceTime());
                    yield CommandApplyResult.accepted();
                }
                case PlaybackCommand.SetSpeed value -> {
                    playbackPort.setSpeed(value.instanceId(), value.speed());
                    yield CommandApplyResult.accepted();
                }
                case PlaybackCommand.SetBoundaryPolicy value -> {
                    playbackPort.setBoundaryPolicy(
                            value.instanceId(), value.sourceBoundaryPolicy());
                    yield CommandApplyResult.accepted();
                }
                case PlaybackCommand.Replace value -> replace(value);
            };
        } catch (IllegalArgumentException rejected) {
            throw new CommandRejectedException(rejected.getMessage());
        }
    }

    private CommandApplyResult play(PlaybackCommand.Play command) {
        PlaybackBinding binding = binding(command.definitionId())
                .withSpeed(command.speed());
        if (command.placementStart().isPresent()) {
            binding = binding.withPlacementStart(command.placementStart().orElseThrow());
        }
        PlaybackInstanceId id = command.parentId().isPresent()
                ? playbackPort.addChild(
                        command.parentId().orElseThrow(), command.definitionId(), binding)
                : playbackPort.addRoot(command.definitionId(), binding);
        return CommandApplyResult.created(id);
    }

    private CommandApplyResult replace(PlaybackCommand.Replace command) {
        PlaybackBinding binding = binding(command.definitionId());
        playbackPort.replace(command.instanceId(), command.definitionId(), binding);
        return CommandApplyResult.accepted();
    }

    private PlaybackBinding binding(String definitionId) {
        PlaybackBinding binding = bindings.binding(definitionId);
        if (binding == null) {
            throw new CommandRejectedException(
                    "unknown playback definition '" + definitionId + "'");
        }
        return binding;
    }
}
