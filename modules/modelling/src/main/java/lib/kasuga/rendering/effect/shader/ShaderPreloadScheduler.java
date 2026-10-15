package lib.kasuga.rendering.effect.shader;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Frame-budgeted render-thread scheduler for shaders registered outside a resource reload.
 * A single compile cannot be preempted, so the budget limits when the next queued job starts.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class ShaderPreloadScheduler {
    public static final long DEFAULT_FRAME_BUDGET_NANOS = 2_000_000L;
    public static final int DEFAULT_MAX_JOBS_PER_FRAME = 4;
    public static final int DEFAULT_MAX_JOBS_PER_OWNER_PER_FRAME = 2;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Settings DEFAULT_SETTINGS = new Settings(
            DEFAULT_FRAME_BUDGET_NANOS,
            DEFAULT_MAX_JOBS_PER_FRAME,
            DEFAULT_MAX_JOBS_PER_OWNER_PER_FRAME
    );
    private static final ShaderPreloadSchedulerCore CORE = new ShaderPreloadSchedulerCore(
            System::nanoTime,
            exception -> LOGGER.error("Unhandled shader preload job failure", exception),
            DEFAULT_SETTINGS
    );

    private ShaderPreloadScheduler() {}

    /** Lower priority values are compiled first, matching render pipeline priority semantics. */
    @ApiStatus.Internal
    public static void configure(
            long frameBudgetNanos,
            int maxJobsPerFrame,
            int maxJobsPerOwnerPerFrame
    ) {
        CORE.configure(new Settings(frameBudgetNanos, maxJobsPerFrame, maxJobsPerOwnerPerFrame));
    }

    @ApiStatus.Internal
    public static void configure(Settings settings) {
        CORE.configure(settings);
    }

    @ApiStatus.Internal
    public static void resetSettings() {
        CORE.configure(DEFAULT_SETTINGS);
    }

    public static Settings settings() {
        return CORE.settings();
    }

    public static Stats stats() {
        return CORE.stats();
    }

    @SubscribeEvent
    @ApiStatus.Internal
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        CORE.pump();
    }

    static ShaderPreloadSchedulerCore.Job enqueue(
            ResourceLocation owner,
            ResourceLocation id,
            int priority,
            Runnable action
    ) {
        return CORE.enqueue(owner, id, priority, action);
    }

    static boolean cancel(ShaderPreloadSchedulerCore.Job job) {
        return CORE.cancel(job);
    }

    static int position(ShaderPreloadSchedulerCore.Job job) {
        return CORE.position(job);
    }

    static long waitNanos(ShaderPreloadSchedulerCore.Job job) {
        return CORE.waitNanos(job);
    }

    public record Settings(
            long frameBudgetNanos,
            int maxJobsPerFrame,
            int maxJobsPerOwnerPerFrame
    ) {
        public Settings {
            if (frameBudgetNanos <= 0) throw new IllegalArgumentException("frameBudgetNanos must be positive");
            if (maxJobsPerFrame <= 0) throw new IllegalArgumentException("maxJobsPerFrame must be positive");
            if (maxJobsPerOwnerPerFrame <= 0) {
                throw new IllegalArgumentException("maxJobsPerOwnerPerFrame must be positive");
            }
        }
    }

    public record Stats(
            int queuedJobs,
            long processedJobs,
            long cancelledJobs,
            long failedJobs,
            long activeFrames,
            long overBudgetFrames,
            long ownerDeferrals,
            int lastFrameJobs,
            int lastFrameOwners,
            long lastFrameNanos,
            long maxFrameNanos
    ) {}
}
