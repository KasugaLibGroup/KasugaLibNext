package lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjModelLoader;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.processors.MtlKTFProcessor;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.processors.MtlMapProcessor;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.processors.MtlNDProcessor;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.processors.MtlObjectProcessor;
import lombok.Getter;

import java.util.HashMap;

@Getter
public class MtlLoader {

    private final HashMap<String, MtlKeyProcessor> processors;
    private final ObjModelLoader loader;

    public MtlLoader(ObjModelLoader loader, boolean useDefaultProcessors) {
        this.processors = new HashMap<>();
        this.loader = loader;
        if (useDefaultProcessors) useDefaultProcessors();
    }

    public void useDefaultProcessors() {
        registerProcessor("newmtl", new MtlObjectProcessor());
        registerProcessor("KTFP",new MtlKTFProcessor());
        registerProcessor("map_KTA", new MtlMapProcessor());
        registerProcessor("ND", new MtlNDProcessor());
    }

    public void registerProcessor(String name, MtlKeyProcessor processor) {
        this.processors.put(name, processor);
    }

    public boolean hasProcessor(String name) {
        return this.processors.containsKey(name);
    }

    public MtlKeyProcessor getProcessor(String name) {
        return this.processors.get(name);
    }

    public void readMtlFile(String content) {
        String[] lines = content.split("\n");
        String[] cache;
        SerialContext<MtlContext> context = new SerialContext<>(loader);
        for (String line : lines) {
            cache = line.split(" ");
            for (MtlKeyProcessor processor : processors.values()) {
                if (processor.isValidInput(cache)) {
                    processor.process(cache, context);
                    break;
                }
            }
        }
        while (!context.isEmpty()) {
            MtlContext context1 = context.pop();
            loader.consumeTexture(context1.build());
        }
    }
}
