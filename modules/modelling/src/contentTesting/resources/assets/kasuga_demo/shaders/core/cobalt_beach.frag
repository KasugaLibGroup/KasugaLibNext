#version 330 core

uniform sampler2D CoastMap;

in vec3 vNormal;
in vec2 vUv;
in vec4 vColor;

out vec4 fragColor;

void main() {
    vec3 albedo = texture(CoastMap, vUv).rgb * vColor.rgb;
    vec3 normal = normalize(vNormal);
    float hemi = 0.82 + 0.18 * max(normal.y, 0.0);
    float sun = max(dot(normal, normalize(vec3(-0.42, 0.82, 0.31))), 0.0);
    vec3 color = albedo * (0.62 * hemi + 0.34 * sun + 0.10);
    fragColor = vec4(color, 1.0);
}
