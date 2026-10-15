package lib.kasuga.registration.core;

import io.micronaut.context.annotation.Context;
import lib.kasuga.registration.Registry;
import lib.kasuga.registration.stages.RegistrationStage;
import net.neoforged.bus.api.IEventBus;

import java.util.*;

@Context()
public class RegisterContextRegistry {

    public static enum Side {
        COMMON, CLIENT
    }

    public static interface RegistryDispatchFunction {
        public void dispatch(Registry registry, IEventBus eventBus);
    }

    @FunctionalInterface
    public interface RegistryStageDispatchFunction {
        void dispatch(Registry registry, RegisterContext<?> context);
    }

    protected record RegistryEntry(Side side, Registry registry, IEventBus eventBus) {
        void dispatch(RegistryDispatchFunction dispatchFunction) {
            dispatchFunction.dispatch(registry, eventBus);
        }
    }

    protected Map<Side, List<RegistryDispatchFunction>> dispatchFunctions = new HashMap<>();

    protected Map<Side, List<RegistryEntry>> registries = new HashMap<>();

    protected Map<Side, Map<RegistrationStage<?>, List<RegistryStageDispatchFunction>>> beforeDispatchFunctions = new HashMap<>();

    public void configure(Side side, Registry registry, IEventBus eventBus) {
        RegistryEntry entry = new RegistryEntry(side, registry, eventBus);
        registries.computeIfAbsent(side, (i)->new ArrayList<>()).add(entry);
        dispatchFunctions.getOrDefault(side, List.of()).forEach(entry::dispatch);
    }

    public void register(Side side, RegistryDispatchFunction dispatchFunction) {
        dispatchFunctions.computeIfAbsent(side, (i)->new ArrayList<>()).add(dispatchFunction);
        for (RegistryEntry registryEntry : registries.getOrDefault(side, List.of())) {
            registryEntry.dispatch(dispatchFunction);
        }
    }

    public void registerBefore(
            Side side,
            RegistrationStage<?> stage,
            RegistryStageDispatchFunction dispatchFunction
    ) {
        beforeDispatchFunctions
                .computeIfAbsent(side, ignored -> new IdentityHashMap<>())
                .computeIfAbsent(stage, ignored -> new ArrayList<>())
                .add(dispatchFunction);
    }

    public void dispatch(Side side, Registry registry, RegisterContext<?> context) {
        beforeDispatchFunctions
                .getOrDefault(side, Map.of())
                .getOrDefault(context.getStage(), List.of())
                .forEach(function -> function.dispatch(registry, context));
        registry.dispatchRegister(context);
    }
}
