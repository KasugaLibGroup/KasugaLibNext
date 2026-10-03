package lib.kasuga.rendering.models.uml.loaders.structural;

import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lib.kasuga.rendering.models.uml.loaders.SkeletonBuilder;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Getter
public abstract class Loader<InputType, OutputIdentifier, TextureIdentifier> implements ModelLoader<InputType, OutputIdentifier, TextureIdentifier> {

    private final HashMap<String, Processor<?>> processors;
    private final HashMap<SourceType, HashMap<String, SourceManager<?>>> sidedSource;

    private final List<Vertex> vertices;
    private final List<Mesh> meshes;
    private final SkeletonBuilder bones;
    private final Context context;

    private final String name;

    @Nullable
    @Setter
    private ModelData data;

    @Getter
    private final MaterialSetBuilder<TextureIdentifier> materialSetBuilder;

    public Loader(String name) {
        this.name = name;
        this.processors = new HashMap<>();
        vertices = new ArrayList<>();
        meshes = new ArrayList<>();
        bones = new SkeletonBuilder();
        this.context = new Context(this);
        this.data = null;
        this.sidedSource = new HashMap<>();
        this.materialSetBuilder = new MaterialSetBuilder<>(this);
    }

    public <T> void registerProcessor(String name, Processor<T> processor) {
        processors.forEach((k, v) -> {
            if (v instanceof Layer<?>) {
                Layer<?> layer = (Layer<?>) v;
                layer.getNamedProcessors().put(name, processor);
            }
        });
        if (processor instanceof Layer<?> layer) {
            layer.getNamedProcessors().putAll(processors);
        }
        this.processors.put(name, processor);
    }

    public HashMap<OutputIdentifier, Model> load(OutputIdentifier source, InputType input) {
        HashMap<OutputIdentifier, Model> resultMap = new HashMap<>();
        Processor<InputType> processor = (Processor<InputType>) processors.get("root");
        if (processor == null) {
            throw new IllegalStateException("No root processor registered");
        }
        processor.walk(input, context);
        build(resultMap);
        clear();
        return resultMap;
    }

    public void clear() {
        vertices.clear();
        meshes.clear();
        bones.clear();
        context.clear();
    }

    public abstract void build(HashMap<OutputIdentifier, Model> resultMap);

    @Override
    public HashMap<SourceType, HashMap<String, SourceManager<?>>> getSidedSources() {
        return sidedSource;
    }

    @Override
    public MaterialSetBuilder<TextureIdentifier> materialSetBuilder() {
        return materialSetBuilder;
    }
}
