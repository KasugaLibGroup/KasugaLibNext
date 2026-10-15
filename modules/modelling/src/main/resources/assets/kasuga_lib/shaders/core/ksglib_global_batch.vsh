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
uniform samplerBuffer ksg_InstanceData;
uniform samplerBuffer ksg_BoneTransforms;
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

int ksg_BatchBoneOffset = 0;
#define KSG_BONE_INDEX_OFFSET ksg_BatchBoneOffset
#moj_import <kasuga_lib:ksg_skinning.transform.glsl>

const int KSG_OBJECT_TEXELS = 9;

void main() {
    int objectBase = UV1.x * KSG_OBJECT_TEXELS;
    mat4 modelPose = mat4(
        texelFetch(ksg_InstanceData, objectBase),
        texelFetch(ksg_InstanceData, objectBase + 1),
        texelFetch(ksg_InstanceData, objectBase + 2),
        texelFetch(ksg_InstanceData, objectBase + 3)
    );
    mat3 modelNormal = mat3(
        texelFetch(ksg_InstanceData, objectBase + 4).xyz,
        texelFetch(ksg_InstanceData, objectBase + 5).xyz,
        texelFetch(ksg_InstanceData, objectBase + 6).xyz
    );
    vec4 parameters = texelFetch(ksg_InstanceData, objectBase + 7);
    vec4 packedLighting = texelFetch(ksg_InstanceData, objectBase + 8);
    ksg_BatchBoneOffset = int(parameters.y);

    vec3 skinnedPosition = Position;
    vec3 skinnedNormal = Normal;
    vec4 skinnedTangent = Tangent;
    if (parameters.z > 0.5) {
        ksg_applyGpuSkinning(skinnedPosition, skinnedNormal, skinnedTangent);
    }

    vec4 posWorld = modelPose * vec4(skinnedPosition, 1.0);
    vertexColor = vec4(Color.rgb, Color.a);
    lightMapColor = texelFetch(Sampler2, ivec2(packedLighting.xy) / 16, 0);
    overlayColor = texelFetch(Sampler1, ivec2(packedLighting.zw), 0);
    texCoord0 = UV0;
    textureUV = TextureUV;
    textureBounds = TextureBounds;
    alphaCutoff = AlphaCutoff;

    vec4 viewPos4 = ModelViewMat * posWorld;
    viewPos = viewPos4.xyz;
    vertexDistance = fog_distance(viewPos, FogShape);
    mat3 normalMatrix = mat3(ModelViewMat) * modelNormal;
    viewNormal = normalize(normalMatrix * skinnedNormal);

    vec3 tangent = normalize(normalMatrix * skinnedTangent.xyz);
    vec3 bitangent = cross(viewNormal, tangent) * skinnedTangent.w;
    TBN = mat3(tangent, bitangent, viewNormal);

    mat3 viewRotation = mat3(ModelViewMat);
    viewLight0_Direction = normalize(viewRotation * Light0_Direction);
    viewLight1_Direction = normalize(viewRotation * Light1_Direction);
    // Zero/NaN light directions (uniforms left at defaults in manual draw paths) would poison
    // NdotH/specular — fall back to a valid direction.
    if (!(length(viewLight0_Direction) > 1e-6)) viewLight0_Direction = vec3(0.0, 1.0, 0.0);
    if (!(length(viewLight1_Direction) > 1e-6)) viewLight1_Direction = vec3(0.0, 1.0, 0.0);
    gl_Position = ProjMat * viewPos4;
}
