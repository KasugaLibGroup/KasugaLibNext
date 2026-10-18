#version 150

uniform sampler2D Layer;
uniform sampler2D NearestDepth;
uniform int WriteDepth;
out vec4 fragColor;

void main() {
    // Defined on every path, including color-only intermediate accumulation.
    gl_FragDepth = gl_FragCoord.z;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    fragColor = texelFetch(Layer, pixel, 0);
    if (WriteDepth != 0) {
        // Do not erase ordinary terrain/opaque depth outside the OIT coverage.
        if (fragColor.a <= 0.0) discard;
        gl_FragDepth = texelFetch(NearestDepth, pixel, 0).r;
    }
}
