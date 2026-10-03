package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream;

import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.Texture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class ByteStreamLoader <
        InputType, OutputIdentifier, TextureIdentifier, M extends ContextData<M>>
        implements ModelLoader<InputType, OutputIdentifier, TextureIdentifier> {

    private final MaterialSetBuilder<TextureIdentifier> materialSetBuilder;

    private final HashMap<SourceType, HashMap<String, SourceManager<?>>> sidedSourceManagers;

    private final List<StreamLoader> loaders;

    public ByteStreamLoader() {
        this.materialSetBuilder = new MaterialSetBuilder<>(this);
        this.sidedSourceManagers = new HashMap<>();
        this.loaders = new ArrayList<>();
    }

    public void registerSourceManager(SourceType type, String key, SourceManager<?> manager) {
        sidedSourceManagers.computeIfAbsent(type, t -> new HashMap<>()).put(key, manager);
    }

    public void registerLoader(StreamLoader loader) {
        loaders.add(loader);
    }

    @Override
    public Map<OutputIdentifier, Model> load(OutputIdentifier outputIdentifier, InputType input) {
        HashMap<OutputIdentifier, Model> map = new HashMap<>();
        ByteBuffer buffer = getAsByteBuffer(input);
        SerialContext<M> context = new SerialContext<>(this);
        beforeAllLoaders(buffer, context);
        for (StreamLoader loader : loaders) {
            beforeLoader(loader, buffer, context);
            Object result = loader.load(buffer, context);
            afterLoader(loader, buffer, result, context);
        }
        build(map, outputIdentifier, buffer, context);
        return map;
    }

    public abstract void beforeAllLoaders(ByteBuffer buffer, SerialContext<M> context);

    public abstract void beforeLoader(StreamLoader loader, ByteBuffer buffer, SerialContext<M> context);

    public abstract void afterLoader(StreamLoader loader, ByteBuffer buffer, Object result, SerialContext<M> context);

    public abstract void build(Map<OutputIdentifier, Model> map, OutputIdentifier identifier, ByteBuffer buffer, SerialContext<M> context);

    public abstract ByteBuffer getAsByteBuffer(InputType input);

    @Override
    public MaterialSetBuilder<TextureIdentifier> materialSetBuilder() {
        return materialSetBuilder;
    }

    @Override
    public HashMap<SourceType, HashMap<String, SourceManager<?>>> getSidedSources() {
        return sidedSourceManagers;
    }
}
