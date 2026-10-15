package lib.kasuga.rendering.models.mc.typo.mtb;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.text_stream.LineSepProcessor;
import org.joml.Vector2i;
import org.joml.Vector3f;

import java.util.Objects;

public class MtbElementProcessor extends LineSepProcessor<MtbContext> {

    public MtbElementProcessor() {
        super("|", true);
    }

    @Override
    public void process(String[] data, SerialContext<MtbContext> context) {
        String elementType = data[5];
        if (!elementType.equals("Box") && !elementType.equals("ShapeBox")) {
            return;
        }
        MtbPolygon polygon = null;
        String name = data[3];
        Vector3f size = new Vector3f(
                Float.parseFloat(data[9]),
                Float.parseFloat(data[10]),
                Float.parseFloat(data[11])
        );
        Vector3f offset = new Vector3f(
                Float.parseFloat(data[15]),
                Float.parseFloat(data[16]),
                Float.parseFloat(data[17])
        );
        Vector3f position = new Vector3f(
                Float.parseFloat(data[6]),
                Float.parseFloat(data[7]),
                Float.parseFloat(data[8])
        );
        Vector2i uv = new Vector2i(
                Integer.parseInt(data[18]),
                Integer.parseInt(data[19])
        );
        Vector3f rotation = new Vector3f(
                Float.parseFloat(data[12]),
                Float.parseFloat(data[13]),
                Float.parseFloat(data[14])
        );
        rotation.z = -rotation.z; // Invert Z rotation to match Minecraft's coordinate system
        switch (elementType) {
            case "Box":
                polygon = new MtbPolygon(name, data[4], size, offset, position, rotation, uv);
                break;
            case "ShapeBox":
                Vector3f[] corners = new Vector3f[8];
                for (int i = 0; i < 8; i++) {
                    corners[i] = new Vector3f(
                            Float.parseFloat(data[20 + i]),
                            Float.parseFloat(data[28 + i]),
                            Float.parseFloat(data[36 + i])
                    );
                }
                polygon = new MtbShapeBox(name, data[4], size, offset, position, rotation, uv, corners);
                break;
        }
        Objects.requireNonNull(polygon);
        context.peek().addPolygon(polygon);
    }

    @Override
    public boolean isValidInput(String[] data) {
        return data[0].equals("Element");
    }


}
