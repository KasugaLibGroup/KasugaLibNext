#version 150

uniform sampler2D AccumulationSampler;
uniform sampler2D RevealageSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 accumulation = texture(AccumulationSampler, texCoord);
    float revealage = clamp(texture(RevealageSampler, texCoord).r, 0.0, 1.0);
    // Empty accumulation is the identity element for the resolve.  Besides
    // being mathematically correct, this protects the main target when a
    // driver/resource pack exposes an uninitialized revealage sample as zero.
    float hasAccumulation = step(1e-5, accumulation.a);
    float oitAlpha = hasAccumulation * (1.0 - revealage);

    // The accumulation buffer stores premultiplied color and its weighted
    // alpha in .a. Resolve back to straight color before source-over blending
    // onto the already-rendered main target.
    float weight = max(accumulation.a, 1e-5);
    vec3 oitColor = accumulation.rgb / weight;
    if (accumulation.a <= 1e-5 || oitAlpha <= 1e-5) {
        oitColor = vec3(0.0);
    }
    fragColor = vec4(oitColor, oitAlpha);
}
