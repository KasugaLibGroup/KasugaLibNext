package lib.kasuga.create.registration;

import lib.kasuga.registration.Registry;
import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CreateRegistrationEvent {
    public enum Phase {
        TRACK_MATERIAL,
        BOGEY_SIZE,
        BOGEY_BLOCK,
        BOGEY_STYLE
    }

    private static final Comparator<ActionKey> ACTION_ORDER = Comparator
            .comparing(ActionKey::phase)
            .thenComparing(key -> key.id().toString());

    private final Registry registry;
    private final CreateClientBridge clientBridge;
    private final Map<ActionKey, PendingAction> actions = new LinkedHashMap<>();
    private boolean committed;

    CreateRegistrationEvent(Registry registry, CreateClientBridge clientBridge) {
        this.registry = registry;
        this.clientBridge = clientBridge;
    }

    public Registry registry() {
        return registry;
    }

    public CreateClientBridge clientBridge() {
        return clientBridge;
    }

    public void submit(Phase phase, ResourceLocation id, Runnable action) {
        submit(phase, id, () -> {}, action);
    }

    public void submit(Phase phase, ResourceLocation id, Runnable validator, Runnable action) {
        submit(phase, id, null, validator, action);
    }

    public void submit(
            Phase phase,
            ResourceLocation id,
            Object source,
            Runnable validator,
            Runnable action
    ) {
        if (committed) {
            throw new IllegalStateException("Create registration event has already committed");
        }
        ActionKey key = new ActionKey(phase, id);
        if (actions.putIfAbsent(key, new PendingAction(source, validator, action)) != null) {
            throw new IllegalStateException("Duplicate Create registration " + phase + ": " + id);
        }
    }

    public boolean contains(Phase phase, ResourceLocation id) {
        return actions.containsKey(new ActionKey(phase, id));
    }

    public boolean contains(Phase phase, ResourceLocation id, Object source) {
        PendingAction action = actions.get(new ActionKey(phase, id));
        return action != null && action.source() == source;
    }

    void commit() {
        if (committed) {
            throw new IllegalStateException("Create registration event has already committed");
        }
        committed = true;
        var orderedActions = actions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(ACTION_ORDER))
                .map(Map.Entry::getValue)
                .toList();
        orderedActions.forEach(action -> action.validator().run());
        orderedActions.forEach(action -> action.committer().run());
    }

    private record ActionKey(Phase phase, ResourceLocation id) {
    }

    private record PendingAction(Object source, Runnable validator, Runnable committer) {
    }
}
