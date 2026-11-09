#version 150
uniform sampler2D ModelColor;
uniform sampler2D ModelDepth;
in vec2 uv;
out vec4 fragColor;
void main() {
    vec4 color = texture(ModelColor, uv);
    if (texture(ModelDepth, uv).r < 1.0) color.rgb *= vec3(0.35, 1.0, 0.6);
    fragColor = color;
}
