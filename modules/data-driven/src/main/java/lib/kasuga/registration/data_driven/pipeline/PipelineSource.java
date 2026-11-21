package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.source.ContentSource;

import java.util.Objects;

record PipelineSource(ContentSource source, int order, int inputOrder) {
    PipelineSource {
        Objects.requireNonNull(source, "source");
        if (order < 0) throw new IllegalArgumentException("order must not be negative");
        if (inputOrder < 0) throw new IllegalArgumentException("inputOrder must not be negative");
    }
}
