#version 150
in vec3 Position;
out vec2 texCoord;
void main() {
    texCoord = Position.xy;
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
}
