#version 150

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform samplerBuffer LightSampler;
uniform mat4 InverseViewProjection;
uniform int LightCount;
in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265359;
const int VOLUME_STEPS = 48;

vec3 positionAt(vec2 uv, float depth) {
    vec4 point = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return point.xyz / point.w;
}

vec3 neighbor(vec2 uv) {
    return positionAt(uv, texture(DepthSampler, uv).r);
}

// Choose the neighbor on the same surface to avoid normals spanning a depth discontinuity.
vec3 surfaceNormal(vec3 position) {
    vec2 pixel = 1.0 / vec2(textureSize(DepthSampler, 0));
    vec3 left = position - neighbor(texCoord - vec2(pixel.x, 0));
    vec3 right = neighbor(texCoord + vec2(pixel.x, 0)) - position;
    vec3 down = position - neighbor(texCoord - vec2(0, pixel.y));
    vec3 up = neighbor(texCoord + vec2(0, pixel.y)) - position;
    vec3 dx = dot(left, left) < dot(right, right) ? left : right;
    vec3 dy = dot(down, down) < dot(up, up) ? down : up;
    vec3 normal = cross(dx, dy);
    normal /= max(length(normal), 0.000001);
    return dot(normal, -position) < 0.0 ? -normal : normal;
}

float attenuation(vec3 offset, float range) {
    float squared = dot(offset, offset);
    float cutoff = max(0.0, 1.0 - squared / (range * range));
    return cutoff * cutoff / (1.0 + squared);
}

float diffuse(vec3 offset, vec3 normal, float range) {
    return max(dot(normal, offset / max(length(offset), 0.0001)), 0.0) * attenuation(offset, range);
}

vec3 areaIrradiance(vec3 position, vec3 normal, vec4 center, vec4 radiance, vec4 axisU, vec4 axisV, bool twoSided) {
    vec3 emitterNormal = cross(axisU.xyz, axisV.xyz);
    float areaScale = length(emitterNormal);
    emitterNormal /= max(areaScale, 0.000001);
    float result = 0.0;
    // Deterministic rectangular quadrature, with intensity interpreted as emitter radiance.
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            vec2 samplePosition = (vec2(x, y) + 0.5) * 0.5 - 1.0;
            vec3 offset = center.xyz + axisU.xyz * axisU.w * samplePosition.x
                    + axisV.xyz * axisV.w * samplePosition.y - position;
            float cosine = dot(emitterNormal, -offset / max(length(offset), 0.0001));
            float emission = twoSided ? abs(cosine) : max(cosine, 0.0);
            result += diffuse(offset, normal, center.w) * emission;
        }
    }
    float area = 4.0 * axisU.w * axisV.w * areaScale;
    return radiance.rgb * radiance.a * result * area / 16.0;
}

// Integrate only the part of the spherical medium between the near plane and visible scene depth.
vec4 volume(vec3 origin, vec3 ray, float sceneDistance, vec4 center, vec4 radiance, float density, float anisotropy) {
    vec3 offset = origin - center.xyz;
    float along = dot(offset, ray);
    float discriminant = along * along - dot(offset, offset) + center.w * center.w;
    if (discriminant <= 0.0 || density <= 0.0) return vec4(0.0);
    float root = sqrt(discriminant);
    float start = max(0.0, -along - root);
    float finish = min(sceneDistance, -along + root);
    if (finish <= start) return vec4(0.0);
    float stepLength = (finish - start) / float(VOLUME_STEPS);
    float transmittance = 1.0;
    vec3 scattered = vec3(0.0);
    float opticalDepth = 0.0;
    for (int step = 0; step < VOLUME_STEPS; step++) {
        vec3 samplePosition = origin + ray * (start + (float(step) + 0.5) * stepLength);
        vec3 toLight = center.xyz - samplePosition;
        float radial = max(0.0, 1.0 - dot(toLight, toLight) / (center.w * center.w));
        float extinction = density * radial * radial * stepLength;
        float segmentTransmittance = exp(-extinction);
        float cosine = dot(toLight / max(length(toLight), 0.0001), ray);
        float phase = (1.0 - anisotropy * anisotropy)
                / (4.0 * PI * pow(max(0.01, 1.0 + anisotropy * anisotropy - 2.0 * anisotropy * cosine), 1.5));
        scattered += transmittance * (1.0 - segmentTransmittance) * phase
                * radiance.rgb * radiance.a * attenuation(toLight, center.w);
        transmittance *= segmentTransmittance;
        opticalDepth += extinction;
    }
    return vec4(scattered, opticalDepth);
}

void main() {
    vec4 scene = texture(SceneSampler, texCoord);
    float depth = texture(DepthSampler, texCoord).r;
    bool surface = depth < 0.999999;
    vec3 position = positionAt(texCoord, depth);
    vec3 normal = surface ? surfaceNormal(position) : vec3(0.0);
    vec3 origin = positionAt(texCoord, 0.0);
    vec3 segment = position - origin;
    float sceneDistance = length(segment);
    vec3 ray = segment / max(sceneDistance, 0.0001);
    vec3 irradiance = vec3(0.0);
    vec3 scattering = vec3(0.0);
    float opticalDepth = 0.0;
    for (int index = 0; index < LightCount; index++) {
        int base = index * 5;
        vec4 center = texelFetch(LightSampler, base);
        vec4 radiance = texelFetch(LightSampler, base + 1);
        vec4 settings = texelFetch(LightSampler, base + 4);
        if (radiance.a <= 0.0) continue;
        int type = int(settings.x + 0.5);
        if (surface) {
            if (type == 1) {
                irradiance += areaIrradiance(position, normal, center, radiance,
                        texelFetch(LightSampler, base + 2), texelFetch(LightSampler, base + 3), settings.y > 0.5);
            } else {
                irradiance += radiance.rgb * radiance.a * diffuse(center.xyz - position, normal, center.w);
            }
        }
        if (type == 2) {
            vec4 fog = volume(origin, ray, sceneDistance, center, radiance, settings.z, settings.w);
            scattering += fog.rgb;
            opticalDepth += fog.a;
        }
    }
    // Scene color is a screen-space albedo estimate; material G-buffer data is not required.
    vec3 linearScene = pow(max(scene.rgb, vec3(0.0)), vec3(2.2));
    vec3 lit = linearScene + max(linearScene, vec3(0.02)) * irradiance;
    lit = lit * exp(-opticalDepth) + scattering;
    fragColor = vec4(pow(max(lit, vec3(0.0)), vec3(1.0 / 2.2)), scene.a);
}
