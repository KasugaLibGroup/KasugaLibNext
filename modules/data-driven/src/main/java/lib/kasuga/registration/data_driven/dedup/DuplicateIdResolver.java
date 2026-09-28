package lib.kasuga.registration.data_driven.dedup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure duplicate-id resolver for the data-driven loader.
 *
 * <p>Duplicate ids must be handled before any registration side effect happens: Minecraft's
 * {@code MappedRegistry.register} does not reject a duplicate key (it only calls
 * {@code Util.pauseInIde}, a no-op without a debugger), which leaves {@code byLocation} overwritten
 * and a phantom entry behind in {@code byId}. Applying twice is therefore not an option.
 *
 * <p>This resolver takes a sequence of candidates (already ordered by the loader's load order) and
 * returns the winners together with the dropped entries and the entry that superseded each of them.
 * Resolution is <em>last-wins</em>: the later candidate for a given {@code (typeName, identity)} key
 * wins, matching the loader's contract that a later source file/array entry overrides earlier ones.
 *
 * <p>An identity of {@code null} opts a candidate out of duplicate detection: it is always a winner
 * and never collides with anything. Candidates with different {@code typeName} never collide even
 * when their identities are equal, because each type is a separate registration space.
 *
 * <p>The class is a pure function of its inputs (no Minecraft types, no I/O), so its behaviour can be
 * locked down by plain JVM tests.
 */
public final class DuplicateIdResolver {

    /**
     * One parsed definition to consider for duplicate resolution.
     *
     * @param typeName   the registration type field (e.g. {@code "blocks"}); the collision space
     * @param identity   the effective id within {@code typeName}, or {@code null} to opt out
     * @param sourcePath the content file the entry came from, relative to {@code data/<mod>/}, used
     *                   for diagnostics
     * @param payload    opaque payload handed back to the caller for the winning candidates
     */
    public record Candidate(String typeName, String identity, String sourcePath, Object payload) {}

    /**
     * A candidate that lost to a later candidate with the same {@code (typeName, identity)} key.
     *
     * @param loser  the superseded candidate
     * @param winner the later candidate that wins
     */
    public record Conflict(Candidate loser, Candidate winner) {

        /**
         * Human-readable reason for the resolution, used in logs and loading errors.
         *
         * @return a constant explanation of the last-wins policy
         */
        public String reason() {
            return "duplicate id superseded by a later definition (last-wins)";
        }
    }

    /**
     * Outcome of {@link #resolve(List)}.
     *
     * @param winners   candidates that survive, in their original relative order
     * @param conflicts dropped candidates paired with their winners, in the order they were dropped
     */
    public record Result(List<Candidate> winners, List<Conflict> conflicts) {}

    /**
     * Resolves duplicate identities in {@code candidates} using last-wins.
     *
     * @param candidates the parsed entries in load order; not modified
     * @return the surviving candidates and the dropped ones with their winners
     */
    public static Result resolve(List<Candidate> candidates) {
        // Index of the last candidate for each (typeName, identity) key.
        Map<String, Integer> lastIndexByKey = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (candidate.identity() == null) {
                continue;
            }
            lastIndexByKey.put(key(candidate.typeName(), candidate.identity()), i);
        }

        List<Candidate> winners = new ArrayList<>(candidates.size());
        List<Conflict> conflicts = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (candidate.identity() == null) {
                winners.add(candidate);
                continue;
            }
            Integer last = lastIndexByKey.get(key(candidate.typeName(), candidate.identity()));
            if (last == null || last.intValue() == i) {
                winners.add(candidate);
            } else {
                conflicts.add(new Conflict(candidate, candidates.get(last)));
            }
        }
        return new Result(List.copyOf(winners), List.copyOf(conflicts));
    }

    /**
     * Message describing one conflict, naming the id, its type and both source files so the loser can
     * be traced back to its origin.
     *
     * @param conflict the conflict to describe
     * @return a message containing the identity, type and both source paths
     */
    public static String describe(Conflict conflict) {
        Candidate loser = conflict.loser();
        Candidate winner = conflict.winner();
        return "Duplicate id '" + loser.identity() + "' in field '" + loser.typeName()
                + "': entry from '" + loser.sourcePath() + "' is superseded by the later entry from '"
                + winner.sourcePath() + "' (" + conflict.reason() + "); the earlier entry was not applied";
    }

    /**
     * Wraps {@link #describe(Conflict)} in a throwable, suitable for recording in the loader's
     * per-mod loading-error list.
     *
     * @param conflict the conflict to record
     * @return an exception whose message names the id and both sources
     */
    public static Throwable toLoadingError(Conflict conflict) {
        return new IllegalStateException(describe(conflict));
    }

    private static String key(String typeName, String identity) {
        return typeName + '\u0000' + identity;
    }

    private DuplicateIdResolver() {}
}
