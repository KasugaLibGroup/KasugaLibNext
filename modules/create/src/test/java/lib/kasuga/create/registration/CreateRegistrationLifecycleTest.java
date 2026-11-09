package lib.kasuga.create.registration;

import lib.kasuga.registration.Reg;
import lib.kasuga.registration.Registry;
import lib.kasuga.registration.core.RegisterContext;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreateRegistrationLifecycleTest {

    @Test
    void prepareCollectsThenCommitsInDependencyOrderOnlyOnce() {
        Registry registry = new Registry("lifecycle_test");
        List<String> trace = new ArrayList<>();

        new ActionReg("style", CreateRegistrationEvent.Phase.BOGEY_STYLE, trace).setParent(registry);
        new ActionReg("material", CreateRegistrationEvent.Phase.TRACK_MATERIAL, trace).setParent(registry);
        new ActionReg("block", CreateRegistrationEvent.Phase.BOGEY_BLOCK, trace).setParent(registry);
        new ActionReg("size", CreateRegistrationEvent.Phase.BOGEY_SIZE, trace).setParent(registry);

        CreateRegistrationState state = new CreateRegistrationState();
        state.prepare(registry, CreateClientBridge.noop());
        state.prepare(registry, CreateClientBridge.noop());

        assertEquals(List.of("material", "size", "block", "style"), trace);
        assertEquals(CreateRegistrationState.Status.COMMITTED, state.status());
        assertEquals(1, state.preparationCount());
    }

    @Test
    void duplicateIdsFailBeforeAnyActionIsCommitted() {
        Registry registry = new Registry("duplicate_test");
        List<String> trace = new ArrayList<>();

        new ActionReg("same", CreateRegistrationEvent.Phase.BOGEY_SIZE, trace).setParent(registry);
        new ActionReg("same", CreateRegistrationEvent.Phase.BOGEY_SIZE, trace).setParent(registry);

        CreateRegistrationState state = new CreateRegistrationState();
        IllegalStateException first = assertThrows(
                IllegalStateException.class,
                () -> state.prepare(registry, CreateClientBridge.noop())
        );
        IllegalStateException second = assertThrows(
                IllegalStateException.class,
                () -> state.prepare(registry, CreateClientBridge.noop())
        );

        assertEquals(List.of(), trace);
        assertSame(first, second);
        assertEquals(CreateRegistrationState.Status.FAILED, state.status());
    }

    @Test
    void validationOfEveryDescriptorRunsBeforeExternalCommitActions() {
        Registry registry = new Registry("validation_test");
        List<String> trace = new ArrayList<>();

        new ActionReg("would_commit", CreateRegistrationEvent.Phase.TRACK_MATERIAL, trace).setParent(registry);
        new InvalidReg().setParent(registry);

        CreateRegistrationState state = new CreateRegistrationState();
        assertThrows(IllegalStateException.class, () -> state.prepare(registry, CreateClientBridge.noop()));
        assertEquals(List.of(), trace);
    }

    private static final class ActionReg extends Reg<ActionReg, Void> {
        private final String id;
        private final CreateRegistrationEvent.Phase phase;
        private final List<String> trace;

        private ActionReg(String id, CreateRegistrationEvent.Phase phase, List<String> trace) {
            this.id = id;
            this.phase = phase;
            this.trace = trace;
        }

        @Override
        public void register(RegisterContext<?> context) {
            context.onStage(CreateRegistrationStages.PREPARE, event -> event.submit(
                    phase,
                    ResourceLocation.fromNamespaceAndPath("lifecycle_test", id),
                    () -> trace.add(id)
            ));
        }

        @Override
        public Void getEntry() {
            return null;
        }
    }

    private static final class InvalidReg extends Reg<InvalidReg, Void> {
        @Override
        public void register(RegisterContext<?> context) {
            context.onStage(CreateRegistrationStages.PREPARE, event -> event.submit(
                    CreateRegistrationEvent.Phase.BOGEY_STYLE,
                    ResourceLocation.fromNamespaceAndPath("validation_test", "invalid"),
                    () -> {
                        throw new IllegalStateException("missing dependency");
                    },
                    () -> {
                        throw new AssertionError("must not commit");
                    }
            ));
        }

        @Override
        public Void getEntry() {
            return null;
        }
    }
}
