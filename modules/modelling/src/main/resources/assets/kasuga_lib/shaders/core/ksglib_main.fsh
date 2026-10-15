#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D ksg_NormalMap;
uniform sampler2D ksg_SpecularMap;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float ksg_EmissiveStrength;
uniform float ksg_ParallaxScale;
uniform int ksg_ParallaxSamples;
uniform float ksg_AmbientLightEnhancement;
uniform float ksg_StylizedShadingStrength;
uniform int ksg_AlphaMode;
uniform int ksg_OitMode;
uniform int ksg_PeelEnabled;
uniform sampler2D ksg_PeelPrevious;
uniform sampler2D ksg_PeelScene;
uniform sampler2D ksg_PeelCoverage;
uniform sampler2D ksg_PeelFootprint;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec2 textureUV;
flat in vec4 textureBounds;
flat in float alphaCutoff;
in vec3 viewPos;
in vec3 viewNormal;
in mat3 TBN;
in vec3 viewLight0_Direction;
in vec3 viewLight1_Direction;

out vec4 fragColor;

float DistributionGGX(float NdotH, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH2 = NdotH * NdotH;

    float nom   = a2;
    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    denom = 3.14159265 * denom * denom;
    return nom / denom;
}

float GeometrySchlickGGX(float NdotV, float roughness) {
    float r = (roughness + 1.0);
    float k = (r * r) / 8.0;

    float nom   = NdotV;
    float denom = NdotV * (1.0 - k) + k;
    return nom / denom;
}

float GeometrySmith(float NdotV, float NdotL, float roughness) {
    float ggx2 = GeometrySchlickGGX(NdotV, roughness);
    float ggx1 = GeometrySchlickGGX(NdotL, roughness);
    return ggx1 * ggx2;
}

vec3 FresnelSchlick(vec3 F0, float VdotH) {
    return F0 + (1.0 - F0) * pow(1.0 - VdotH, 5.0);
}

float SubsurfaceScattering(float NdotL, float sssStrength) {
    float wrap = 0.5;  // 固定包裹参数，也可随强度变化
    float wrapNdotL = (NdotL + wrap) / (1.0 + wrap);
    // 在原始 NdotL 和包裹光照之间根据强度插值
    return mix(max(NdotL, 0.0), clamp(wrapNdotL, 0.0, 1.0), sssStrength);
}

// Preserve readable albedo when no shader pack supplies bounced light.  The
// smooth two-step ramp keeps the result stylized without introducing unstable
// hard bands on animated normals.  A strength of zero restores Lambert.
float StylizedDiffuse(float NdotL) {
    float shadow = smoothstep(-0.35, 0.15, NdotL);
    float light = smoothstep(0.25, 0.8, NdotL);
    float ramp = 0.28 + shadow * 0.30 + light * 0.42;
    return mix(max(NdotL, 0.0), ramp, ksg_StylizedShadingStrength);
}

// 预定义金属的 F0 值（根据光学常数 n,k计算）
vec3 getPredefinedF0(float value) {
    // value 为归一化后的纹理值（0~1）
    // 对应位值 230~254，使用分段整数比较
    if (value < 231.0 / 255.0) { // 230: Iron
        return vec3(0.531, 0.518, 0.560);
    }
    if (value < 232.0 / 255.0) { // 231: Gold
        return vec3(0.916, 0.850, 0.763);
    }
    if (value < 233.0 / 255.0) { // 232: Aluminum
        return vec3(0.871, 0.801, 0.754);
    }
    if (value < 234.0 / 255.0) { // 233: Chrome
        return vec3(0.601, 0.601, 0.565);
    }
    if (value < 235.0 / 255.0) { // 234: Copper
        return vec3(0.832, 0.725, 0.670);
    }
    if (value < 236.0 / 255.0) { // 235: Lead
        return vec3(0.457, 0.441, 0.406);
    }
    if (value < 237.0 / 255.0) { // 236: Platinum
        return vec3(0.571, 0.530, 0.494);
    }
    if (value < 238.0 / 255.0) { // 237: Silver
        return vec3(0.345, 0.333, 0.331);
    }
    // 其余未定义金属（238~254）默认为黄金
    return vec3(0.916, 0.850, 0.763);
}

vec2 steepParallaxMapping(float depth, vec2 texCoords, vec3 viewDir) {
    if (depth >= 1.0f) return texCoords;
    float height = 1.0 - depth;
    float scale = ksg_ParallaxScale;
    float layers = float(ksg_ParallaxSamples);
    float layerDepth = 1.0 / layers;
    float currentLayerDepth = 0.0;

    vec2 deltaTexCoords = (viewDir.xy / viewDir.z) * scale / layers * height;
    vec2 currentTexCoords = texCoords;
    vec2 prevTexCoords = texCoords;

    float prevHeight = 0.0;
    float currentheight = 0.0;

    for (int i = 0; i < layers; ++i) {
        currentLayerDepth += layerDepth;
        currentTexCoords += deltaTexCoords;
        float sampledHeight = 1.0 - texture(ksg_NormalMap, currentTexCoords).a;
        if (currentLayerDepth >= sampledHeight) {
            prevTexCoords = currentTexCoords - deltaTexCoords;
            prevHeight = 1.0 - texture(ksg_NormalMap, prevTexCoords).a;
            currentheight = sampledHeight;
            break;
        }
    }

    if (currentLayerDepth < 1.0) {
        float weight = (currentLayerDepth - prevHeight) / (currentheight - prevHeight + 0.00001);
        currentTexCoords = mix(prevTexCoords, currentTexCoords, weight);
    }

    return clamp(currentTexCoords, 0.0, 1.0);
}


