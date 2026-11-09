package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/** Narrow command execution seam used by the transaction. */
@FunctionalInterface
public interface PlaybackCommandApplier {

    CommandApplyResult apply(PlaybackCommand command);

    /**
     * Opens a staging scope for one logical step.
     *
     * <p>Implementations that mutate long-lived state should override this method, stage every
     * command, and publish the staged state only in {@link Stage#commit()}.
     */
    default Stage begin() {
        return new Stage() {
            @Override
            public CommandApplyResult apply(PlaybackCommand command) {
                return PlaybackCommandApplier.this.apply(command);
            }

            @Override
            public void commit() {
            }

            @Override
            public void discard() {
            }
        };
    }

    /** One logical-step transaction for playback command application. */
    interface Stage {

        CommandApplyResult apply(PlaybackCommand command);

        void commit();

        void discard();
    }
}
