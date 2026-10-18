package lib.kasuga.rendering.effect.shader;

import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Testable priority queue behind the render-thread shader preload scheduler. */
final class ShaderPreloadSchedulerCore {
    private static final Comparator<Job> ORDER = Comparator
            .comparingInt((Job job) -> job.priority)
            .thenComparingLong(job -> job.sequence);

    private final LongSupplier nanoTime;
    private final Consumer<RuntimeException> failureHandler;
    private final PriorityQueue<Job> queue = new PriorityQueue<>(ORDER);
    private ShaderPreloadScheduler.Settings settings;
    private long nextSequence;
    private long processedJobs;
    private long cancelledJobs;
    private long failedJobs;
    private long activeFrames;
    private long overBudgetFrames;
    private long ownerDeferrals;
    private int lastFrameJobs;
    private int lastFrameOwners;
    private long lastFrameNanos;
    private long maxFrameNanos;

    ShaderPreloadSchedulerCore(LongSupplier nanoTime,
                               Consumer<RuntimeException> failureHandler,
                               ShaderPreloadScheduler.Settings settings) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    synchronized Job enqueue(
            ResourceLocation owner,
            ResourceLocation id,
            int priority,
            Runnable action
    ) {
        Job job = new Job(
                Objects.requireNonNull(owner, "owner"), Objects.requireNonNull(id, "id"),
                priority, nextSequence++,
                nanoTime.getAsLong(), Objects.requireNonNull(action, "action")
        );
        queue.add(job);
        return job;
    }

    synchronized boolean cancel(Job job) {
        if (job == null || job.state == JobState.FINISHED || job.state == JobState.CANCELLED) return false;
        boolean removed = queue.remove(job);
        job.state = JobState.CANCELLED;
        cancelledJobs++;
        return removed;
    }

    void pump() {
        ShaderPreloadScheduler.Settings frameSettings;
        synchronized (this) {
            if (queue.isEmpty()) return;
            frameSettings = settings;
        }

        long started = nanoTime.getAsLong();
        int processed = 0;
        Map<ResourceLocation, Integer> jobsByOwner = new HashMap<>();
        while (processed < frameSettings.maxJobsPerFrame()) {
            if (processed > 0 && nanoTime.getAsLong() - started >= frameSettings.frameBudgetNanos()) break;
            Job job;
            synchronized (this) {
                job = pollNext(jobsByOwner, frameSettings.maxJobsPerOwnerPerFrame());
                if (job == null) break;
                if (job.state == JobState.CANCELLED) continue;
                job.state = JobState.RUNNING;
            }

            try {
                job.action.run();
            } catch (RuntimeException exception) {
                synchronized (this) {
                    failedJobs++;
                }
                failureHandler.accept(exception);
            } finally {
                synchronized (this) {
                    if (job.state != JobState.CANCELLED) job.state = JobState.FINISHED;
                }
            }
            processed++;
            jobsByOwner.merge(job.owner, 1, Integer::sum);
        }

        long elapsed = Math.max(0L, nanoTime.getAsLong() - started);
        synchronized (this) {
            processedJobs += processed;
            activeFrames++;
            lastFrameJobs = processed;
            lastFrameOwners = jobsByOwner.size();
            lastFrameNanos = elapsed;
            maxFrameNanos = Math.max(maxFrameNanos, elapsed);
            if (elapsed > frameSettings.frameBudgetNanos()) overBudgetFrames++;
        }
    }

    synchronized void configure(ShaderPreloadScheduler.Settings value) {
        settings = Objects.requireNonNull(value, "value");
    }

    synchronized ShaderPreloadScheduler.Settings settings() {
        return settings;
    }

    synchronized ShaderPreloadScheduler.Stats stats() {
        return new ShaderPreloadScheduler.Stats(
                queue.size(), processedJobs, cancelledJobs, failedJobs, activeFrames,
                overBudgetFrames, ownerDeferrals, lastFrameJobs, lastFrameOwners,
                lastFrameNanos, maxFrameNanos
        );
    }

    private Job pollNext(Map<ResourceLocation, Integer> jobsByOwner, int ownerLimit) {
        Job first = queue.peek();
        if (first == null) return null;
        if (jobsByOwner.getOrDefault(first.owner, 0) < ownerLimit) return queue.poll();

        Job selected = null;
        for (Job candidate : queue) {
            if (jobsByOwner.getOrDefault(candidate.owner, 0) >= ownerLimit) continue;
            if (selected == null || ORDER.compare(candidate, selected) < 0) selected = candidate;
        }
        if (selected != null) {
            queue.remove(selected);
            ownerDeferrals++;
            return selected;
        }

        // Every queued owner reached its soft limit; consume spare global capacity.
        return queue.poll();
    }

    synchronized int position(Job target) {
        if (target == null || target.state != JobState.QUEUED) return 0;
        boolean found = false;
        int position = 1;
        for (Job candidate : queue) {
            if (candidate == target) found = true;
            if (ORDER.compare(candidate, target) < 0) position++;
        }
        return found ? position : 0;
    }

    synchronized long waitNanos(Job job) {
        if (job == null) return 0L;
        if (job.state != JobState.QUEUED && job.state != JobState.RUNNING) return 0L;
        return Math.max(0L, nanoTime.getAsLong() - job.queuedAtNanos);
    }

    static final class Job {
        private final ResourceLocation owner;
        private final ResourceLocation id;
        private final int priority;
        private final long sequence;
        private final long queuedAtNanos;
        private final Runnable action;
        private JobState state = JobState.QUEUED;

        private Job(ResourceLocation owner, ResourceLocation id, int priority, long sequence,
                    long queuedAtNanos, Runnable action) {
            this.owner = owner;
            this.id = id;
            this.priority = priority;
            this.sequence = sequence;
            this.queuedAtNanos = queuedAtNanos;
            this.action = action;
        }

        ResourceLocation id() {
            return id;
        }
    }

    private enum JobState {
        QUEUED,
        RUNNING,
        FINISHED,
        CANCELLED
    }
}
