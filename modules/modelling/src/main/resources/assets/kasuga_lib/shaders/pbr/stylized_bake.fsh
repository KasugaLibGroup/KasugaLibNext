#version 150

uniform sampler2D sourceTexture;
uniform float smoothness;
uniform float f0Code;
uniform float sssCode;
uniform float normalStrength;
uniform float emission;

in vec2 texCoord;

out vec4 normalOut;
out vec4 specularOut;

float luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec2 texel = 1.0 / vec2(textureSize(sourceTexture, 0));
    float left = luminance(texture(sourceTexture, texCoord - vec2(texel.x, 0.0)).rgb);
    float right = luminance(texture(sourceTexture, texCoord + vec2(texel.x, 0.0)).rgb);
    float up = luminance(texture(sourceTexture, texCoord - vec2(0.0, texel.y)).rgb);
    float down = luminance(texture(sourceTexture, texCoord + vec2(0.0, texel.y)).rgb);
    vec2 normalXY = clamp(
        vec2(left - right, up - down) * normalStrength,
        -1.0,
        1.0
    );
    normalOut = vec4(normalXY * 0.5 + 0.5, 1.0, 1.0);
    specularOut = vec4(smoothness, f0Code, sssCode, emission);
}
