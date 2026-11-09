package lib.kasuga.create.registration;

import lib.kasuga.registration.Registry;
import lib.kasuga.registration.core.RegisterContext;

public final class CreateRegistrationState {
    public enum Status {
        NEW,
        PREPARING,
        COMMITTED,
        FAILED
    }

    private Status status = Status.NEW;
    private IllegalStateException failure;
    private int preparationCount;

    public synchronized void prepare(Registry registry, CreateClientBridge clientBridge) {
        if (status == Status.COMMITTED) {
            return;
        }
        if (status == Status.FAILED) {
            throw failure;
        }
        if (status == Status.PREPARING) {
            throw new IllegalStateException("Recursive Create registration preparation for " + registry.getModId());
        }

        status = Status.PREPARING;
        preparationCount++;
        try {
            CreateRegistrationEvent event = new CreateRegistrationEvent(registry, clientBridge);
            registry.dispatchRegister(new RegisterContext<>(CreateRegistrationStages.PREPARE, event));
            event.commit();
            status = Status.COMMITTED;
        } catch (RuntimeException exception) {
            failure = exception instanceof IllegalStateException illegalStateException
                    ? illegalStateException
                    : new IllegalStateException("Create registration preparation failed for " + registry.getModId(), exception);
            status = Status.FAILED;
            throw failure;
        }
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized int preparationCount() {
        return preparationCount;
    }
}
