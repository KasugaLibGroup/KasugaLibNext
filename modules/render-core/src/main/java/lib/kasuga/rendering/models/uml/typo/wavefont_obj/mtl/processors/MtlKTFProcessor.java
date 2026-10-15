package lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.processors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.MtlContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.MtlKeyProcessor;
import org.joml.Vector3f;

public class MtlKTFProcessor extends MtlKeyProcessor {

    public MtlKTFProcessor() {
        super("K");
    }

    @Override
    public void process(String[] data, SerialContext<MtlContext> context) {
        switch (data[0]) {
            case "Ka":
                context.peek().Ka(
                        new Vector3f(
                                Float.parseFloat(data[1]),
                                Float.parseFloat(data[2]),
                                Float.parseFloat(data[3])
                        )
                );
                break;
            case "Kd":
                context.peek().Kd(
                        new Vector3f(
                                Float.parseFloat(data[1]),
                                Float.parseFloat(data[2]),
                                Float.parseFloat(data[3])
                        )
                );
                break;
            case "Ks":
                context.peek().Ks(
                        new Vector3f(
                                Float.parseFloat(data[1]),
                                Float.parseFloat(data[2]),
                                Float.parseFloat(data[3])
                        )
                );
                break;
            case "Tf":
                context.peek().Tf(
                        new Vector3f(
                                Float.parseFloat(data[1]),
                                Float.parseFloat(data[2]),
                                Float.parseFloat(data[3])
                        )
                );
                break;
        }
    }

    @Override
    public boolean isValidInput(String[] data) {
        return data.length > 1 && (
                data[0].equals("Ka") ||
                data[0].equals("Kd") ||
                data[0].equals("Ks") ||
                data[0].equals("Tf")
        );
    }
}
