package lib.kasuga.rendering.models.uml.loaders.sources;

import lombok.Getter;

import java.util.HashMap;
import java.util.function.Function;

@Getter
public class AllSources {

    private final HashMap<String, SourceType> sourceTypes;
    private final HashMap<String, SourceManager<?>> sourceManagers;

    public AllSources() {
        this.sourceTypes = new HashMap<>();
        this.sourceManagers = new HashMap<>();
    }

    public void registerSourceType(SourceType type) {
        sourceTypes.put(type.getType(), type);
    }

    public boolean hasSourceType(String type) {
        return sourceTypes.containsKey(type);
    }

    public void registerSourceManager(String type, Function<SourceType, SourceManager<?>> managerConstructor) {
        SourceType sourceType = sourceTypes.get(type);
        if (sourceType == null) {
            throw new IllegalArgumentException("Source type '" + type + "' is not registered.");
        }
        SourceManager<?> manager = managerConstructor.apply(sourceType);
        sourceManagers.put(manager.getName(), manager);
    }

    public boolean hasSourceManager(String type) {
        return sourceManagers.containsKey(type);
    }

    public SourceManager<?> getSourceManager(String type) {
        return sourceManagers.get(type);
    }

    public <T> void registerSource(String managerName, Source<?, T> source) {
        SourceManager<T> manager = (SourceManager<T>) sourceManagers.get(managerName);
        if (manager == null) {
            throw new IllegalArgumentException("Source manager '" + managerName + "' is not registered.");
        }
        manager.registerSource(source);
    }

    public boolean hasSource(String managerName, String sourceName) {
        SourceManager<?> manager = sourceManagers.get(managerName);
        if (manager == null) {
            throw new IllegalArgumentException("Source manager '" + managerName + "' is not registered.");
        }
        return manager.hasSource(sourceName);
    }

    public Source getSource(String managerName, String sourceName) {
        SourceManager<?> manager = sourceManagers.get(managerName);
        if (manager == null) {
            throw new IllegalArgumentException("Source manager '" + managerName + "' is not registered.");
        }
        return manager.getSource(sourceName);
    }
}
