package lib.kasuga.rendering.models.uml.loaders;

import lib.kasuga.rendering.models.uml.loaders.sources.AllSources;
import lib.kasuga.rendering.models.uml.loaders.sources.Source;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.MeshData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.VertexData;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteData;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteSetData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.AnchorData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonData;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public interface ModelLoader<InputType, OutputIdentifier, TextureIdentifier> {

    Map<OutputIdentifier, Model> load(OutputIdentifier identifier, InputType input);

    MaterialSetBuilder<TextureIdentifier> materialSetBuilder();

    String getName();

    boolean isValidInput(Object input);

    HashMap<SourceType, HashMap<String, SourceManager<?>>> getSidedSources();

    default void setSourceManager(SourceManager<?> sourceManager) {}

    @Nullable
    default <T> T getSource(String type, String sourceManagerName, String sourceName) {
        HashMap<SourceType, HashMap<String, SourceManager<?>>> map = getSidedSources();
        for (Map.Entry<SourceType, HashMap<String, SourceManager<?>>> entry : map.entrySet()) {
            if (!entry.getKey().getType().equals(type)) continue;
            SourceManager<?> manager = entry.getValue().get(sourceManagerName);
            if (manager == null || !manager.getSources().containsKey(sourceName)) return null;
            Source<?, ?> source = manager.getSources().get(sourceName);
            if (source == null) return null;
            @SuppressWarnings("unchecked")
            T result = (T) source;
            return result;
        }
        return null;
    }

    @Nullable
    default <T, R> R loadType(@NonNull String sourceType,
                              @Nullable String sourceManagerName,
                              @Nullable String sourceName,
                              @NonNull T inputIdentifier) {
        HashMap<SourceType, HashMap<String, SourceManager<?>>> map = getSidedSources();
        for (Map.Entry<SourceType, HashMap<String, SourceManager<?>>> entry : map.entrySet()) {
            if (!entry.getKey().getType().equals(sourceType)) continue;
            if (sourceManagerName == null) {
                HashMap<String, SourceManager<?>> managers = entry.getValue();
                for (SourceManager<?> manager : managers.values()) {
                    for (Source source : manager.getSources().values()) {
                        if (sourceName == null) {
                            if (!source.isValidInput(inputIdentifier)) continue;
                            @SuppressWarnings("unchecked")
                            R result = (R) source.getInput(inputIdentifier);
                            return result;
                        } else if (source.name().equals(sourceType)) {
                            if (!source.isValidInput(inputIdentifier)) continue;
                            @SuppressWarnings("unchecked")
                            R result = (R) source.getInput(inputIdentifier);
                            return result;
                        }
                    }
                }
            } else {
                SourceManager<?> manager = entry.getValue().get(sourceManagerName);
                if (manager == null) return null;
                if (sourceName == null) {
                    for (Source source : manager.getSources().values()) {
                        if (!source.isValidInput(inputIdentifier)) continue;
                        @SuppressWarnings("unchecked")
                        R result = (R) source.getInput(inputIdentifier);
                        return result;
                    }
                    return null;
                }
                Source source = manager.getSources().get(sourceName);
                if (source == null || !source.isValidInput(inputIdentifier)) return null;
                @SuppressWarnings("unchecked")
                R result = (R) source.getInput(inputIdentifier);
                return result;
            }
        }
        return null;
    }

    @Nullable
    default <T, R> R loadType(String sourceType, T inputIdentifier) {
        return loadType(sourceType, null, null , inputIdentifier);
    }

    default <T, R> R loadType(String sourceType, String sourceManagerName, T inputIdentifier) {
        return loadType(sourceType, sourceManagerName, null , inputIdentifier);
    }

    Texture loadTexture(Object textureIdentifier);
}
