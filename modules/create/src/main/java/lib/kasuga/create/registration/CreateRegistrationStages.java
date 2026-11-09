package lib.kasuga.create.registration;

import lib.kasuga.registration.stages.RegistrationStage;

public final class CreateRegistrationStages {
    public static final RegistrationStage<CreateRegistrationEvent> PREPARE = new RegistrationStage<>();

    private CreateRegistrationStages() {
    }
}
