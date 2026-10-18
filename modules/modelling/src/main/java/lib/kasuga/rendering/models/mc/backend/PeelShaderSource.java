package lib.kasuga.rendering.models.mc.backend;

/** Wraps Sodium's own lighting/fog shader; normal chunk rendering is unchanged. */
public final class PeelShaderSource {
    private PeelShaderSource() {}

    public static String wrap(String source) {
        if (source.contains("ksg_PeelEnabled")) return source;
        String renamed = source.replaceFirst("void\\s+main\\s*\\(\\s*\\)", "void ksg_original_main()");
        if (renamed.equals(source)) throw new IllegalArgumentException("Cannot find Sodium fragment entry point");
        return renamed + "\n" + """
                uniform int ksg_PeelEnabled;
                uniform sampler2D ksg_PeelPrevious;
                uniform sampler2D ksg_PeelScene;
                uniform sampler2D ksg_PeelCoverage;
                uniform sampler2D ksg_PeelFootprint;
                void main() {
                    // On macOS the fixed-function depth write can round one
                    // ULP below gl_FragCoord.z, causing the same water surface
                    // to survive every peel. Store the value we compare.
                    // Assign on ALL paths, including normal terrain rendering.
                    gl_FragDepth = gl_FragCoord.z;
                    ivec2 pixel = ivec2(gl_FragCoord.xy);
                    if (ksg_PeelEnabled == 3 && texelFetch(ksg_PeelFootprint, pixel, 0).r > 0.0) discard;
                    if (ksg_PeelEnabled == 1) {
                        if (texelFetch(ksg_PeelFootprint, pixel, 0).r == 0.0) discard;
                        if (texelFetch(ksg_PeelCoverage, pixel, 0).a >= 1.0) discard;
                        if (gl_FragCoord.z <= texelFetch(ksg_PeelPrevious, pixel, 0).r
                            || gl_FragCoord.z > texelFetch(ksg_PeelScene, pixel, 0).r) discard;
                    }
                    ksg_original_main();
                    if (ksg_PeelEnabled == 1) {
                        if (fragColor.a <= (1.0 / 255.0)) discard;
                        fragColor.rgb *= fragColor.a;
                    }
                }
                """;
    }
}
