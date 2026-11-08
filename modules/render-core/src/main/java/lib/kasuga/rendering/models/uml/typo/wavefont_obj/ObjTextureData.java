package lib.kasuga.rendering.models.uml.typo.wavefont_obj;

import lombok.Getter;
import org.joml.Vector3f;

@Getter
public class ObjTextureData {

    private final String name;

    private final Vector3f Ka, Kd, Ks, Tf;

    private final float Ns, Ni, d;

    private final String map_Kd, map_Ka, map_Ks;

    private final String map_Bump, map_Pr, map_Ke;
    private final Vector3f Ke;
    private final float Pr;

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
        this(name, ka, kd, ks, tf, ns, ni, d, illum, map_Kd, map_Ka, map_Ks, null, null, null);
    }

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
                          String map_Ks,
                          String map_Bump,
                          String map_Pr,
                          String map_Ke) {
        this(name, ka, kd, ks, tf, ns, ni, d, illum, map_Kd, map_Ka, map_Ks,
                map_Bump, map_Pr, map_Ke, new Vector3f(), .75f);
    }

    public ObjTextureData(String name, Vector3f ka, Vector3f kd, Vector3f ks, Vector3f tf,
                          float ns, float ni, float d, int illum, String map_Kd, String map_Ka,
                          String map_Ks, String map_Bump, String map_Pr, String map_Ke,
                          Vector3f ke, float pr) {
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
        this.map_Bump = map_Bump;
        this.map_Pr = map_Pr;
        this.map_Ke = map_Ke;
        this.Ke = ke;
        this.Pr = pr;
    }
}
