package lib.kasuga.rendering.models.mc.proxies;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Single volatile publication point shared by every render target in one model group. */
final class ModelGroupFrameState {

    private volatile Publication publication = new Publication(null, false);

    @Nullable
    ModelGroupFrame current() {
        return publication.frame();
    }

    boolean frameInput() {
        return publication.frameInput();
    }

    Publication snapshot() {
        return publication;
    }

    void publishLogical(ModelGroupFrame frame) {
        publish(frame, false);
    }

    void publishFrameInput(ModelGroupFrame frame) {
        publish(frame, true);
    }

    private void publish(ModelGroupFrame frame, boolean frameInput) {
        publication = new Publication(Objects.requireNonNull(frame, "frame"), frameInput);
    }

    record Publication(
            @Nullable ModelGroupFrame frame,
            boolean frameInput
    ) {
    }
}
