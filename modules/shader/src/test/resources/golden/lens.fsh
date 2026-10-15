#version 150

in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D SceneSampler;
uniform vec2 Center;
uniform float Strength;

void main() {
    vec2 delta = (texCoord - Center);
    float distanceToCenter = length(delta);
    vec2 warped = (texCoord + (normalize(delta) * (Strength / max(distanceToCenter, 0.001))));
    fragColor = texture(SceneSampler, warped);
}
