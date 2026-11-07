#version 150
uniform sampler2D SceneSampler;
in vec2 texCoord;
out vec4 fragColor;
void main() { fragColor = vec4(texture(SceneSampler, texCoord).rgb, 1.0); }
