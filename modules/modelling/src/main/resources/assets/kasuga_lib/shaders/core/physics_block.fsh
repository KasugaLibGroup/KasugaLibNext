#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec3 BouncePX, BounceNX, BouncePY, BounceNY, BouncePZ, BounceNZ;
uniform float GIStrength;
uniform float AlphaCutoff;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 worldNormal;

out vec4 fragColor;

void main() {
    vec4 albedo = texture(Sampler0, texCoord0);
    vec4 color = albedo * vertexColor * ColorModulator;
    // Both entity block sheets used by BlockRenderDispatcher discard below
    // 0.1. Keep the fallback identical for cutout and translucent models.
    if (color.a < AlphaCutoff) discard;

    // Directional single-bounce irradiance from visible, lit world surfaces.
    // The zero-probe path is exactly the terrain shader's color expression.
    float directLuma = clamp(dot(vertexColor.rgb, vec3(0.333333)), 0.0, 1.0);
    float visibility = 1.0 - smoothstep(0.15, 0.82, directLuma);
    vec3 n = worldNormal / max(length(worldNormal), .0001);
    vec3 irradiance = n.x*n.x * (n.x < 0.0 ? BounceNX : BouncePX)
        + n.y*n.y * (n.y < 0.0 ? BounceNY : BouncePY)
        + n.z*n.z * (n.z < 0.0 ? BounceNZ : BouncePZ);
    vec3 bounce = pow(max(albedo.rgb, vec3(0)), vec3(2.2)) * irradiance * visibility * GIStrength;
    if (any(greaterThan(bounce, vec3(0))))
        color.rgb = pow(pow(max(color.rgb, vec3(0)), vec3(2.2)) + bounce, vec3(1.0 / 2.2));
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
