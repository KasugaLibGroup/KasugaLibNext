#version 330 core

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Normal;
layout(location = 2) in vec2 UV;
layout(location = 3) in vec4 Color;
layout(location = 4) in vec4 Material;

uniform mat4 ViewProjection;

out vec3 vNormal;
out vec2 vUv;
out vec4 vColor;

void main() {
    vNormal = normalize(Normal);
    vUv = UV;
    vColor = Color;
    gl_Position = ViewProjection * vec4(Position, 1.0);
}
