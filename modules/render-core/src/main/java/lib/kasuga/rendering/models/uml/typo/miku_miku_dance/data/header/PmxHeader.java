package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header;

import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lombok.Getter;

@Getter
public class PmxHeader implements ContextData<PmxHeader>, ModelData {

    public final String signature;
    public final float version;
    public final byte dataSize;
    public final PmxGlobalInfo info;
    public final String localModelName;
    public final String engModelName;
    public final String localDescription;
    public final String engDescription;

    public PmxHeader(
            String signature,
            float version,
            byte dataSize,
            PmxGlobalInfo info,
            String localModelName,
            String engModelName,
            String localDescription,
            String engDescription
    ) {
        this.signature = signature;
        this.version = version;
        this.dataSize = dataSize;
        this.info = info;
        this.localModelName = localModelName;
        this.engModelName = engModelName;
        this.localDescription = localDescription;
        this.engDescription = engDescription;
    }

    @Override
    public void build(SerialContext<PmxHeader> context) {

    }

    @Override
    public boolean isMeshTriangles() {
        return true;
    }
}
