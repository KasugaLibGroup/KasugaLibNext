package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derives playback-local time recursively from one root timeline step.
 *
 * <p>This class is stateless with respect to time. It never advances the root and never changes
 * registry lifecycle state.
 */
public final class PlaybackTimeGraph {

    public List<PlaybackTimeResolution> resolve(
            PlaybackRegistry registry,
            double previousRootLocalTime,
            double currentRootLocalTime
    ) {
        Objects.requireNonNull(registry, "registry");
        return resolve(registry.snapshot(), previousRootLocalTime, currentRootLocalTime);
    }

    public List<PlaybackTimeResolution> resolve(
            PlaybackRegistrySnapshot snapshot,
            double previousRootLocalTime,
            double currentRootLocalTime
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireFinite(previousRootLocalTime, "previousRootLocalTime");
        requireFinite(currentRootLocalTime, "currentRootLocalTime");

        Resolver resolver = new Resolver(snapshot);
        List<PlaybackTimeResolution> resolved = new ArrayList<>(snapshot.instances().size());
        for (PlaybackInstanceState instance : snapshot.instances()) {
            resolved.add(resolver.resolve(instance, previousRootLocalTime, currentRootLocalTime));
        }
        return List.copyOf(resolved);
    }

    private static final class Resolver {

        private final PlaybackRegistrySnapshot snapshot;
        private final Map<PlaybackInstanceId, PlaybackTimeResolution> resolved = new HashMap<>();
        private final Set<PlaybackInstanceId> visiting = new HashSet<>();

        private Resolver(PlaybackRegistrySnapshot snapshot) {
            this.snapshot = snapshot;
        }

        private PlaybackTimeResolution resolve(
                PlaybackInstanceState instance,
                double previousRootLocalTime,
                double currentRootLocalTime
        ) {
            PlaybackTimeResolution existing = resolved.get(instance.id());
            if (existing != null) {
                return existing;
            }
            if (!visiting.add(instance.id())) {
                throw new IllegalArgumentException(
                        "playback parent graph contains a cycle at " + instance.id());
            }

            PlaybackTimeResolution parent = null;
            double effectivePreviousRoot = previousRootLocalTime
                    - instance.accumulatedPauseSeconds();
            double effectiveCurrentRoot = currentRootLocalTime
                    - instance.accumulatedPauseSeconds();
            if (instance.paused()) {
                double pausedRoot = instance.pausedAtRootTime().orElse(currentRootLocalTime)
                        - instance.accumulatedPauseSeconds();
                effectivePreviousRoot = pausedRoot;
                effectiveCurrentRoot = pausedRoot;
            }
            if (instance.parentId().isPresent()) {
                PlaybackInstanceId parentId = instance.parentId().orElseThrow();
                PlaybackInstanceState parentState = snapshot.find(parentId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "unknown parent playback instance: " + parentId));
                parent = resolve(parentState, effectivePreviousRoot, effectiveCurrentRoot);
            }

            double previousParentLocal = parent == null
                    ? effectivePreviousRoot : parent.previousSourceLocalTime();
            double currentParentLocal = parent == null
                    ? effectiveCurrentRoot : parent.currentSourceLocalTime();
            PlaybackTimeResolution result = map(
                    instance,
                    previousParentLocal,
                    currentParentLocal,
                    parent == null || parent.active(),
                    parent != null && parent.completed());
            visiting.remove(instance.id());
            resolved.put(instance.id(), result);
            return result;
        }

