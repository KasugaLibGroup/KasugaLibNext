#version 150

uniform sampler2D AccumulationSampler;
uniform sampler2D RevealageSampler;
uniform sampler2D DepthSampler;
uniform int DebugView;
uniform float NearPlane;
uniform float FarPlane;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 accumulation = texture(AccumulationSampler, texCoord);
    float revealage = clamp(texture(RevealageSampler, texCoord).r, 0.0, 1.0);
    float hasAccumulation = step(1e-5, accumulation.a);
    float oitAlpha = hasAccumulation * (1.0 - revealage);
    float weight = max(accumulation.a, 1e-5);
    vec3 oitColor = accumulation.rgb / weight;
    if (accumulation.a <= 1e-5 || oitAlpha <= 1e-5) oitColor = vec3(0.0);

    if (DebugView == 1) {
        fragColor = vec4(oitColor, 1.0);
    } else if (DebugView == 2) {
        float normalizedWeight = clamp(accumulation.a / 8.0, 0.0, 1.0);
        fragColor = vec4(normalizedWeight, normalizedWeight, normalizedWeight, 1.0);
    } else if (DebugView == 3) {
        fragColor = vec4(revealage, revealage, revealage, 1.0);
    } else if (DebugView == 4) {
        float depth = texture(DepthSampler, texCoord).r;
        float ndc = depth * 2.0 - 1.0;
        float linearDepth = (2.0 * NearPlane * FarPlane)
                / max(FarPlane + NearPlane - ndc * (FarPlane - NearPlane), 1e-5);
        float normalizedDepth = clamp((linearDepth - NearPlane) / (FarPlane - NearPlane), 0.0, 1.0);
        fragColor = vec4(normalizedDepth, normalizedDepth, normalizedDepth, 1.0);
    } else {
        fragColor = vec4(oitColor, oitAlpha);
    }
}
