package lib.kasuga.rendering.models.mc.source.resource;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.uml.loaders.sources.Source;
import java.io.*;
import java.util.Optional;

/** Adapters for existing source managers. Format decoders and legacy Java entry points remain usable. */
public final class ResourceModelSources {
    private ResourceModelSources() {}
    @FunctionalInterface private interface Reader<T> { T read(ResourceModelInput input) throws IOException; }
    public static Source<ResourceModelInput, String> text() { return source("resource_text", String.class, ResourceModelInput::text); }
    public static Source<ResourceModelInput, byte[]> binary() { return source("resource_binary", byte[].class, ResourceModelInput::bytes); }
    public static Source<ResourceModelInput, JsonObject> json() { return source("resource_json", JsonObject.class, ResourceModelInput::json); }
    private static <T> Source<ResourceModelInput, T> source(String name, Class<T> output, Reader<T> reader) {
        return new Source<>() {
            @Override public Optional<T> getInput(ResourceModelInput input) {
                try { return Optional.of(reader.read(input)); } catch (IOException error) { throw new UncheckedIOException(error); }
            }
            @Override public String name() { return name; }
            @Override public Class<ResourceModelInput> getInputType() { return ResourceModelInput.class; }
            @Override public Class<T> getOutputType() { return output; }
            @Override public boolean isValidInput(Object input) { return input instanceof ResourceModelInput; }
        };
    }
}
