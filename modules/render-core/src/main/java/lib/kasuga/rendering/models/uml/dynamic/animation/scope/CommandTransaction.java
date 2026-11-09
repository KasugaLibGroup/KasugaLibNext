package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stable, idempotent submission boundary for playback commands. */
public final class CommandTransaction {

    private static final int MAX_REMEMBERED_RESULTS = 4096;

    private static final Comparator<CommandEnvelope> STABLE_ORDER =
            Comparator.comparingInt((CommandEnvelope value) -> value.priority().weight())
                    .reversed()
                    .thenComparingInt(CommandEnvelope::originOrdinal)
                    .thenComparingInt(CommandEnvelope::commandOrdinal)
                    .thenComparing(CommandEnvelope::requestId);

    private final Map<String, CommandResult> resultsByRequestId =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, CommandResult> eldest
                ) {
                    return size() > MAX_REMEMBERED_RESULTS;
                }
            };

    public CommandCommit commit(
            List<CommandEnvelope> envelopes,
            PlaybackCommandApplier applier
    ) {
        Objects.requireNonNull(envelopes, "envelopes");
        Objects.requireNonNull(applier, "applier");

        List<CommandResult> applied = new ArrayList<>();
        List<CommandResult> rejected = new ArrayList<>();
        List<CommandResult> duplicates = new ArrayList<>();
        List<CommandResult> discarded = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int idempotentReuses = 0;
        Map<String, CommandResult> stagedResults = new HashMap<>(resultsByRequestId);

        List<CommandEnvelope> ordered = envelopes.stream().sorted(STABLE_ORDER).toList();
        Set<String> scalarWinners = scalarWinners(envelopes);
        PlaybackCommandApplier.Stage stage = null;
        try {
            stage = applier.begin();
            for (CommandEnvelope envelope : ordered) {
                CommandResult previous = stagedResults.get(envelope.requestId());
                if (previous != null) {
                    duplicates.add(previous);
                    idempotentReuses++;
                    continue;
                }
                if (!seen.add(envelope.requestId())) {
                    discarded.add(new CommandResult(
                            envelope.requestId(), java.util.Optional.empty(), "duplicate in batch"));
                    continue;
                }
                if (isScalarRelease(envelope.command())) {
                    if (!scalarWinners.contains(envelope.requestId())) {
                        discarded.add(new CommandResult(
                                envelope.requestId(), java.util.Optional.empty(), "superseded scalar command"));
                        continue;
                    }
                }
                try {
                    CommandApplyResult result = stage.apply(envelope.command());
                    CommandResult recorded = new CommandResult(
                            envelope.requestId(), result.assignedInstanceId(), "");
                    stagedResults.put(envelope.requestId(), recorded);
                    applied.add(recorded);
                } catch (CommandRejectedException expected) {
                    CommandResult recorded = new CommandResult(
                            envelope.requestId(), java.util.Optional.empty(), expected.getMessage());
                    stagedResults.put(envelope.requestId(), recorded);
                    rejected.add(recorded);
                }
            }
            stage.commit();
            resultsByRequestId.clear();
            resultsByRequestId.putAll(stagedResults);
            return new CommandCommit(applied, rejected, duplicates, idempotentReuses, discarded, false, null);
        } catch (RuntimeException internalFailure) {
            if (stage != null) {
                try {
                    stage.discard();
                } catch (RuntimeException discardFailure) {
                    internalFailure.addSuppressed(discardFailure);
                }
            }
            return CommandCommit.aborted(internalFailure);
        }
    }

    private static Set<String> scalarWinners(List<CommandEnvelope> envelopes) {
        Map<ScalarKey, CommandEnvelope> winners = new HashMap<>();
        for (CommandEnvelope envelope : envelopes) {
            if (!isScalarRelease(envelope.command())) {
                continue;
            }
            ScalarKey key = scalarKey(envelope.command());
            winners.merge(key, envelope, (current, candidate) -> {
                int priority = Integer.compare(
                        candidate.priority().weight(), current.priority().weight());
                if (priority != 0) {
                    return priority > 0 ? candidate : current;
                }
                int origin = Integer.compare(candidate.originOrdinal(), current.originOrdinal());
                if (origin != 0) {
                    return origin > 0 ? candidate : current;
                }
                int command = Integer.compare(candidate.commandOrdinal(), current.commandOrdinal());
                if (command != 0) {
                    return command > 0 ? candidate : current;
                }
                return candidate.requestId().compareTo(current.requestId()) > 0 ? candidate : current;
            });
        }
        Set<String> result = new HashSet<>();
        winners.values().forEach(envelope -> result.add(envelope.requestId()));
        return result;
    }

    private static boolean isScalarRelease(PlaybackCommand command) {
        return command instanceof PlaybackCommand.SetSpeed
                || command instanceof PlaybackCommand.Pause
                || command instanceof PlaybackCommand.Resume
                || command instanceof PlaybackCommand.SetBoundaryPolicy;
    }

    private static ScalarKey scalarKey(PlaybackCommand command) {
        return switch (command) {
            case PlaybackCommand.SetSpeed value ->
                    new ScalarKey(value.instanceId(), ScalarField.SPEED);
            case PlaybackCommand.Pause value ->
                    new ScalarKey(value.instanceId(), ScalarField.PAUSE_STATE);
            case PlaybackCommand.Resume value ->
                    new ScalarKey(value.instanceId(), ScalarField.PAUSE_STATE);
            case PlaybackCommand.SetBoundaryPolicy value ->
                    new ScalarKey(value.instanceId(), ScalarField.BOUNDARY_POLICY);
            default -> throw new IllegalArgumentException("not a scalar release command");
        };
    }

    private enum ScalarField {
        SPEED,
        PAUSE_STATE,
        BOUNDARY_POLICY
    }

    private record ScalarKey(PlaybackInstanceId instanceId, ScalarField field) {
        private ScalarKey {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(field, "field");
        }
    }
}
