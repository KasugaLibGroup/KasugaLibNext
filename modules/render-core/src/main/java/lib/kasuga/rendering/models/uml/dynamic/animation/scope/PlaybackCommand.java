package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Closed command set accepted by the scope command transaction. */
public sealed interface PlaybackCommand permits
        PlaybackCommand.Play,
        PlaybackCommand.Stop,
        PlaybackCommand.Pause,
        PlaybackCommand.Resume,
        PlaybackCommand.Seek,
        PlaybackCommand.SetSpeed,
        PlaybackCommand.SetBoundaryPolicy,
        PlaybackCommand.Replace {

    record Play(
            String definitionId,
            Optional<PlaybackInstanceId> parentId,
            Optional<Double> placementStart,
            double speed
    ) implements PlaybackCommand {
        public Play {
            Objects.requireNonNull(definitionId, "definitionId");
            parentId = Objects.requireNonNull(parentId, "parentId");
            placementStart = Objects.requireNonNull(placementStart, "placementStart");
            placementStart.ifPresent(value -> {
                if (!Double.isFinite(value) || value < 0.0) {
                    throw new IllegalArgumentException("placementStart must be finite and non-negative");
                }
            });
            requireSpeed(speed);
        }
    }

    record Stop(PlaybackInstanceId instanceId) implements PlaybackCommand {
        public Stop {
            Objects.requireNonNull(instanceId, "instanceId");
        }
    }

    record Pause(PlaybackInstanceId instanceId) implements PlaybackCommand {
        public Pause {
            Objects.requireNonNull(instanceId, "instanceId");
        }
    }

    record Resume(PlaybackInstanceId instanceId) implements PlaybackCommand {
        public Resume {
            Objects.requireNonNull(instanceId, "instanceId");
        }
    }

    record Seek(PlaybackInstanceId instanceId, double sourceTime) implements PlaybackCommand {
        public Seek {
            Objects.requireNonNull(instanceId, "instanceId");
            if (!Double.isFinite(sourceTime) || sourceTime < 0.0) {
                throw new IllegalArgumentException("sourceTime must be finite and non-negative");
            }
        }
    }

    record SetSpeed(PlaybackInstanceId instanceId, double speed) implements PlaybackCommand {
        public SetSpeed {
            Objects.requireNonNull(instanceId, "instanceId");
            requireSpeed(speed);
        }
    }

    record SetBoundaryPolicy(
            PlaybackInstanceId instanceId,
            PlaybackBoundaryPolicy sourceBoundaryPolicy
    ) implements PlaybackCommand {
        public SetBoundaryPolicy {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(sourceBoundaryPolicy, "sourceBoundaryPolicy");
        }
    }

    record Replace(PlaybackInstanceId instanceId, String definitionId) implements PlaybackCommand {
        public Replace {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(definitionId, "definitionId");
        }
    }

    private static void requireSpeed(double speed) {
        if (!Double.isFinite(speed) || speed < 0.0) {
            throw new IllegalArgumentException("speed must be finite and non-negative");
        }
    }

    static List<Class<? extends PlaybackCommand>> kinds() {
        return List.of(
                Play.class, Stop.class, Pause.class, Resume.class, Seek.class,
                SetSpeed.class, SetBoundaryPolicy.class, Replace.class);
    }
}
