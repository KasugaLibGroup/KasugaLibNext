#version 150
uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D HeightSampler;
uniform mat4 InverseViewProjection;
uniform mat4 ViewProjection;
uniform vec3 Eye;
uniform vec4 WaterDomain; // centre X/Z, simulated extent, mean surface Y
uniform float Time;
uniform float CellSize;
uniform vec3 WaterAbsorption;
uniform vec3 WaterScattering;
uniform float ShorelineFoam;
in vec2 texCoord;
out vec4 fragColor;

vec3 relativePosition(vec2 uv, float depth) {
    vec4 point = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return point.xyz / point.w;
}
float displacement(vec2 world) {
    vec2 uv = (world - WaterDomain.xy) / WaterDomain.z + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return 0.0;
    vec2 encoded = texture(HeightSampler, uv).rg;
    return ((encoded.r * 65280.0 + encoded.g * 255.0) / 65535.0 - 0.5) * 0.5;
}
vec3 sky(vec3 direction) {
    return mix(vec3(0.58, 0.76, 0.94), vec3(0.12, 0.39, 0.82), pow(max(direction.y, 0.0), 0.45));
}
void main() {
    vec4 scene = texture(SceneSampler, texCoord);
    vec3 ray = normalize(relativePosition(texCoord, 0.999));
    // A wading camera stays above the free surface. Only rays aimed at the surface can hit it.
    if (abs(ray.y) < 0.00005) { fragColor = scene; return; }
    float t = (WaterDomain.w - Eye.y) / ray.y;
    if (t < 0.0) { fragColor = scene; return; }
    for (int i = 0; i < 4; i++) {
        vec2 xz = Eye.xz + ray.xz * t;
        t = (WaterDomain.w + displacement(xz) - Eye.y) / ray.y;
    }
    if (t < 0.0 || t > 16000.0) { fragColor = scene; return; }
    float depth = texture(DepthSampler, texCoord).r;
    float behind = depth < 0.999999 ? length(relativePosition(texCoord, depth)) : 20000.0;
    // World props, walls and the record player always occlude water in front of the plane.
    if (behind < t + 0.012) { fragColor = scene; return; }
    vec3 hit = Eye + ray * t;
    float e = max(CellSize, 0.12);
    vec2 slope = vec2(displacement(hit.xz + vec2(e, 0)) - displacement(hit.xz - vec2(e, 0)),
                      displacement(hit.xz + vec2(0, e)) - displacement(hit.xz - vec2(0, e))) / (2.0 * e);
    // Capillary detail changes optical roughness only; it does not replace the simulated surface.
    float detail = exp(-t * 0.006);
    slope += detail * vec2(0.006 * cos(hit.x * 4.8 + hit.z * 2.3 - Time * 1.5),
                           0.005 * cos(hit.z * 5.3 - hit.x * 1.7 + Time * 1.2));
    vec3 normal = normalize(vec3(-slope.x, 1.0, -slope.y));
    float fresnel = 0.0204 + 0.9796 * pow(1.0 - max(dot(-ray, normal), 0.0), 5.0);
    vec2 refractedUV = clamp(texCoord + slope * 0.035 * min(1.0, behind - t), vec2(0.001), vec2(0.999));
    float refractedDepth = texture(DepthSampler, refractedUV).r;
    if (refractedDepth < 0.999999 && (Eye + relativePosition(refractedUV, refractedDepth)).y > hit.y - 0.015)
        refractedUV = texCoord;
    vec3 bottom = texture(SceneSampler, refractedUV).rgb;
    float opticalDistance = min(18.0, max(0.0, behind - t));
    vec3 transmittance = exp(-WaterAbsorption * opticalDistance);
    vec3 transmitted = bottom * transmittance + WaterScattering * (1.0 - transmittance);
    vec3 reflectionDirection = reflect(ray, normal);
    vec3 reflected = sky(reflectionDirection);
    vec4 reflectedClip = ViewProjection * vec4(reflectionDirection * 1000.0, 1.0);
    vec2 reflectedUV = reflectedClip.xy / reflectedClip.w * 0.5 + 0.5;
    if (reflectedClip.w > 0.0 && all(greaterThan(reflectedUV, vec2(0))) && all(lessThan(reflectedUV, vec2(1)))
            && texture(DepthSampler, reflectedUV).r > 0.99999) {
        // Screen-space sky samples end at the viewport. Blend to the analytic sky before that
        // boundary rather than drawing a hard seam across otherwise continuous open water.
        vec2 edge = min(reflectedUV, vec2(1.0) - reflectedUV);
        float screenWeight = smoothstep(0.0, 0.12, min(edge.x, edge.y));
        reflected = mix(reflected, texture(SceneSampler, reflectedUV).rgb, screenWeight);
    }
    vec3 color = mix(transmitted, reflected, fresnel);
    vec3 sun = normalize(vec3(-0.3, 0.82, 0.38));
    float specular = pow(max(dot(reflect(-sun, normal), -ray), 0.0), 420.0);
    color += vec3(1.0, 0.96, 0.85) * specular * 0.5;
    float shallow = 1.0 - smoothstep(0.68, 1.12, opticalDistance);
    float foamNoise = 0.5 + 0.5 * sin(hit.x * 2.3 + hit.z * 1.7 - Time * 0.9)
            * sin(hit.z * 2.8 - hit.x * 1.2 + Time * 0.55);
    float foam = shallow * smoothstep(0.62, 0.88, foamNoise) * ShorelineFoam;
    color = mix(color, vec3(0.76, 0.84, 0.82), foam * 0.48);
    color = mix(color, vec3(0.48, 0.69, 0.90), 1.0 - exp(-t * 0.00032));
    fragColor = vec4(color, scene.a);
}
