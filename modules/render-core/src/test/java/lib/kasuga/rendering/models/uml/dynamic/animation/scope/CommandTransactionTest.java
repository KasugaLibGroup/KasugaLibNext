package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandTransactionTest {

    private static final PlaybackInstanceId INSTANCE =
            new PlaybackInstanceId(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1);

    @Test
    void highestPriorityScalarCommandWinsAndSamePriorityUsesLastOrdinal() {
        List<Double> applied = new ArrayList<>();
        CommandTransaction transaction = new CommandTransaction();
        List<CommandEnvelope> commands = List.of(
                envelope("event", CommandPriority.EVENT_HANDLER, 2,
                        new PlaybackCommand.SetSpeed(INSTANCE, 9.0)),
                envelope("controller-early", CommandPriority.CONTROLLER, 0,
                        new PlaybackCommand.SetSpeed(INSTANCE, 1.0)),
                envelope("controller-late", CommandPriority.CONTROLLER, 1,
                        new PlaybackCommand.SetSpeed(INSTANCE, 2.0))
        );

        CommandCommit commit = transaction.commit(commands, command -> {
            applied.add(((PlaybackCommand.SetSpeed) command).speed());
            return CommandApplyResult.accepted();
        });

        assertEquals(List.of(2.0), applied);
        assertEquals(1, commit.applied().size());
        assertEquals(2, commit.discarded().size());
    }

    @Test
    void independentScalarFieldsForOneInstanceAreAllApplied() {
        List<String> applied = new ArrayList<>();
        CommandTransaction transaction = new CommandTransaction();
        List<CommandEnvelope> commands = List.of(
                envelope("speed", CommandPriority.CONTROLLER, 0,
                        new PlaybackCommand.SetSpeed(INSTANCE, 2.0)),
                envelope("pause", CommandPriority.CONTROLLER, 1,
                        new PlaybackCommand.Pause(INSTANCE)),
                envelope("boundary", CommandPriority.CONTROLLER, 2,
                        new PlaybackCommand.SetBoundaryPolicy(
                                INSTANCE, PlaybackBoundaryPolicy.REPEAT)));

        CommandCommit commit = transaction.commit(commands, command -> {
            applied.add(command.getClass().getSimpleName());
            return CommandApplyResult.accepted();
        });

        assertEquals(List.of("SetSpeed", "Pause", "SetBoundaryPolicy"), applied);
        assertEquals(3, commit.applied().size());
        assertTrue(commit.discarded().isEmpty());
    }

    @Test
    void repeatedRequestIdAppliesOnlyOnceAndReturnsTheFirstResult() {
        List<String> applied = new ArrayList<>();
        CommandTransaction transaction = new CommandTransaction();
        CommandEnvelope first = envelope(
                "same-request", CommandPriority.CONTROLLER, 0,
                new PlaybackCommand.Stop(INSTANCE));

        CommandCommit initial = transaction.commit(
                List.of(first), command -> {
                    applied.add("initial");
                    return CommandApplyResult.accepted();
                });
        CommandCommit duplicate = transaction.commit(
                List.of(first), command -> {
                    applied.add("duplicate");
                    return CommandApplyResult.accepted();
                });

        assertEquals(List.of("initial"), applied);
        assertEquals(1, initial.applied().size());
        assertTrue(duplicate.applied().isEmpty());
        assertEquals(1, duplicate.idempotentReuses());
    }

    @Test
    void rejectedCommandsDoNotBlockIndependentCommands() {
        CommandTransaction transaction = new CommandTransaction();
        List<CommandEnvelope> commands = List.of(
                envelope("reject", CommandPriority.CONTROLLER, 0,
                        new PlaybackCommand.Stop(INSTANCE)),
                envelope("accept", CommandPriority.CONTROLLER, 1,
                        new PlaybackCommand.SetSpeed(INSTANCE, 0.5))
        );

        CommandCommit commit = transaction.commit(commands, command -> {
            if (command instanceof PlaybackCommand.Stop) {
                throw new CommandRejectedException("instance missing");
            }
            return CommandApplyResult.accepted();
        });

        assertEquals(1, commit.rejected().size());
        assertEquals("reject", commit.rejected().getFirst().requestId());
        assertEquals(1, commit.applied().size());
        assertEquals("accept", commit.applied().getFirst().requestId());
        assertFalse(commit.aborted());
    }

    @Test
    void internalFailureMarksTheCommitAborted() {
        CommandTransaction transaction = new CommandTransaction();

        CommandCommit commit = transaction.commit(
                List.of(envelope("boom", CommandPriority.CONTROLLER, 0,
                        new PlaybackCommand.SetSpeed(INSTANCE, 1.0))),
                command -> {
                    throw new IllegalStateException("broken staging");
                });

        assertTrue(commit.aborted());
        assertTrue(commit.applied().isEmpty());
        assertTrue(commit.rejected().isEmpty());
    }

    @Test
    void internalFailureDiscardsResultsAndCanBeRetriedWithTheSameRequestId() {
        CommandTransaction transaction = new CommandTransaction();
        String requestId = "a-applied-before-abort";
        int[] applications = {0};

        CommandCommit aborted = transaction.commit(
                List.of(
                        envelope(requestId, CommandPriority.CONTROLLER, 0,
                                new PlaybackCommand.Stop(INSTANCE)),
                        envelope("z-internal-failure", CommandPriority.CONTROLLER, 1,
                                new PlaybackCommand.Resume(INSTANCE))),
                command -> {
                    applications[0]++;
                    if (command instanceof PlaybackCommand.Resume) {
                        throw new IllegalStateException("step staging failed");
                    }
                    return CommandApplyResult.accepted();
                });

        assertTrue(aborted.aborted());
        assertEquals(2, applications[0]);

        CommandCommit retried = transaction.commit(
                List.of(envelope(requestId, CommandPriority.CONTROLLER, 0,
                        new PlaybackCommand.Stop(INSTANCE))),
                command -> {
                    applications[0]++;
                    return CommandApplyResult.accepted();
                });

        assertFalse(retried.aborted());
        assertEquals(1, retried.applied().size());
        assertEquals(3, applications[0],
                "an internally aborted transaction must not make its request id permanently idempotent");
    }

    @Test
    void internalFailureDiscardsTheApplierStage() {
        CommandTransaction transaction = new CommandTransaction();
        TransactionalRecordingApplier applier = new TransactionalRecordingApplier();

        CommandCommit commit = transaction.commit(
                List.of(
                        envelope("a-first", CommandPriority.CONTROLLER, 0,
                                new PlaybackCommand.Stop(INSTANCE)),
                        envelope("z-fails", CommandPriority.CONTROLLER, 1,
                                new PlaybackCommand.Resume(INSTANCE))),
                applier);

        assertTrue(commit.aborted());
        assertEquals(List.of("a-first", "z-fails"), applier.staged);
        assertEquals(0, applier.commits);
        assertEquals(1, applier.discards);
    }

    private static CommandEnvelope envelope(
            String requestId,
            CommandPriority priority,
            int ordinal,
            PlaybackCommand command
    ) {
        return new CommandEnvelope(requestId, priority, 0, ordinal, command);
    }

    private static final class TransactionalRecordingApplier
            implements PlaybackCommandApplier {
        private final List<String> staged = new ArrayList<>();
        private int commits;
        private int discards;

        @Override
        public CommandApplyResult apply(PlaybackCommand command) {
            throw new AssertionError("direct apply must not be used");
        }

        @Override
        public Stage begin() {
            return new Stage() {
                @Override
                public CommandApplyResult apply(PlaybackCommand command) {
                    String request = command instanceof PlaybackCommand.Stop
                            ? "a-first"
                            : "z-fails";
                    staged.add(request);
                    if (command instanceof PlaybackCommand.Resume) {
                        throw new IllegalStateException("staging failed");
                    }
                    return CommandApplyResult.accepted();
                }

                @Override
                public void commit() {
                    commits++;
                }

                @Override
                public void discard() {
                    discards++;
                }
            };
        }
    }
}
