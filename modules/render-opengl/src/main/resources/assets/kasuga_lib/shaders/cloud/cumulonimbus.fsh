#version 150
uniform sampler2D SceneDepth;
// CLOUD_DENSITY
uniform sampler2D LightVolume;
uniform int LightGridSize;
uniform vec2 DepthFootprint;
uniform mat4 InverseViewProjection;
uniform mat4 WorldToLocal;
uniform vec4 Viewport;
uniform vec3 SunDirection;
uniform vec3 SunColor;
uniform vec3 AmbientColor;
uniform int ViewSteps;
uniform bool AdaptiveMarching;
out vec4 fragColor;

float lightSlice(vec3 p, float slice) {
    vec2 tile = vec2(mod(slice, 8.0), floor(slice / 8.0));
    vec2 xy = clamp(p.xy, vec2(.5 / float(LightGridSize)), vec2(1.0 - .5 / float(LightGridSize)));
    return texture(LightVolume, (tile + xy) / vec2(8, (LightGridSize + 7) / 8)).r;
}
float lightDepthAt(vec3 p) {
    p = p * vec3(.5, 1, .5) + vec3(.5, 0, .5);
    float z = clamp(p.z * float(LightGridSize) - .5, 0.0, float(LightGridSize - 1));
    return mix(lightSlice(p, floor(z)), lightSlice(p, min(floor(z) + 1.0, float(LightGridSize - 1))), fract(z));
}
float sceneDepthAt(vec2 uv) {
    return min(texture(SceneDepth, uv).r,
        min(min(texture(SceneDepth, uv + DepthFootprint).r, texture(SceneDepth, uv - DepthFootprint).r),
            min(texture(SceneDepth, uv + DepthFootprint * vec2(-1, 1)).r, texture(SceneDepth, uv + DepthFootprint * vec2(1, -1)).r)));
}

float hg(float cosine, float g) {
    return (1.0 - g * g) / (12.5663706 * pow(max(.001, 1.0 + g * g - 2.0 * g * cosine), 1.5));
}

vec3 unproject(vec2 ndc, float depth) {
    vec4 p = InverseViewProjection * vec4(ndc, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec2 uv = (gl_FragCoord.xy - Viewport.xy) / Viewport.zw;
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 origin = unproject(ndc, 0.0);
    vec3 farPoint = unproject(ndc, 1.0);
    vec3 direction = normalize(farPoint - origin);
    vec3 localOrigin = (WorldToLocal * vec4(origin, 1.0)).xyz;
    vec3 localDirection = (WorldToLocal * vec4(direction, 0.0)).xyz;
    vec2 interval = intersectVolume(localOrigin, localDirection);
    float start = max(0.0, interval.x);
    // Clip at opaque scene surfaces; never write cloud depth.
    if (interval.y <= start) discard;
    float sceneDepth = sceneDepthAt(uv);
    // Sky clouds extend past the terrain far plane, while opaque world surfaces still clip the ray.
    float end = sceneDepth >= .999999 ? interval.y : min(interval.y, dot(unproject(ndc, sceneDepth) - origin, direction));
    if (end <= start || Optical.x == 0.0) discard;
    float stepLength = (end - start) / float(ViewSteps);
    // Fixed screen-space jitter avoids coherent bands without temporal ghosting/history.
    float jitter = fract(sin(dot(floor(gl_FragCoord.xy), vec2(12.9898, 78.233))) * 43758.5453);
    float transmittance = 1.0;
    vec3 radiance = vec3(0.0);
    float cosTheta = clamp(dot(direction, SunDirection), -1.0, 1.0);
    float phase = min(4.0, .35 + .65 * 12.5663706 * max(hg(cosTheta, .45), .5 * hg(cosTheta, .78)));
    float distance = start + jitter * stepLength;
    bool cheap = AdaptiveMarching;
    int emptySamples = 0;
    for (int i = 0; i < 256; i++) {
        if (distance >= end || transmittance < .005) break;
        vec3 p = localOrigin + localDirection * distance;
        if (cheap) {
            if (densityAt(p, true, 0.0) <= .0001) { distance += stepLength * 2.0; continue; }
            // Revisit the interval before a coarse hit so the front edge gets full samples.
            distance = max(start, distance - stepLength); cheap = false; continue;
        }
        float density = densityAt(p, false, 0.0);
        if (density < .001) {
            emptySamples++;
            if (AdaptiveMarching && emptySamples >= 6) cheap = true;
            distance += stepLength; continue;
        }
        emptySamples = 0;
        float opacity = 1.0 - exp(-density * Optical.y * min(stepLength, end - distance));
        float lightDepth = lightDepthAt(p);
        float secondary = .7 * exp(-lightDepth * .25) * mix(1.0, .25, smoothstep(.7, 1.0, cosTheta));
        float attenuation = max(exp(-lightDepth), secondary);
        float lowDensity = clamp(density / max(Optical.x, .001), 0.0, 1.0);
        float exponent = mix(.5, 2.0, smoothstep(.3, .85, p.y));
        float depthProbability = clamp(.05 + pow(lowDensity, exponent), 0.0, 1.0);
        float verticalProbability = pow(mix(.1, 1.0, smoothstep(.07, .14, p.y)), .8);
        float inScatter = mix(1.0, depthProbability * verticalProbability, .65);
        vec3 ambient = AmbientColor * mix(.40, 1.55, smoothstep(0.0, .95, p.y));
        vec3 lighting = ambient + SunColor * attenuation * phase * inScatter;
        radiance += transmittance * opacity * lighting;
        transmittance *= 1.0 - opacity;
        distance += stepLength;
    }
    fragColor = vec4(radiance, 1.0 - transmittance); // premultiplied source-over
}
