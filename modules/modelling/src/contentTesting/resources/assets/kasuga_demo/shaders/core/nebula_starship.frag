#version 330 core

in vec3 vLocal;
in vec3 vWorld;
in vec3 vNormal;
in vec2 vUv;
in vec4 vColor;
in vec4 vMaterial;

uniform float Time;
uniform float HullTextureStrength;
uniform float WarpCharge;
uniform float WarpActive;
uniform sampler2D AlbedoMap;
uniform sampler2D NormalMap;
uniform sampler2D MetallicRoughnessMap;
uniform sampler2D EmissiveMap;

layout(location = 0) out vec4 outSurface;
layout(location = 1) out vec4 outNormal;
layout(location = 2) out vec4 outPbr;

float panelLine(float value, float period, float width) {
    float cell = fract(value / period);
    return 1.0 - smoothstep(width * .35, width, min(cell, 1.0 - cell));
}

void main() {
    vec3 normal = normalize(vNormal);
    vec3 albedo = vColor.rgb * mix(vec3(1.0), clamp(texture(AlbedoMap, vUv).rgb * 4.20, vec3(.35), vec3(1.45)), HullTextureStrength);
    vec3 mr = texture(MetallicRoughnessMap, vUv).rgb;
    float metallic = clamp(mr.r * .95, 0.0, .72);
    float roughness = clamp(vMaterial.y * (.86 + mr.g * .40), .34, .97);
    float occlusion = mix(.72, 1.0, mr.b);
    float emission = vMaterial.x * texture(EmissiveMap, vUv).r;
    float kind = vMaterial.z;

    // Derivative tangent frame keeps the normal map usable without adding another vertex attribute.
    vec3 dp1 = dFdx(vWorld), dp2 = dFdy(vWorld);
    vec2 duv1 = dFdx(vUv), duv2 = dFdy(vUv);
    float det = duv1.x * duv2.y - duv1.y * duv2.x;
    vec3 tangent = abs(det) > 1e-6
        ? normalize((dp1 * duv2.y - dp2 * duv1.y) / det)
        : normalize(cross(normal, abs(normal.y) < .9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    tangent = normalize(tangent - normal * dot(normal, tangent));
    vec3 bitangent = normalize(cross(normal, tangent));
    vec3 tangentNormal = texture(NormalMap, vUv).xyz * 2.0 - 1.0;
    // The retained normal map is microstructure, not the new albedo panel layout.
    tangentNormal.xy *= .18;
    normal = normalize(mat3(tangent, bitangent, normal) * normalize(tangentNormal));

    // Broad physical paneling is baked into OBJ material bands; shader seams add only large, readable structure.
    if (kind < .5) {
        float saucerR = length(vLocal.xz - vec2(0.0, 0.228));
        float saucerAngle = atan(vLocal.x, vLocal.z - 0.228);
        if (vLocal.y > 0.04 && saucerR < 0.36) {
            float sectorSeam = panelLine(saucerAngle + 3.14159265, 0.39269908, 0.045);
            float ringA = 1.0 - smoothstep(0.0018, 0.0045, abs(saucerR - 0.230));
            float ringB = 1.0 - smoothstep(0.0020, 0.0050, abs(saucerR - 0.304));
            float tile = step(0.52, fract(saucerAngle / 0.39269908
                             + floor(saucerR * 11.0) * 0.37));
            albedo *= 0.94 + 0.08 * tile;
            albedo *= 1.0 - min(0.27, sectorSeam * 0.10 + ringA * 0.15 + ringB * 0.18);
        }
        if (abs(vLocal.x) < 0.105 && vLocal.y < 0.02 && vLocal.z < 0.16 && vLocal.z > -0.45) {
            float angle = atan(vLocal.x, vLocal.y + 0.073);
            albedo *= 1.0 - panelLine(angle + 3.14159265, 0.52359878, 0.07) * 0.11;
            albedo *= 1.0 - panelLine(vLocal.z + 0.06, 0.135, 0.07) * 0.07;
        }
    }

    if (kind > 1.5 && kind < 2.5) {
        float pulse = 0.82 + 0.18 * sin(Time * 7.5 + vLocal.z * 42.0);
        float grid = panelLine(vLocal.z + 0.54, 0.024, 0.12);
        albedo *= 0.72 + 0.28 * grid;
        emission *= pulse * (0.82 + WarpCharge * 0.15 + WarpActive * 0.20);
        roughness = 0.46;
        metallic *= .22;
    } else if (kind > 2.5 && kind < 3.5) {
        float radial = length(vLocal.xy - vec2(sign(vLocal.x) * 0.255, 0.115));
        float ring = 1.0 - smoothstep(0.002, 0.006, abs(radial - 0.027));
        albedo = mix(albedo, vec3(0.035, 0.012, 0.005), ring * 0.55);
        emission *= 0.78 + WarpCharge * 0.22;
        metallic *= .32;
    } else if (kind > 3.5 && kind < 4.5) {
        emission *= 0.82;
        metallic *= .18;
    } else if (kind > 4.5 && kind < 5.5) {
        albedo *= 0.72;
        emission *= 0.50;
        metallic *= .20;
    } else if (kind > 5.5 && kind < 6.5) {
        emission *= 0.75 + WarpCharge * 0.25;
        metallic = max(metallic, .48);
    } else if (kind > 6.5 && kind < 7.5) {
        emission *= 0.70 - WarpActive * 0.18;
        metallic *= .28;
    }

    float grain = fract(sin(dot(vLocal * 93.0, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
    albedo *= 0.94 + grain * 0.10;
    outSurface = vec4(albedo, 1.0);
    outNormal = vec4(normal, 1.0);
    outPbr = vec4(metallic, roughness, occlusion, max(emission, 0.0));
}
