#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec3 Normal;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec4 Tangent;
in int BoneBindingType;
in vec4 BoneIndices;
in vec4 BoneWeights;
in vec3 sdefR0;
in vec3 sdefR1;
in vec3 sdefC;
in vec2 TextureUV;
in vec4 TextureBounds;
in float AlphaCutoff;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform samplerBuffer ksg_BoneTransforms;
uniform mat4 ksg_ModelPoseMat;
uniform mat3 ksg_ModelNormalMat;
uniform float ksg_BrightnessScale;
uniform ivec2 ksg_PackedLight;
uniform ivec2 ksg_PackedOverlay;
uniform int ksg_GpuSkinningEnabled;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec2 textureUV;
flat out vec4 textureBounds;
flat out float alphaCutoff;
out vec3 viewPos;
out vec3 viewNormal;
out mat3 TBN;
out vec3 viewLight0_Direction;
out vec3 viewLight1_Direction;

#moj_import <kasuga_lib:ksg_skinning.transform.glsl>

void main() {
    vec3 skinnedPosition = Position;
    vec3 skinnedNormal = Normal;
    vec4 skinnedTangent = Tangent;
    if (ksg_GpuSkinningEnabled > 0) {
        ksg_applyGpuSkinning(skinnedPosition, skinnedNormal, skinnedTangent);
    }

    vec4 posWorld = (ksg_ModelPoseMat * vec4(skinnedPosition, 1.0));
    // Vertex color is the model's authored color only — NOT scaled by light brightness. Brightness (0..1
    // from the light lookup) must only darken the ambient term via lightMapColor/aoCombined in the
    // fragment shader; multiplying it into vertexColor zeroes the directional light too, so models in
    // dark spots render pure black instead of dark-but-visible.
    vertexColor = vec4(Color.rgb, Color.a);
    lightMapColor = texelFetch(Sampler2, ksg_PackedLight / 16, 0);
    overlayColor = texelFetch(Sampler1, ksg_PackedOverlay, 0);

    texCoord0 = UV0;
    textureUV = TextureUV;
    textureBounds = TextureBounds;
    alphaCutoff = AlphaCutoff;
    vec4 viewPos4 = ModelViewMat * posWorld;
    viewPos = viewPos4.xyz;
    vertexDistance = fog_distance(viewPos, FogShape);
    mat3 normalMatrix = mat3(ModelViewMat) * ksg_ModelNormalMat;
    viewNormal = normalize(normalMatrix * skinnedNormal);
    // Zero/NaN normals (degenerate geometry from zero-thickness faces) would poison TBN and all lighting
    // below — fall back to a valid direction so the fragment shader never sees NaN.
    if (!(length(viewNormal) > 1e-6)) {
        viewNormal = vec3(0.0, 1.0, 0.0);
    }

    vec3 T = normalize(normalMatrix * skinnedTangent.xyz);
    if (!(length(T) > 1e-6)) {
        T = vec3(1.0, 0.0, 0.0);
    }
    vec3 B = cross(viewNormal, T) * skinnedTangent.w;
    TBN = mat3(T, B, viewNormal);

    mat3 viewRot = mat3(ModelViewMat);
    viewLight0_Direction = normalize(viewRot * Light0_Direction);
    viewLight1_Direction = normalize(viewRot * Light1_Direction);
    // Zero/NaN light directions (uniforms left at defaults in manual draw paths) would poison
    // NdotH/specular — fall back to a valid direction.
    if (!(length(viewLight0_Direction) > 1e-6)) viewLight0_Direction = vec3(0.0, 1.0, 0.0);
    if (!(length(viewLight1_Direction) > 1e-6)) viewLight1_Direction = vec3(0.0, 1.0, 0.0);

    gl_Position = ProjMat * viewPos4;
}
