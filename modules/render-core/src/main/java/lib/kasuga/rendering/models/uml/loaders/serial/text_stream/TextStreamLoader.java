package lib.kasuga.rendering.models.uml.loaders.serial.text_stream;

import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.structure.Model;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public abstract class TextStreamLoader<
        InputType, OutputIdentifier, TextureIdentifier, M extends ContextData<M>>
        implements ModelLoader<InputType, OutputIdentifier, TextureIdentifier> {

    @Getter
    private final String separator;

    @Getter
    private final HashMap<String, LineProcessor> processors;

    @Getter
    private final boolean trim;

    @Getter
    private final String name;

    @Getter
    private SerialContext<M> context;

    @Getter
    private final HashMap<SourceType, HashMap<String, SourceManager<?>>> sidedSourceManagers;

    @Getter
    private final MaterialSetBuilder<TextureIdentifier> materialSetBuilder;

    public TextStreamLoader(String name, String separator, boolean trim) {
        this.separator = separator;
        this.processors = new HashMap<>();
        this.trim = trim;
        this.name = name;
        sidedSourceManagers =  new HashMap<>();
        this.materialSetBuilder = new MaterialSetBuilder<>(this);
    }

    public void registerProcessor(String key, LineProcessor processor) {
        processors.put(key, processor);
    }

    public abstract String getAsString(InputType input);

    public String[] split(String input) {
        String[] result = input.split(separator);
        if (!trim) return result;
        for (int i = 0; i < result.length; i++) {
            result[i] = innerTrim(result[i]);
        }
        return result;
    }

    @Override
    public Map<OutputIdentifier, Model> load(OutputIdentifier identifier, InputType input) {
        HashMap<OutputIdentifier, Model> result = new HashMap<>();
        String[] str = getString(input);
        LineProcessor lastProcessor = null;
        context = new SerialContext<>(this);
        beforeProcessors(context);
        for (String line : str) {
            if (lastProcessor != null && lastProcessor.isValidInput(line, context)) {
                lastProcessor.input(line, context);
                continue;
            }
            for (LineProcessor processor : processors.values()) {
                if (processor == lastProcessor) continue;
                if (processor.isValidInput(line, context)) {
                    processor.input(line, context);
                    lastProcessor = processor;
                    break;
                }
            }
        }
        build(result);
        return result;
    }

    public void beforeProcessors(SerialContext<M> context) {

    }

    public abstract void build(HashMap<OutputIdentifier, Model> result);

    public String[] getString(InputType input) {
        String str = getAsString(input);
        if (str == null) return new String[0];
        return split(str);
    }

    public static String innerTrim(String str) {
        String cache = str.trim();
        while (cache.contains("  ")) {
            cache = cache.replace("  ", " ");
        }
        return cache;
    }

    @Override
    public HashMap<SourceType, HashMap<String, SourceManager<?>>> getSidedSources() {
        return getSidedSourceManagers();
    }

    @Override
    public MaterialSetBuilder<TextureIdentifier> materialSetBuilder() {
        return materialSetBuilder;
    }
}
