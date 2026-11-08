package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/** Fixed priority order for playback commands. */
public enum CommandPriority {
    DERIVED(0),
    EVENT_HANDLER(100),
    CONTROLLER(200),
    HOST(300),
    SYSTEM(400);

    private final int weight;

    CommandPriority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
