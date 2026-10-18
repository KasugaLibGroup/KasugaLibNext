package lib.kasuga.rendering.effect.shader;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPreloadSchedulerCoreTest {

    @Test
    void ordersByPriorityThenRegistrationSequence() {
        AtomicLong clock = new AtomicLong();
        List<String> executed = new ArrayList<>();
        ShaderPreloadSchedulerCore scheduler = scheduler(clock, 100, 10);

        scheduler.enqueue(owner("shared"), id("normal_first"), 10, () -> executed.add("normal-first"));
        scheduler.enqueue(owner("shared"), id("eager"), -10, () -> executed.add("eager"));
        scheduler.enqueue(owner("shared"), id("normal_second"), 10, () -> executed.add("normal-second"));
        scheduler.pump();

        assertEquals(List.of("eager", "normal-first", "normal-second"), executed);
        assertEquals(3, scheduler.stats().processedJobs());
        assertEquals(0, scheduler.stats().queuedJobs());
    }

    @Test
    void stopsStartingJobsAfterFrameBudgetIsSpent() {
        AtomicLong clock = new AtomicLong();
        List<String> executed = new ArrayList<>();
        ShaderPreloadSchedulerCore scheduler = scheduler(clock, 5, 10);

        scheduler.enqueue(owner("shared"), id("expensive"), 0, () -> {
            executed.add("expensive");
            clock.addAndGet(6);
        });
        scheduler.enqueue(owner("shared"), id("next_frame"), 0, () -> executed.add("next-frame"));

        scheduler.pump();
        assertEquals(List.of("expensive"), executed);
        assertEquals(1, scheduler.stats().queuedJobs());
        assertEquals(1, scheduler.stats().overBudgetFrames());

        scheduler.pump();
        assertEquals(List.of("expensive", "next-frame"), executed);
        assertEquals(0, scheduler.stats().queuedJobs());
    }

    @Test
    void cancelledJobIsRemovedBeforeItCanRun() {
        AtomicLong clock = new AtomicLong();
        List<String> executed = new ArrayList<>();
        ShaderPreloadSchedulerCore scheduler = scheduler(clock, 100, 10);
        ShaderPreloadSchedulerCore.Job cancelled = scheduler.enqueue(
                owner("shared"), id("cancelled"), -100, () -> executed.add("cancelled")
        );
        scheduler.enqueue(owner("shared"), id("kept"), 0, () -> executed.add("kept"));

        assertEquals(1, scheduler.position(cancelled));
        assertTrue(scheduler.cancel(cancelled));
        scheduler.pump();

        assertEquals(List.of("kept"), executed);
        assertEquals(1, scheduler.stats().cancelledJobs());
    }

    @Test
    void givesEachOwnerAChanceBeforeReusingItsSoftQuota() {
        AtomicLong clock = new AtomicLong();
        List<String> executed = new ArrayList<>();
        ShaderPreloadSchedulerCore scheduler = scheduler(clock, 100, 4);

        for (int index = 0; index < 4; index++) {
            int current = index;
            scheduler.enqueue(owner("busy"), id("busy_" + index), 0,
                    () -> executed.add("busy-" + current));
        }
        scheduler.enqueue(owner("guest"), id("guest"), 10, () -> executed.add("guest"));

        scheduler.pump();

        assertEquals(List.of("busy-0", "busy-1", "guest", "busy-2"), executed);
        assertEquals(2, scheduler.stats().lastFrameOwners());
        assertEquals(1, scheduler.stats().ownerDeferrals());
    }

    private static ShaderPreloadSchedulerCore scheduler(AtomicLong clock, long budget, int jobs) {
        return new ShaderPreloadSchedulerCore(
                clock::get,
                exception -> { throw exception; },
                new ShaderPreloadScheduler.Settings(budget, jobs, 2)
        );
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_scheduler_test", path);
    }

    private static ResourceLocation owner(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_scheduler_owner", path);
    }
}
