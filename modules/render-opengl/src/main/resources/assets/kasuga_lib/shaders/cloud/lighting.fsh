#version 150
// CLOUD_DENSITY
uniform vec3 LocalSunDirection;
uniform vec3 LocalSunTangent;
uniform vec3 LocalSunBitangent;
uniform int LightSteps;
uniform int LightGridSize;
out float fragColor;

void main() {
    vec2 tile = floor(gl_FragCoord.xy / float(LightGridSize));
    float slice = tile.x + tile.y * 8.0;
    vec2 cell = mod(gl_FragCoord.xy, float(LightGridSize));
    vec3 p = vec3(cell, slice + .5) / float(LightGridSize);
    p = p * vec3(2, 1, 2) - vec3(1, 0, 1);
    float stepLength = max(0.0, intersectVolume(p, LocalSunDirection).y) / float(LightSteps);
    float opticalDepth = 0.0;
    for (int i = 0; i < 8; i++) {
        if (i >= LightSteps) break;
        float distance = (float(i) + .5) * stepLength;
        float angle = float(i) * 2.39996323;
        vec3 offset = (LocalSunTangent * cos(angle) + LocalSunBitangent * sin(angle)) * distance * .045;
        opticalDepth += densityAt(p + LocalSunDirection * distance + offset, true, 1.0) * stepLength * Optical.y;
    }
    fragColor = opticalDepth;
}
