package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.data_driven.dedup.DuplicateIdResolver;
import lib.kasuga.registration.data_driven.dedup.DuplicateIdResolver.Candidate;
import lib.kasuga.registration.data_driven.dedup.DuplicateIdResolver.Conflict;
import lib.kasuga.registration.data_driven.dedup.DuplicateIdResolver.Result;
import lib.kasuga.registration.data_driven.dedup.EffectiveId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM tests for {@link DuplicateIdResolver}: the side-effect-free core that decides which parsed
 * entries may reach the apply phase.
 *
 * <p>Collision key is {@code (typeName, identity)}, resolution is last-wins in the supplied sequence.
 * Candidates carry their provenance (the source file path relative to {@code data/<mod>/}) so a
 * conflict can be reported with both the winning and the losing source.
 */
class DuplicateIdResolverTest {

    @Test
    void lastEntryOfTheSameArrayWins() {
        Candidate first = candidate("blocks", "mod:foo", "blocks.json", "first");
        Candidate second = candidate("blocks", "mod:foo", "blocks.json", "second");

        Result result = DuplicateIdResolver.resolve(List.of(first, second));

        assertEquals(List.of(second), result.winners(), "the later entry wins");
        assertEquals(1, result.conflicts().size());
        Conflict conflict = result.conflicts().get(0);
        assertSame(first, conflict.loser());
        assertSame(second, conflict.winner());
    }

    @Test
    void lastEntryAcrossSourceFilesWins() {
        Candidate earlyFile = candidate("blocks", "mod:foo", "a/blocks.json", "early");
        Candidate lateFile = candidate("blocks", "mod:foo", "b/blocks.json", "late");

        Result result = DuplicateIdResolver.resolve(List.of(earlyFile, lateFile));

        assertEquals(List.of(lateFile), result.winners());
        assertEquals("b/blocks.json", result.conflicts().get(0).winner().sourcePath());
        assertEquals("a/blocks.json", result.conflicts().get(0).loser().sourcePath());
    }

    /**
     * Locks rule C: "last" is the position in the supplied sequence, not a lexicographic property of
     * the source path. Candidates supplied in load order therefore mean the loader's order
     * (index files by path, their {@code sources} arrays, then the type field arrays) defines the
     * winner — a resolver that sorted internally would fail this test.
     */
    @Test
    void lastMeansLastInSequenceNotLexicographicallyLastSource() {
        Candidate fromZ = candidate("blocks", "mod:foo", "z/blocks.json", "z");
        Candidate fromA = candidate("blocks", "mod:foo", "a/blocks.json", "a");

        Result result = DuplicateIdResolver.resolve(List.of(fromZ, fromA));

        assertSame(fromA, result.winners().get(0),
                "the later candidate in load order wins even when its path sorts first");
        assertSame(fromZ, result.conflicts().get(0).loser());
    }

    @Test
    void differentTypesDoNotCollide() {
        Candidate block = candidate("blocks", "mod:foo", "blocks.json", "block");
        Candidate item = candidate("items", "mod:foo", "items.json", "item");

        Result result = DuplicateIdResolver.resolve(List.of(block, item));

        assertEquals(List.of(block, item), result.winners());
        assertTrue(result.conflicts().isEmpty(), "same id in two types is not a conflict");
    }

    @Test
    void nullIdentityOptsOutOfDuplicateDetection() {
        Candidate first = candidate("effects", null, "effects.json", "first");
        Candidate second = candidate("effects", null, "effects.json", "second");

        Result result = DuplicateIdResolver.resolve(List.of(first, second));

        assertEquals(List.of(first, second), result.winners());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void loserIsAbsentFromWinners() {
        Candidate first = candidate("blocks", "mod:foo", "a.json", "first");
        Candidate second = candidate("blocks", "mod:foo", "b.json", "second");

        Result result = DuplicateIdResolver.resolve(List.of(first, second));

        assertFalse(result.winners().contains(first), "the superseded entry must not reach apply");
        assertTrue(result.winners().contains(second));
    }

    @Test
    void describeNamesIdTypeAndBothSources() {
        Candidate loser = candidate("blocks", "mod:foo", "early.json", "loser");
        Candidate winner = candidate("blocks", "mod:foo", "late.json", "winner");
        Conflict conflict = new Conflict(loser, winner);

        String message = DuplicateIdResolver.describe(conflict);

        assertTrue(message.contains("mod:foo"), message);
        assertTrue(message.contains("blocks"), message);
        assertTrue(message.contains("early.json"), message);
        assertTrue(message.contains("late.json"), message);
        assertTrue(message.contains(conflict.reason()), message);
    }

    @Test
    void toLoadingErrorCarriesTheDescribeMessage() {
        Candidate loser = candidate("items", "mod:bar", "one.json", "loser");
        Candidate winner = candidate("items", "mod:bar", "two.json", "winner");

        Throwable error = DuplicateIdResolver.toLoadingError(new Conflict(loser, winner));

        assertEquals(DuplicateIdResolver.describe(new Conflict(loser, winner)), error.getMessage());
        assertTrue(error.getMessage().contains("one.json") && error.getMessage().contains("two.json"));
    }

    @Test
    void winnersKeepTheirOriginalRelativeOrder() {
        Candidate a = candidate("blocks", "mod:a", "f.json", "a");
        Candidate b = candidate("blocks", "mod:b", "f.json", "b");
        Candidate c = candidate("blocks", "mod:c", "f.json", "c");

        Result result = DuplicateIdResolver.resolve(List.of(a, b, c));

        assertEquals(List.of(a, b, c), result.winners());
    }

    @Test
    void resolvesSeveralIndependentConflicts() {
        Candidate a1 = candidate("blocks", "mod:a", "f1.json", "a1");
        Candidate b1 = candidate("blocks", "mod:b", "f1.json", "b1");
        Candidate a2 = candidate("blocks", "mod:a", "f2.json", "a2");
        Candidate b2 = candidate("blocks", "mod:b", "f2.json", "b2");

        Result result = DuplicateIdResolver.resolve(List.of(a1, b1, a2, b2));

        assertEquals(List.of(a2, b2), result.winners());
        assertEquals(2, result.conflicts().size());
        assertEquals("f2.json", result.conflicts().get(0).winner().sourcePath());
        assertEquals("f1.json", result.conflicts().get(1).loser().sourcePath());
    }

    @Test
    void normalizedNamespaceSpellingsCollide() {
        String modId = "mymod";
        Candidate bare = candidate("blocks", EffectiveId.of(modId, "foo"), "a.json", "bare");
        Candidate explicitMinecraft =
                candidate("blocks", EffectiveId.of(modId, "minecraft:foo"), "b.json", "minecraft");

        Result result = DuplicateIdResolver.resolve(List.of(bare, explicitMinecraft));

        assertEquals(1, result.conflicts().size(), "bare and minecraft:-prefixed ids are the same location");
        assertSame(explicitMinecraft, result.winners().get(0));
    }

    @Test
    void emptyInputYieldsEmptyResult() {
        Result result = DuplicateIdResolver.resolve(List.of());

        assertTrue(result.winners().isEmpty());
        assertTrue(result.conflicts().isEmpty());
    }

    private static Candidate candidate(String typeName, String identity, String sourcePath, Object payload) {
        return new Candidate(typeName, identity, sourcePath, payload);
    }
}
