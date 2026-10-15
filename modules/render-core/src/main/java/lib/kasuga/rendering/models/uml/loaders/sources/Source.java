package lib.kasuga.rendering.models.uml.loaders.sources;

import java.util.Optional;

public interface Source<T, R> {

    Optional<R> getInput(T input);

    String name();

    Class<T> getInputType();
    Class<R> getOutputType();

    boolean isValidInput(Object input);
}
