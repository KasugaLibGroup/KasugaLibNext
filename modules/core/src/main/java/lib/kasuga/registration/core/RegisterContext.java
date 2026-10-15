package lib.kasuga.registration.core;

import lib.kasuga.registration.stages.RegistrationStage;

import java.util.Objects;
import java.util.function.Consumer;

public class RegisterContext<L> {
    private RegistrationStage<L> stage;

    private L value;

    public RegisterContext(RegistrationStage<L> stage, L context) {
        this.value = context;
        this.stage = stage;
    }

    public <T> void onStage(RegistrationStage<T> stage, Consumer<T> consumer) {
        if(Objects.equals(stage, this.stage)) {
            //noinspection unchecked
            consumer.accept((T) value);
        }
    }

    public RegistrationStage<?> getStage() {
        return stage;
    }
}
