#version 150
uniform sampler2D SceneDepth, WeatherLayout, WeatherDimensions, WeatherLayers;
uniform sampler3D NoiseVolume, ShapeVolume;
uniform mat4 InverseViewProjection;
uniform vec4 Viewport;
uniform vec2 DepthFootprint;
uniform vec3 GridOrigin, SunDirection, SunColor, AmbientColor, NoiseOffset, AtmosphereColor;
uniform float CellSize, MaxHeight, Coverage, MaxDistance;
uniform int GridSize, SampleBudget, LayerCount, StormLayer, LightSteps;
// Base relative to camera, thickness, noise scale, profile type.
uniform vec4 Layers[4];
uniform float LayerBias[4], LayerDensity[4];
uniform vec3 LayerNoiseOrigin[4];
out vec4 fragColor;

bool slab(vec3 o, vec3 d, vec3 low, vec3 high, out vec2 hit) {
    hit = vec2(-1e20, 1e20);
    for (int axis = 0; axis < 3; axis++) {
        if (abs(d[axis]) < 1e-8) { if (o[axis] < low[axis] || o[axis] > high[axis]) return false; }
        else {
            vec2 t = vec2(low[axis] - o[axis], high[axis] - o[axis]) / d[axis];
            hit.x = max(hit.x, min(t.x, t.y)); hit.y = min(hit.y, max(t.x, t.y));
        }
    }
    return hit.y > max(0.0, hit.x);
}
float remap(float v, float threshold) { return clamp((v - threshold) / max(.001, 1.0 - threshold), 0.0, 1.0); }
vec3 rotate(vec3 v, vec2 yaw) { return vec3(yaw.x*v.x + yaw.y*v.z, v.y, -yaw.y*v.x + yaw.x*v.z); }
float layerCoverage(int layer) { return clamp(Coverage + LayerBias[layer] * min(1.0, Coverage * 4.0), 0.0, 1.0); }
vec4 weatherAt(vec3 p, int layer) {
    vec2 cell = (p.xz - GridOrigin.xz) / CellSize;
    return texture(WeatherLayers, vec2(cell.x, cell.y + float(layer * GridSize)) / vec2(float(GridSize), float(GridSize * 4)));
}
float layerDensity(vec3 p, int layer, bool cheap) {
    if (LayerDensity[layer] <= 0.0) return 0.0;
    vec4 config = Layers[layer];
    float height = (p.y - config.x) / config.y;
    if (height < 0.0 || height > 1.16) return 0.0;
    vec4 weather = weatherAt(p, layer);
    height -= weather.b * .12;
    if (height <= 0.0 || height >= 1.0) return 0.0;
    float cover = clamp(layerCoverage(layer) + (weather.r - .5) * .55, 0.0, 1.0);
    if (cover <= 0.0) return 0.0;
    vec3 q = vec3(p.x, p.y - config.x, p.z) / config.z;
    if (config.w > 1.5) q = vec3((p.x * .25 + p.z * .5) / config.z, height * .3, p.z * 2.7 / config.z);
    q += LayerNoiseOrigin[layer];
    vec4 low = textureLod(NoiseVolume, q, cheap ? 1.0 : 0.0);
    float top = config.w < .5 ? mix(.38, 1.0, weather.g) : 1.0;
    top = min(1.0, top + (low.a - .5) * .18);
    float vertical = smoothstep(0.0, .065, height) * (1.0 - smoothstep(top * .65, top, height));
    float body = config.w > 1.5 ? mix(low.r, low.a, .7) : remap(low.r, -(1.0 - low.g));
    float rho = remap(body * vertical, 1.0 - cover);
    if (cheap || rho <= 0.0) return rho * LayerDensity[layer];
    vec3 detailQ = q * (config.w > 1.5 ? vec3(2, 4, 8) : vec3(4));
    float detail = textureLod(NoiseVolume, detailQ, 0.0).b;
    return max(0.0, rho - (1.0 - detail) * .12 * (1.0 - rho)) * LayerDensity[layer];
}
// Rare authored storms are embedded in the low field, rather than repeating the tower shape everywhere.
float stormDensity(vec3 p, bool cheap) {
    if (StormLayer < 0) return 0.0;
    float base = Layers[StormLayer].x;
    if (LayerDensity[StormLayer] <= 0.0 || p.y < base || p.y > base + MaxHeight) return 0.0;
    ivec2 cell = ivec2(floor((p.xz - GridOrigin.xz) / CellSize));
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(GridSize)))) return 0.0;
    vec4 placement = texelFetch(WeatherLayout, cell, 0);
    if (floor(placement.z) != 1.0) return 0.0;
    float weight = smoothstep(0.0, .08, layerCoverage(StormLayer) - placement.w) * LayerDensity[StormLayer] * .5;
    if (weight <= 0.0) return 0.0;
    vec4 dimensions = texelFetch(WeatherDimensions, cell, 0);
    vec3 size = vec3(dimensions.x * CellSize * .5, dimensions.y * MaxHeight, dimensions.z * CellSize * .5);
    vec3 center = vec3(GridOrigin.x + (float(cell.x) + .5 + placement.x) * CellSize,
        base + fract(placement.z) * MaxHeight, GridOrigin.z + (float(cell.y) + .5 + placement.y) * CellSize);
    vec3 local = rotate(p - center, vec2(cos(dimensions.w), sin(dimensions.w))) / size;
    if (any(greaterThan(abs(local.xz), vec2(1))) || local.y <= 0.0 || local.y >= 1.0) return 0.0;
    vec2 envelope = textureLod(ShapeVolume, local * vec3(.5, 1, .5) + vec3(.5, 0, .5), 0.0).rg;
    float body = smoothstep(0.0, .23, max(envelope.r, envelope.g));
    if (cheap || body <= 0.0) return body * weight;
    vec4 low = textureLod(NoiseVolume, local * size / 700.0 + NoiseOffset, 0.0);
    return max(0.0, body - (1.0 - low.r) * .20) * weight;
}
float densityAt(vec3 p, bool cheap, out int dominant) {
    float rho = 0.0, strongest = 0.0; dominant = 0;
    for (int layer = 0; layer < 4; layer++) {
        if (layer >= LayerCount) break;
        float value = layerDensity(p, layer, cheap);
        rho += value;
        if (value > strongest) { strongest = value; dominant = layer; }
    }
    float storm = stormDensity(p, cheap);
    if (storm > strongest && StormLayer >= 0) dominant = StormLayer;
    return min(4.0, rho + storm);
}
float phase(float mu, float g) { return (1.0 - g*g) / (12.56637 * pow(max(.02, 1.0 + g*g - 2.0*g*mu), 1.5)); }
float sceneDepth(vec2 uv) {
    return min(texture(SceneDepth, uv).r, min(min(texture(SceneDepth, uv + DepthFootprint).r,
        texture(SceneDepth, uv - DepthFootprint).r), min(texture(SceneDepth, uv + DepthFootprint * vec2(-1, 1)).r,
        texture(SceneDepth, uv + DepthFootprint * vec2(1, -1)).r)));
}
void main() {
    fragColor = vec4(0);
    if (Coverage <= 0.0) return;
    vec2 uv = (gl_FragCoord.xy - Viewport.xy) / Viewport.zw;
    vec4 nearPoint = InverseViewProjection * vec4(uv * 2.0 - 1.0, -1, 1);
    vec4 farPoint = InverseViewProjection * vec4(uv * 2.0 - 1.0, 1, 1);
    vec3 o = nearPoint.xyz / nearPoint.w, d = normalize(farPoint.xyz / farPoint.w - o);
    float end = MaxDistance, depth = sceneDepth(uv);
    if (depth < .999999) {
        vec4 opaque = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1);
        end = min(end, length(opaque.xyz / opaque.w - o));
    }
    vec2 intervals[4];
    for (int layer = 0; layer < 4; layer++) {
        intervals[layer] = vec2(1e20);
        if (layer >= LayerCount || LayerDensity[layer] <= 0.0) continue;
        float bottom = Layers[layer].x, top = bottom + Layers[layer].y * 1.16;
        if (layer == StormLayer) top = max(top, bottom + MaxHeight);
        vec2 hit;
        if (slab(o, d, vec3(GridOrigin.x, bottom, GridOrigin.z),
            vec3(GridOrigin.x + CellSize * float(GridSize), top, GridOrigin.z + CellSize * float(GridSize)), hit)) {
            intervals[layer] = vec2(max(0.0, hit.x), min(end, hit.y));
            if (intervals[layer].y <= intervals[layer].x) intervals[layer] = vec2(1e20);
        }
    }
    // Sort and merge the intervals. Downward/inside views use the same front-to-back integration.
    for (int a = 0; a < 4; a++) for (int b = a + 1; b < 4; b++) {
        if (intervals[b].x < intervals[a].x) { vec2 swap = intervals[a]; intervals[a] = intervals[b]; intervals[b] = swap; }
    }
    if (intervals[0].x >= 1e19) return;
    vec2 segments[4]; int segmentCount = 0;
    for (int layer = 0; layer < 4; layer++) {
        if (intervals[layer].x >= 1e19) break;
        if (segmentCount > 0 && intervals[layer].x <= segments[segmentCount - 1].y)
            segments[segmentCount - 1].y = max(segments[segmentCount - 1].y, intervals[layer].y);
        else { segments[segmentCount] = intervals[layer]; segmentCount++; }
    }
    // Allocate work to each disjoint layer interval. Thin upper layers must not disappear
    // merely because the low field consumes most of the physical ray length.
    float importance = 0.0;
    for (int segment = 0; segment < 4; segment++) {
        if (segment >= segmentCount) break;
        importance += sqrt(segments[segment].y - segments[segment].x);
    }
    int segment = 0;
    float extent = segments[0].y - segments[0].x;
    float stepSize = max(4.0, extent * importance / (float(SampleBudget) * sqrt(extent)));
    float jitter = fract(sin(dot(floor(gl_FragCoord.xy), vec2(12.9898, 78.233))) * 43758.5453);
    float t = segments[0].x + jitter * stepSize, transmittance = 1.0;
    float mu = dot(d, SunDirection), scattering = min(4.0, .35 + .65 * 12.56637 * mix(phase(mu, .5), phase(mu, -.25), .15));
    vec3 radiance = vec3(0);
    for (int sample = 0; sample < 512; sample++) {
        if (sample >= SampleBudget || transmittance < .008) break;
        if (t >= segments[segment].y) {
            segment++; if (segment >= segmentCount) break;
            extent = segments[segment].y - segments[segment].x;
            stepSize = max(4.0, extent * importance / (float(SampleBudget) * sqrt(extent)));
            t = segments[segment].x + jitter * stepSize; sample--; continue;
        }
        float intervalEnd = segments[segment].y;
        vec3 p = o + d * t;
        int dominant;
        float rho = densityAt(p, false, dominant) * (1.0 - smoothstep(MaxDistance * .75, MaxDistance, t));
        float length = min(stepSize, intervalEnd - t);
        if (rho > .001) {
            vec4 config = Layers[dominant];
            float optical = 0.0, lightStep = min(300.0, config.y * .2);
            for (int light = 1; light <= 3; light++) {
                if (config.w > 1.5) break;
                int ignored;
                if (light > LightSteps) break;
                float distance = (float(light) - .5) * lightStep * 3.0 / float(LightSteps);
                optical += densityAt(p + SunDirection * distance, true, ignored) * lightStep * 3.0 / float(LightSteps) * .009;
            }
            float illumination = max(exp(-optical), .55 * exp(-optical * .22));
            float height = clamp((p.y - config.x) / config.y, 0.0, 1.0);
            float core = clamp(rho / max(.01, LayerDensity[dominant]), 0.0, 1.0);
            float probability = mix(1.0, clamp(.05 + pow(core, mix(.5, 2.0, height)), 0.0, 1.0), .65);
            vec3 lighting = AmbientColor * mix(.55, 1.6, height) + SunColor * illumination * scattering * probability;
            if (config.w > 1.5) lighting = AmbientColor * 1.5 + SunColor * .65;
            // Preserve cloud relief in the host's normalized color target, with a smooth highlight shoulder.
            lighting = mix(lighting, .65 + .35 * (1.0 - exp(-max(vec3(0), lighting - .65) / .35)), greaterThan(lighting, vec3(.65)));
            lighting = mix(lighting, AtmosphereColor, 1.0 - exp(-t*t / 500000000.0));
            float alpha = 1.0 - exp(-rho * length * (config.w > 1.5 ? .004 : .009));
            radiance += transmittance * alpha * lighting; transmittance *= 1.0 - alpha;
        }
        t += stepSize;
    }
    fragColor = vec4(radiance, 1.0 - transmittance);
}
