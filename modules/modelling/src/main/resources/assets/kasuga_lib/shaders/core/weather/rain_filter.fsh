#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform sampler2D RainDepthSampler;
uniform sampler2D VolumeSampler;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

uniform int RainDepthEnabled;
// x = 1 / (2 * R), y = bottomRelY, z = depthSpan, w = depthBias
uniform vec4 RainDepthParams;
uniform int VolumeCount;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
in vec3 cameraRelPos;

out vec4 fragColor;

float columnDropHash(vec3 relPos, vec2 uv) {
    vec2 cell = floor(vec2(relPos.x * 4.0 + relPos.z * 7.0, uv.x * 8.0));
    return fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }

    if (RainDepthEnabled != 0) {
        vec2 depthUv = cameraRelPos.xz * RainDepthParams.x + 0.5;
        if (depthUv.x >= 0.0 && depthUv.x <= 1.0 && depthUv.y >= 0.0 && depthUv.y <= 1.0) {
            float depth = texture(RainDepthSampler, depthUv).r;
            if (depth < 0.99999) {
                float roofRelY = (RainDepthParams.y + RainDepthParams.z) - depth * RainDepthParams.z;
                if (cameraRelPos.y < roofRelY - RainDepthParams.w) {
                    discard;
                }
            }
        }
    }

    if (VolumeCount > 0) {
        float blocked = 0.0;
        for (int i = 0; i < VolumeCount; i++) {
            vec4 row0 = texelFetch(VolumeSampler, ivec2(0, i), 0);
            vec4 row1 = texelFetch(VolumeSampler, ivec2(1, i), 0);
            vec4 row2 = texelFetch(VolumeSampler, ivec2(2, i), 0);
            vec4 params = texelFetch(VolumeSampler, ivec2(3, i), 0);

            vec3 local = vec3(
                dot(row0.xyz, cameraRelPos) + row0.w,
                dot(row1.xyz, cameraRelPos) + row1.w,
                dot(row2.xyz, cameraRelPos) + row2.w
            );

            float maxAxis = max(abs(local.x), max(abs(local.y), abs(local.z)));
            if (maxAxis <= 0.5) {
                float edgeWeight = params.y > 0.0001
                    ? clamp((0.5 - maxAxis) / params.y, 0.0, 1.0)
                    : 1.0;
                blocked = max(blocked, clamp(params.x * edgeWeight, 0.0, 1.0));
                if (blocked >= 0.999) {
                    discard;
                }
            }
        }
        if (blocked > 0.0 && columnDropHash(cameraRelPos, texCoord0) < blocked) {
            discard;
        }
    }

    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
