package lib.kasuga.rendering.effect.shader;

/** Aggregate CPU-side source preparation metrics for generated shaders. */
public record ShaderPreparationStats(
        int workers,
        int queueCapacity,
        int activeJobs,
        int queuedJobs,
        long completedJobs,
        long cancelledJobs,
        long rejectedJobs,
        long failedJobs,
        long totalNanos,
        long maxNanos,
        int requestedWorkers,
        int availableProcessors
) {}
