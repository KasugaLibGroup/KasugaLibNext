#version 330 core

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Normal;
layout(location = 2) in vec2 UV;
layout(location = 3) in vec4 Color;
layout(location = 4) in vec4 Material;

uniform mat4 ViewProjection;
uniform vec3 CraftPosition;
uniform vec3 CraftRight;
uniform vec3 CraftUp;
uniform vec3 CraftForward;
uniform float ShipScale;
uniform float WarpStretch;

out vec3 vLocal;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vUv;
out vec4 vColor;
out vec4 vMaterial;

void main() {
    vec3 local = Position * ShipScale;
    local.z *= max(WarpStretch, 1.0);
    vec3 world = CraftPosition
        + CraftRight * local.x
        + CraftUp * local.y
        + CraftForward * local.z;
    vec3 basisNormal = CraftRight * Normal.x + CraftUp * Normal.y
        + CraftForward * (Normal.z / max(WarpStretch, 1.0));
    vLocal = Position;
    vWorld = world;
    vNormal = normalize(basisNormal);
    vUv = UV;
    vColor = Color;
    vMaterial = Material;
    gl_Position = ViewProjection * vec4(world, 1.0);
}
