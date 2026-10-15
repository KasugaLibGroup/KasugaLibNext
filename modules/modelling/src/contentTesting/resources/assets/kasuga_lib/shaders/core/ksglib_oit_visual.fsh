#version 150

uniform int AlphaMode;
uniform float AlphaCutoff;
uniform int OitMode;

in vec4 vertexColor;
out vec4 fragColor;

void main() {
    vec4 color = vertexColor;

    // The mask path is intentionally a hard discard and writes depth through
    // its render state. The blend path has its own near-zero cutoff so it does
    // not turn transparent OIT contributions into black RGB.
    if (AlphaMode == 1) {
        if (color.a < AlphaCutoff) discard;
        color.a = 1.0;
    } else if (AlphaMode == 2 && color.a < (1.0 / 255.0)) {
        discard;
    }

    if (OitMode == 1) {
        // The accumulation target uses ONE, ONE blending. Both channels are
        // therefore already the exact weighted sums needed by the resolve.
        float weight = clamp(pow(max(0.0, 1.0 - gl_FragCoord.z), 3.0) * 8.0 + 0.01,
                0.01, 8.0);
        fragColor = vec4(color.rgb * color.a * weight, color.a * weight);
    } else if (OitMode == 2) {
        // Revealage target uses ZERO, ONE_MINUS_SRC_ALPHA blending.
        fragColor = vec4(0.0, 0.0, 0.0, clamp(color.a, 0.0, 1.0));
    } else {
        fragColor = color;
    }
}
