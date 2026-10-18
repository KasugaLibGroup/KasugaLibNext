package lib.kasuga.rendering.effect.post.graph;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Declares one pass, its resource access and explicit ordering constraints. */
public record PostProcessGraphPassDescriptor(
        ResourceLocation id,
        int priority,
        Set<PostProcessGraphTarget> reads,
        Set<PostProcessGraphTarget> writes,
        Set<ResourceLocation> after,
        PostProcessGraphPass pass
) {
    public PostProcessGraphPassDescriptor {
        Objects.requireNonNull(id, "id");
        reads = immutableCopy(reads, "reads");
        writes = immutableCopy(writes, "writes");
        after = immutableCopy(after, "after");
        Objects.requireNonNull(pass, "pass");
        if (writes.isEmpty()) {
            throw new IllegalArgumentException("Graph pass must declare at least one output: " + id);
        }
        Set<PostProcessGraphTarget> feedback = new LinkedHashSet<>(reads);
        feedback.retainAll(writes);
        if (!feedback.isEmpty()) {
            throw new IllegalArgumentException("Graph pass cannot read and write the same target "
                    + feedback + ": " + id);
        }
        if (after.contains(id)) {
            throw new IllegalArgumentException("Graph pass cannot depend on itself: " + id);
        }
    }

    private static <T> Set<T> immutableCopy(Set<T> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashSet<T> copy = new LinkedHashSet<>();
        for (T value : values) copy.add(Objects.requireNonNull(value, name + " contains null"));
        return Collections.unmodifiableSet(copy);
    }

    public static Builder builder(ResourceLocation id, PostProcessGraphPass pass) {
        return new Builder(id, pass);
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final PostProcessGraphPass pass;
        private final Set<PostProcessGraphTarget> reads = new LinkedHashSet<>();
        private final Set<PostProcessGraphTarget> writes = new LinkedHashSet<>();
        private final Set<ResourceLocation> after = new LinkedHashSet<>();
        private int priority;

        private Builder(ResourceLocation id, PostProcessGraphPass pass) {
            this.id = Objects.requireNonNull(id, "id");
            this.pass = Objects.requireNonNull(pass, "pass");
        }

        public Builder priority(int value) {
            priority = value;
            return this;
        }

        public Builder reads(PostProcessGraphTarget... targets) {
            addAll(reads, targets, "targets");
            return this;
        }

        public Builder readsMain() {
            return reads(PostProcessGraphTarget.main());
        }

        public Builder writes(PostProcessGraphTarget... targets) {
            addAll(writes, targets, "targets");
            return this;
        }

        public Builder writesMain() {
            return writes(PostProcessGraphTarget.main());
        }

        public Builder after(ResourceLocation... passIds) {
            addAll(after, passIds, "passIds");
            return this;
        }

        private static <T> void addAll(Set<T> destination, T[] values, String name) {
            Objects.requireNonNull(values, name);
            for (T value : values) destination.add(Objects.requireNonNull(value, name + " contains null"));
        }

        public PostProcessGraphPassDescriptor build() {
            return new PostProcessGraphPassDescriptor(id, priority, reads, writes, after, pass);
        }
    }
}