void main() {
    // Keep sampled depth identical to the value used by the peel comparison.
    // The fixed-function write can round down by one ULP on macOS. An explicit
    // write must be defined on every path, not only the peel/footprint paths.
    gl_FragDepth = gl_FragCoord.z;
    vec2 boundsSize = textureBounds.zw - textureBounds.xy;
    vec2 originalTexCoord = texCoord0;
    if (abs(boundsSize.x) > 0.000001 && abs(boundsSize.y) > 0.000001) {
        // PMX uses repeating texture coordinates. Repeat in local texture space
        // before mapping into the shared atlas, otherwise out-of-range UVs sample
        // adjacent sprites in the atlas.
        originalTexCoord = textureBounds.xy + fract(textureUV) * boundsSize;
    }
    float depth = texture(ksg_NormalMap, originalTexCoord).a;
    vec3 V = normalize(-viewPos);
    vec3 viewDir = normalize(transpose(TBN) * V);
    vec2 texCoord = steepParallaxMapping(depth, originalTexCoord, viewDir);

    vec4 albedo = texture(Sampler0, texCoord);
    // Alpha classification is supplied by the material pass. MASK fragments
    // are depth-writing only after rejection; BLEND keeps its effective alpha
    // for conventional source-over blending and only removes invisible texels.
    float effectiveAlpha = albedo.a * vertexColor.a * ColorModulator.a;
    // Sample the material before per-pixel peel rejection. The atlas/parallax
    // lookups use implicit derivatives; early divergent discard made the
    // native macOS shader lose the model behind the first water layer.
    // Keep the expensive lighting below both transparency and alpha rejection.
    if (ksg_PeelEnabled == 2) {
        if (gl_FragCoord.z > texelFetch(ksg_PeelScene, ivec2(gl_FragCoord.xy), 0).r) discard;
    }
    if (ksg_PeelEnabled == 1) {
        ivec2 pixel = ivec2(gl_FragCoord.xy);
        if (texelFetch(ksg_PeelFootprint, pixel, 0).r == 0.0) discard;
        if (texelFetch(ksg_PeelCoverage, pixel, 0).a >= 1.0) discard;
        if (gl_FragCoord.z <= texelFetch(ksg_PeelPrevious, pixel, 0).r
            || gl_FragCoord.z > texelFetch(ksg_PeelScene, pixel, 0).r) discard;
    }
    if (ksg_AlphaMode == 1) {
        if (effectiveAlpha < alphaCutoff) discard;
        effectiveAlpha = 1.0;
    } else if (ksg_AlphaMode == 2) {
        // BLEND's work-saving cutoff is intentionally independent from the
        // material MASK cutoff. A translucent material must retain alpha
        // values such as 0.25 rather than inheriting the usual 0.5 mask rule.
        if (effectiveAlpha <= (1.0 / 255.0)) discard;
        if (ksg_OitMode == 3) {
            // Solid coverage is rendered with color/depth writes before world
            // translucency. Use effective alpha (including vertex fades), not
            // texture alpha alone. Do not turn almost-opaque texels into masks.
            if (effectiveAlpha < 1.0) discard;
            effectiveAlpha = 1.0;
        } else if (ksg_OitMode == 1 || ksg_OitMode == 2 || ksg_OitMode == 4 || ksg_OitMode == 5) {
            // These texels already contributed color and depth. Including
            // them again would average hidden layers into an opaque surface.
            if (effectiveAlpha >= 1.0) discard;
        }
    } else {
        effectiveAlpha = 1.0;
    }
    if (ksg_OitMode == 5) {
        // Exact alpha footprint, including parallax and material fades, but
        // without evaluating lighting. Scene depth was tested above.
        fragColor = vec4(1.0);
        return;
    }
    if (ksg_OitMode == 2) {
        // Revealage consumes only alpha. Vanilla linear_fog preserves alpha,
        // so normal/specular sampling and the entire lighting pass are unused.
        fragColor = vec4(0.0, 0.0, 0.0, clamp(effectiveAlpha, 0.0, 1.0));
        return;
    }
    vec3 normalTexture = texture(ksg_NormalMap, texCoord).rgb;
    vec4 specularTexture = texture(ksg_SpecularMap, texCoord);

    vec2 normalXY = normalTexture.rg * 2.0 - 1.0;
    float normalZ = sqrt(max(0.0, 1.0 - dot(normalXY, normalXY)));
    vec3 normalTS = vec3(normalXY, normalZ);
    float materialAO = normalTexture.b;

    float perceptualSmoothness = specularTexture.r;
    float roughness = clamp(pow(1.0 - perceptualSmoothness, 2.0), 0.0, 1.0);

    float f0Value = specularTexture.g;
    float metallic;
    vec3 F0;

    if (f0Value >= 230.0 / 255.0 && f0Value < 1) {
        metallic = 1.0;
        F0 = getPredefinedF0(f0Value);
    } else if (f0Value >= 1.0) {
        metallic = 1.0;
        F0 = albedo.rgb;
    } else {
        metallic = 0.0;
        F0 = vec3(f0Value);
    }

    float porositySSS = specularTexture.b;
    float porosity = 0.0;
    float sssStrength = 0.0;

    if (porositySSS <= 64.0 / 255.0) {
        porosity = porositySSS * (255.0 / 64.0); // 线性映射到 0~1
    } else {
        sssStrength = (porositySSS - 65.0 / 255.0) * (255.0 / 190.0); // 线性映射到 0~1
    }

    float emissionStrengthTex = specularTexture.a;
    float emission = emissionStrengthTex * ksg_EmissiveStrength;

    float aoCombined = materialAO * lightMapColor.r;
    aoCombined *= (1.0 - porosity * 0.5);

    vec3 N = normalize(TBN * normalTS);
    if (!(length(N) > 1e-6)) {
        N = vec3(0.0, 1.0, 0.0);
    }
    float NdotV = max(dot(N, V), 0.0);
    mat3 viewMatrix = mat3(ModelViewMat);
    vec3 L0 = viewLight0_Direction;
    vec3 L1 = viewLight1_Direction;
    vec3 lightColor = vec3(1.0);
    vec3 kD = (1.0 - metallic) * (1.0 - F0);
    vec3 diffuse = kD * albedo.rgb / 3.14159265;

    vec3 color = vec3(0.0);
    // Directional (L0/L1) intensity must follow the block light level like vanilla (the light texture
    // Sampler2 encodes sky+block light as a brightness); otherwise models in dark spots render as bright
    // as daylight ones. lightLevel = 0 in pitch darkness, 1 in full light.
    float lightLevel = lightMapColor.r;
    if (!(lightLevel >= 0.0 && lightLevel <= 1.0)) lightLevel = 0.0;
    for (int i = 0; i < 2; ++i) {
        vec3 L = (i == 0) ? L0 : L1;
        if (!(length(L) > 1e-6)) L = vec3(0.0, 1.0, 0.0);
        vec3 H = normalize(V + L);
        float rawNdotL = dot(N, L);
        // NaN guard: degenerate normals/tangents/lights would otherwise poison the whole lighting
        // chain and render black. NaN fails every comparison, so this catches it.
        if (!(rawNdotL >= -1.0 && rawNdotL <= 1.0)) rawNdotL = 0.0;
        float NdotL = max(rawNdotL, 0.0);
        float VdotH = max(dot(V, H), 0.0);
        float NdotH = max(dot(N, H), 0.0);
        if (!(VdotH >= 0.0 && VdotH <= 1.0)) VdotH = 0.0;
        if (!(NdotH >= 0.0 && NdotH <= 1.0)) NdotH = 1.0;

        float D = DistributionGGX(NdotH, roughness);
        float G = GeometrySmith(NdotV, NdotL, roughness);
        vec3 F = FresnelSchlick(F0, VdotH);
        vec3 specular = (D * G * F) / (4.0 * NdotV * NdotL + 0.001);

        float diffuseFactor = StylizedDiffuse(rawNdotL);
        if (sssStrength > 0.0 && metallic < 0.5) {
            diffuseFactor = mix(diffuseFactor,
                    SubsurfaceScattering(NdotL, 1.0), sssStrength);
        }
        color += diffuse * lightColor * diffuseFactor * lightLevel;
        color += specular * lightColor * NdotL * lightLevel;
    }

    vec3 ambient = vec3(0.2 * ksg_AmbientLightEnhancement) * albedo.rgb * aoCombined;
    color += ambient;

    color *= vertexColor.rgb;
    color = mix(overlayColor.rgb, color, overlayColor.a);
    color *= ColorModulator.rgb;

    color += albedo.rgb * emission;
    float alpha = effectiveAlpha;
    vec4 finalColor = vec4(color, alpha);

    vec4 shadedColor = linear_fog(finalColor, vertexDistance, FogStart, FogEnd, FogColor);
    if (ksg_OitMode == 1) {
        // Bounded depth-aware weighting favors fragments nearer the camera
        // without making the aggregate depend on draw order.
        float oitWeight = clamp(
                pow(max(0.0, 1.0 - gl_FragCoord.z), 3.0) * 8.0 + 0.01,
                0.01, 8.0);
        fragColor = vec4(shadedColor.rgb * shadedColor.a * oitWeight,
                shadedColor.a * oitWeight);
    } else if (ksg_OitMode == 4) {
        fragColor = vec4(shadedColor.rgb * shadedColor.a, shadedColor.a);
    } else {
        fragColor = shadedColor;
    }
}
