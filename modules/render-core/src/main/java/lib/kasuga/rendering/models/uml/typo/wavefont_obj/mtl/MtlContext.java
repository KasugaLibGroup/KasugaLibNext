package lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl;

import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjTextureData;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

public class MtlContext implements ContextData<MtlContext> {

    private final @NonNull String name;
    private @NonNull Vector3f Ka, Kd, Ks, Tf;
    private float Ns, Ni, d;
    private int illum;
    private String map_Ka, map_Kd, map_Ks;

    public MtlContext(@NonNull String name) {
        this.name = name;
        Ka = new Vector3f(0.2f, 0.2f, 0.2f);
        Kd = new Vector3f(0.8f, 0.8f, 0.8f);
        Ks = new Vector3f(1.0f, 1.0f, 1.0f);
        Tf = new Vector3f(1.0f, 1.0f, 1.0f);
        d = 1.0f;
        Ns = 0.0f;
        Ni = 1.0f;
        illum = 1;
        map_Ka = null;
        map_Kd = null;
        map_Ks = null;
    }

    public void Ka(@NonNull Vector3f Ka) {
        this.Ka = Ka;
    }

    public void Kd(@NonNull Vector3f Kd) {
        this.Kd = Kd;
    }

    public void Ks(@NonNull Vector3f Ks) {
        this.Ks = Ks;
    }

    public void Tf(@NonNull Vector3f Tf) {
        this.Tf = Tf;
    }

    public void Ns(float Ns) {
        this.Ns = Ns;
    }

    public void Ni(float Ni) {
        this.Ni = Ni;
    }

    public void d(float d) {
        this.d = d;
    }

    public void illum(int illum) {
        this.illum = illum;
    }

    public void map_Ka(@NonNull String map_Ka) {
        this.map_Ka = map_Ka;
    }

    public void map_Kd(@Nullable String map_Kd) {
        this.map_Kd = map_Kd;
    }

    public void map_Ks(@Nullable String map_Ks) {
        this.map_Ks = map_Ks;
    }

    @Nullable
    public ObjTextureData build() {
        return new ObjTextureData(
                name, Ka, Kd, Ks, Tf, Ns, Ni, d, illum, map_Kd, map_Ka, map_Ks
        );
    }

    @Override
    public void build(SerialContext<MtlContext> context) {}
}
