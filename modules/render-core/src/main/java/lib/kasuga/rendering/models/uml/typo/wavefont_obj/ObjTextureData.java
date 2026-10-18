package lib.kasuga.rendering.models.uml.typo.wavefont_obj;

import lombok.Getter;
import org.joml.Vector3f;

@Getter
public class ObjTextureData {

    private final String name;

    private final Vector3f Ka, Kd, Ks, Tf;

    private final float Ns, Ni, d;

    private final String map_Kd, map_Ka, map_Ks;

    private final int illum;


    public ObjTextureData(String name,
                          Vector3f ka,
                          Vector3f kd,
                          Vector3f ks,
                          Vector3f tf,
                          float ns,
                          float ni,
                          float d,
                          int illum,
                          String map_Kd,
                          String map_Ka,
                          String map_Ks) {
        this.name = name;
        Ka = ka;
        Kd = kd;
        Ks = ks;
        Tf = tf;
        Ns = ns;
        Ni = ni;
        this.d = d;
        this.illum = illum;
        this.map_Kd = map_Kd;
        this.map_Ka = map_Ka;
        this.map_Ks = map_Ks;
    }
}
