package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/** Expected command validation failure that is isolated to one command. */
public final class CommandRejectedException extends RuntimeException {

    public CommandRejectedException(String message) {
        super(message);
    }
}