        private static PlaybackTimeResolution map(
                PlaybackInstanceState instance,
                double previousParentLocal,
                double currentParentLocal,
                boolean parentActive,
                boolean parentCompleted
        ) {
            PlaybackBinding binding = instance.binding();
            double placementEnd = binding.placementEnd();
            boolean placementActive = currentParentLocal >= binding.placementStart()
                    && currentParentLocal < placementEnd;
            boolean placementCompleted = currentParentLocal >= placementEnd;
            double previousElapsed = elapsedWithinPlacement(binding, previousParentLocal);
            double currentElapsed = elapsedWithinPlacement(binding, currentParentLocal);
            double previousUnwrapped = binding.sourceStart() + binding.speed() * previousElapsed;
            double currentUnwrapped = binding.sourceStart() + binding.speed() * currentElapsed;

            SourceTime previous = sourceTime(binding, previousUnwrapped);
            SourceTime current = sourceTime(binding, currentUnwrapped);
            long loopCrossings = loopCrossings(binding, previousUnwrapped, currentUnwrapped);
            boolean sourceActive = switch (binding.boundaryPolicy()) {
                case STOP -> currentUnwrapped < binding.sourceEnd();
                case HOLD_LAST -> true;
                case REPEAT, PING_PONG -> true;
            };
            boolean sourceCompleted = currentUnwrapped >= binding.sourceEnd();
            boolean completed = placementCompleted
                    || parentCompleted
                    || (binding.boundaryPolicy() == PlaybackBoundaryPolicy.STOP && sourceCompleted);
            boolean active = parentActive
                    && placementActive
                    && sourceActive
                    && !instance.paused();

            return new PlaybackTimeResolution(
                    instance.id(),
                    instance.definitionId(),
                    instance.parentId(),
                    previousParentLocal,
                    currentParentLocal,
                    previous.localTime(),
                    current.localTime(),
                    previousUnwrapped,
                    currentUnwrapped,
                    active,
                    loopCrossings,
                    current.direction(),
                    completed);
        }

        private static double elapsedWithinPlacement(
                PlaybackBinding binding,
                double parentLocalTime
        ) {
            double clamped = Math.max(
                    binding.placementStart(),
                    Math.min(parentLocalTime, binding.placementEnd()));
            return clamped - binding.placementStart();
        }

        private static SourceTime sourceTime(PlaybackBinding binding, double unwrapped) {
            return switch (binding.boundaryPolicy()) {
                case STOP, HOLD_LAST -> new SourceTime(
                        Math.max(
                                binding.sourceStart(),
                                Math.min(unwrapped, binding.sourceEnd())),
                        1);
                case REPEAT -> repeatSourceTime(binding, unwrapped);
                case PING_PONG -> pingPongSourceTime(binding, unwrapped);
            };
        }

        private static SourceTime repeatSourceTime(PlaybackBinding binding, double unwrapped) {
            if (unwrapped <= binding.sourceStart()) {
                return new SourceTime(binding.sourceStart(), 1);
            }
            double phase = (unwrapped - binding.sourceStart()) / binding.sourceDuration();
            double remainder = phase - Math.floor(phase);
            double normalized = remainder <= 1.0e-12 ? 0.0 : remainder;
            return new SourceTime(
                    binding.sourceStart() + normalized * binding.sourceDuration(),
                    1);
        }

        private static SourceTime pingPongSourceTime(
                PlaybackBinding binding,
                double unwrapped
        ) {
            if (unwrapped <= binding.sourceStart()) {
                return new SourceTime(binding.sourceStart(), 1);
            }
            double phase = (unwrapped - binding.sourceStart()) / binding.sourceDuration();
            long cycle = (long) Math.floor(phase);
            double remainder = phase - cycle;
            if ((cycle & 1L) == 0L) {
                return new SourceTime(
                        binding.sourceStart() + remainder * binding.sourceDuration(),
                        1);
            }
            return new SourceTime(
                    binding.sourceEnd() - remainder * binding.sourceDuration(),
                    -1);
        }

        private static long loopCrossings(
                PlaybackBinding binding,
                double previousUnwrapped,
                double currentUnwrapped
        ) {
            if (binding.boundaryPolicy() == PlaybackBoundaryPolicy.STOP
                    || binding.boundaryPolicy() == PlaybackBoundaryPolicy.HOLD_LAST
                    || binding.speed() == 0.0) {
                return 0;
            }
            long previousCycle = cycleIndex(binding, previousUnwrapped);
            long currentCycle = cycleIndex(binding, currentUnwrapped);
            return Math.abs(currentCycle - previousCycle);
        }

        private static long cycleIndex(PlaybackBinding binding, double unwrapped) {
            if (unwrapped <= binding.sourceStart()) {
                return 0;
            }
            return (long) Math.floor(
                    (unwrapped - binding.sourceStart()) / binding.sourceDuration());
        }

    }

    private record SourceTime(double localTime, int direction) {
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
