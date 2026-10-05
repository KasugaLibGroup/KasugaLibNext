#version 150
uniform sampler2D CloudColor;
uniform sampler2D SceneDepth;
uniform mat4 InverseViewProjection;
uniform vec4 Viewport;
uniform vec2 CloudSize;
uniform vec2 DepthFootprint;
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
    return length(p.xyz / p.w - n.xyz / n.w);
}
void main() {
    vec2 uv = (gl_FragCoord.xy - Viewport.xy) / Viewport.zw;
    float targetDepth = texture(SceneDepth, uv).r;
    float targetDistance = linearDepth(uv, targetDepth);
    vec2 cell = uv * CloudSize - .5, base = floor(cell), fraction = fract(cell);
    vec4 color = vec4(0);
    float total = 0.0;
    for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        vec2 sampleUV = (clamp(base + vec2(x, y), vec2(0), CloudSize - 1.0) + .5) / CloudSize;
        float depth = sceneDepth(sampleUV);
        // Do not interpolate a cloud sample across an opaque silhouette.
        float compatible = targetDepth >= .999999 ? (depth >= .999999 ? 1.0 : 0.0)
            : (depth >= .999999 ? 0.0 : exp(-abs(linearDepth(sampleUV, depth) - targetDistance) / max(.1, targetDistance * .01)));
        float weight = (x == 0 ? 1.0 - fraction.x : fraction.x) * (y == 0 ? 1.0 - fraction.y : fraction.y) * compatible;
        color += texture(CloudColor, sampleUV) * weight; total += weight;
    }
    fragColor = total > .00001 ? color / total : vec4(0);
}
