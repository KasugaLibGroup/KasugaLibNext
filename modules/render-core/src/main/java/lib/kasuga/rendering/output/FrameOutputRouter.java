package lib.kasuga.rendering.output;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Render-thread-owned completed-view routing. Each view is captured once per
 * publication and its read-only frame is shared by all synchronous consumers.
 * Capturing does not render another camera or advance simulation. A failed
 * consumer is detached; capture failure detaches the entire affected view.
 */
public final class FrameOutputRouter<T> implements AutoCloseable {
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, Long> frames = new HashMap<>();
    private final Runnable checkThread;
    private final Supplier<? extends FrameOutputTarget<T>> defaultFactory;
    private boolean publishing;
    private boolean closed;

    public FrameOutputRouter() { this(() -> {}); }

    public FrameOutputRouter(Runnable checkThread) { this(checkThread, null); }

    /** The factory creates one lazily allocated target per view, not per consumer. */
    public FrameOutputRouter(Runnable checkThread, Supplier<? extends FrameOutputTarget<T>> factory) {
        this.checkThread = Objects.requireNonNull(checkThread);
        this.defaultFactory = factory;
    }

    public record Delivery(int delivered, boolean suppressScreen, List<Exception> failures) {}

    public boolean hasOutputs(String viewId) {
        checkThread.run();
        View view = views.get(viewId);
        return view != null && view.outputs.stream().anyMatch(output -> !output.closed);
    }

    public Registration register(String viewId, FrameOutputMode mode, Consumer<OutputFrame<T>> consumer) {
        if (defaultFactory == null) throw new IllegalStateException("No default output target factory");
        return register(viewId, mode, defaultFactory, consumer);
    }

    /**
     * The first registration selects the view's factory until its last consumer
     * closes. Later registrations share that target; their factories are unused.
     * Registrations added during publication start receiving on the next frame.
     */
    public Registration register(String viewId, FrameOutputMode mode,
                                 Supplier<? extends FrameOutputTarget<T>> factory,
                                 Consumer<OutputFrame<T>> consumer) {
        checkThread.run();
        if (closed) throw new IllegalStateException("Frame output router is closed");
        if (viewId == null || viewId.isBlank()) throw new IllegalArgumentException("A view ID is required");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(consumer, "consumer");
        View view = views.computeIfAbsent(viewId, id -> new View(id, factory));
        Registration output = new Registration(view, mode, consumer);
        view.outputs.add(output);
        return output;
    }

    public Delivery publish(String viewId, int width, int height, FrameBlitter blitter) {
        checkThread.run();
        if (closed) throw new IllegalStateException("Frame output router is closed");
        if (publishing) throw new IllegalStateException("Recursive frame publication");
        Objects.requireNonNull(blitter, "blitter");
        if (viewId == null || viewId.isBlank()) throw new IllegalArgumentException("A view ID is required");
        if (width <= 0 || height <= 0 || !hasOutputs(viewId)) return new Delivery(0, false, List.of());
        View view = views.get(viewId);
        long frameNumber = frames.merge(viewId, 1L, Long::sum);
        List<Registration> recipients = List.copyOf(view.outputs);
        List<Registration> delivered = new ArrayList<>();
        List<Exception> failures = new ArrayList<>();
        publishing = true;
        try {
            // Resizing may retire the preceding texture. Invalidate borrowed
            // handles before capture, then update every recipient before callbacks.
            for (Registration output : recipients) output.latest = null;
            OutputFrame<T> frame = null;
            try {
                if (view.target == null) view.target = Objects.requireNonNull(view.factory.get(), "output target");
                T resource = Objects.requireNonNull(view.target.capture(blitter, width, height), "output resource");
                frame = new OutputFrame<>(viewId, frameNumber, width, height, resource);
            } catch (Exception failure) {
                failures.add(failure);
                for (Registration output : view.outputs) output.detach();
            }
            if (frame != null) {
                for (Registration output : recipients) if (!output.closed) output.latest = frame;
                for (Registration output : recipients) {
                    if (output.closed) continue;
                    try {
                        output.consumer.accept(frame);
                        delivered.add(output);
                    } catch (Exception failure) {
                        output.detach();
                        failures.add(failure);
                    }
                }
            }
        } finally {
            publishing = false;
            retireEmptyViews(failures);
        }
        int count = 0;
        boolean suppressScreen = false;
        for (Registration output : delivered) if (!output.closed) {
            count++;
            suppressScreen |= output.mode == FrameOutputMode.OFFSCREEN_ONLY;
        }
        return new Delivery(count, suppressScreen, List.copyOf(failures));
    }

    private void retireEmptyViews(List<Exception> failures) {
        for (View view : List.copyOf(views.values())) {
            view.outputs.removeIf(output -> output.closed);
            if (!view.outputs.isEmpty()) continue;
            views.remove(view.id);
            FrameOutputTarget<T> retired = view.target;
            view.target = null;
            if (retired != null) try { retired.close(); }
            catch (Exception failure) { failures.add(failure); }
        }
    }

    @Override
    public void close() throws Exception {
        checkThread.run();
        if (closed) return;
        closed = true;
        for (View view : views.values()) for (Registration output : view.outputs) output.detach();
        frames.clear();
        if (!publishing) {
            List<Exception> failures = new ArrayList<>();
            retireEmptyViews(failures);
            throwCleanupFailures(failures);
        }
    }

    private static void throwCleanupFailures(List<Exception> failures) throws Exception {
        if (failures.isEmpty()) return;
        Exception first = failures.getFirst();
        for (int i = 1; i < failures.size(); i++) if (failures.get(i) != first) first.addSuppressed(failures.get(i));
        throw first;
    }

    private final class View {
        final String id;
        final Supplier<? extends FrameOutputTarget<T>> factory;
        final List<Registration> outputs = new ArrayList<>();
        FrameOutputTarget<T> target;

        View(String id, Supplier<? extends FrameOutputTarget<T>> factory) {
            this.id = id;
            this.factory = factory;
        }
    }

    public final class Registration implements AutoCloseable {
        private final View view;
        private final FrameOutputMode mode;
        private final Consumer<OutputFrame<T>> consumer;
        private OutputFrame<T> latest;
        private boolean closed;

        private Registration(View view, FrameOutputMode mode, Consumer<OutputFrame<T>> consumer) {
            this.view = view;
            this.mode = mode;
            this.consumer = consumer;
        }

        public String viewId() { return view.id; }
        public boolean isClosed() { return closed; }
        public Optional<OutputFrame<T>> latest() { return Optional.ofNullable(latest); }

        private void detach() { closed = true; latest = null; }

        @Override
        public void close() throws Exception {
            checkThread.run();
            if (closed) return;
            detach();
            // Shared storage remains alive through all callbacks and until the
            // last consumer closes. Replacements opened here reuse the same view.
            if (!publishing) {
                List<Exception> failures = new ArrayList<>();
                retireEmptyViews(failures);
                throwCleanupFailures(failures);
            }
        }
    }
}
