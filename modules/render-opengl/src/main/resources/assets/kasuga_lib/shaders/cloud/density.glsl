uniform sampler3D NoiseVolume;
uniform sampler3D ShapeVolume;
uniform vec3 NoiseOffset;
uniform vec4 Optical; // density, extinction per world unit, erosion, anvil
uniform float Coverage;
uniform vec3 NoiseScale;
uniform vec3 WarpScale;

vec2 intersectVolume(vec3 origin, vec3 direction) {
    vec3 safeDirection = mix(vec3(1e-8), direction, greaterThan(abs(direction), vec3(1e-8)));
    vec3 a = (vec3(-1, 0, -1) - origin) / safeDirection;
    vec3 b = (vec3(1) - origin) / safeDirection;
    vec3 lo = min(a, b), hi = max(a, b);
    return vec2(max(max(lo.x, lo.y), lo.z), min(min(hi.x, hi.y), hi.z));
}

float remap01(float value, float minimum) {
    return clamp((value - minimum) / max(1.0 - minimum, .001), 0.0, 1.0);
}

float densityAt(vec3 p, bool cheap, float lod) {
    if (p.y <= 0.0 || p.y >= 1.0 || abs(p.x) >= 1.0 || abs(p.z) >= 1.0) return 0.0;
    vec3 uv = p * NoiseScale + NoiseOffset;
    vec3 turbulence = vec3(textureLod(NoiseVolume, uv * 2.0, lod).a,
                           textureLod(NoiseVolume, uv * 2.0 + .31, lod).a,
                           textureLod(NoiseVolume, uv * 2.0 + .63, lod).a);
    vec3 warped = p + (turbulence - .5) * WarpScale * Optical.z;
    // Separate baked channels preserve the controllable anvil, without a per-sample lobe loop.
    vec2 shape = textureLod(ShapeVolume, warped * vec3(.5, 1, .5) + vec3(.5, 0, .5), 0.0).rg;
    float interior = max(shape.r, shape.g * Optical.w);
    if (interior <= 0.0) return 0.0;
    vec4 lowNoise = textureLod(NoiseVolume, uv, lod);
    float baseNoise = remap01(lowNoise.r, -(1.0 - lowNoise.g));
    float envelope = smoothstep(0.0, .23, interior) * smoothstep(0.0, .016, p.y);
    float baseDensity = remap01(remap01(envelope, (1.0 - baseNoise) * Optical.z * .55), 1.0 - Coverage);
    if (cheap || baseDensity <= 0.0) return baseDensity * Optical.x;
    vec3 detailUV = p * NoiseScale * mix(vec3(3.0), vec3(2.0, 8.0, 4.0), smoothstep(.76, .92, p.y)) + NoiseOffset * 4.0;
    float detail = textureLod(NoiseVolume, detailUV, lod).b;
    float erosion = mix(detail, 1.0 - detail, clamp(p.y * 10.0, 0.0, 1.0)) * Optical.z * .40;
    return remap01(baseDensity, erosion) * Optical.x;
}
