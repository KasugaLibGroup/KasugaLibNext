package lib.kasuga.rendering.models.uml.loaders.sources;

import lombok.Getter;

import java.util.HashMap;
import java.util.Optional;

@Getter
public abstract class SourceManager<R> {

    private final SourceType type;

    private final String name;

    private final HashMap<String, Source<?, R>> sources;

    public SourceManager(SourceType type, String name) {
        this.type = type;
        this.sources = new HashMap<>();
        this.name = name;
    }

    public void registerSource(Source<?, R> source) {
        sources.put(source.name(), source);
    }

    public boolean hasSource(String name) {
        return sources.containsKey(name);
    }

    public Source<?, R> getSource(String name) {
        return sources.get(name);
    }

    @SuppressWarnings("unchecked")
    public Optional<R> load(Object sourceIdentifier) throws Exception {
        for (Source<?, R> source : sources.values()) {
            if (source.isValidInput(sourceIdentifier)) {
                return ((Source<Object, R>)source).getInput(sourceIdentifier);
            }
        }
        throw new IllegalArgumentException("No source found for identifier: " + sourceIdentifier);
    }
}
