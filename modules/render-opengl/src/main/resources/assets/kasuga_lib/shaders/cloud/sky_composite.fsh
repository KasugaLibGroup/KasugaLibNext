#version 150
uniform sampler2D CloudColor, SceneDepth, SceneColor;
uniform mat4 InverseViewProjection;
uniform vec4 Viewport;
uniform vec2 CloudSize, DepthFootprint;
uniform int DisplayColorSpace;
out vec4 fragColor;

float sceneDepth(vec2 uv) {
    return min(texture(SceneDepth, uv).r,
        min(min(texture(SceneDepth, uv + DepthFootprint).r, texture(SceneDepth, uv - DepthFootprint).r),
            min(texture(SceneDepth, uv + DepthFootprint * vec2(-1, 1)).r,
                texture(SceneDepth, uv + DepthFootprint * vec2(1, -1)).r)));
}
float linearDepth(vec2 uv, float depth) {
    vec4 p = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1);
    vec4 n = InverseViewProjection * vec4(uv * 2.0 - 1.0, -1, 1);
    return abs(p.w) < 1e-8 ? 1e20 : length(p.xyz / p.w - n.xyz / n.w);
}
vec3 decode(vec3 color) {
    return mix(color / 12.92, pow((max(color, vec3(0)) + .055) / 1.055, vec3(2.4)), greaterThan(color, vec3(.04045)));
}
vec3 encode(vec3 color) {
    return mix(color * 12.92, 1.055 * pow(max(color, vec3(0)), vec3(1.0 / 2.4)) - .055, greaterThan(color, vec3(.0031308)));
}
void main() {
    vec2 uv = (gl_FragCoord.xy - Viewport.xy) / Viewport.zw;
    float depth = texture(SceneDepth, uv).r;
    float distance = linearDepth(uv, depth);
    vec2 cell = uv * CloudSize - .5, base = floor(cell), fraction = fract(cell);
    vec4 cloud = vec4(0);
    float total = 0.0;
    for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        vec2 sampleUV = (clamp(base + vec2(x, y), vec2(0), CloudSize - 1.0) + .5) / CloudSize;
        float sampleDepth = sceneDepth(sampleUV);
        float compatible = depth >= .999999 ? (sampleDepth >= .999999 ? 1.0 : 0.0)
            : (sampleDepth >= .999999 ? 0.0 : exp(-abs(linearDepth(sampleUV, sampleDepth) - distance) / max(.1, distance * .01)));
        float weight = (x == 0 ? 1.0 - fraction.x : fraction.x) * (y == 0 ? 1.0 - fraction.y : fraction.y) * compatible;
        cloud += texture(CloudColor, sampleUV) * weight; total += weight;
    }
    cloud = total > .00001 ? cloud / total : vec4(0);
    vec4 scene = texture(SceneColor, uv);
    // Preserve exact host pixels when the volume contributes nothing. This
    // also avoids decode/encode roundoff at silhouettes and empty sky.
    if (cloud.a <= 0.0) { fragColor = scene; return; }
    vec3 background = DisplayColorSpace != 0 ? decode(scene.rgb) : scene.rgb;
    vec3 result = cloud.rgb + background * (1.0 - clamp(cloud.a, 0.0, 1.0));
    fragColor = vec4(DisplayColorSpace != 0 ? encode(result) : result, scene.a);
}
