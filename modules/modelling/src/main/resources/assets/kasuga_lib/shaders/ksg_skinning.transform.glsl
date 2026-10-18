// created by glowingstone124 (https://github.com/glowingstone124)

#version 150

in vec3 Position;
in vec3 Normal;
in vec4 Tangent;
// BoneBindingType is Type.INT in the vertex format; BoneIndices is stored as
// floats and rounded to int here to avoid integer-vertex-attribute issues.
in int BoneBindingType;
in vec4 BoneIndices;
in vec4 BoneWeights;
in vec3 sdefR0;
in vec3 sdefR1;
in vec3 sdefC;

uniform samplerBuffer ksg_BoneTransforms;

#ifndef KSG_BONE_INDEX_OFFSET
#define KSG_BONE_INDEX_OFFSET 0
#endif

out vec3 tf_Position;

mat4 ksg_readBoneTransform(int boneIndex, int offset) {
    int base = (boneIndex + KSG_BONE_INDEX_OFFSET) * 9 + offset;
    vec4 col0 = texelFetch(ksg_BoneTransforms, base);
    vec4 col1 = texelFetch(ksg_BoneTransforms, base + 1);
    vec4 col2 = texelFetch(ksg_BoneTransforms, base + 2);
    return mat4(
    vec4(col0.xyz, 0.0),
    vec4(col1.xyz, 0.0),
    vec4(col2.xyz, 0.0),
    vec4(col0.w, col1.w, col2.w, 1.0)
    );
}

mat4 ksg_readBoneAbsTransform(int boneIndex) {
    return ksg_readBoneTransform(boneIndex, 0);
}

mat4 ksg_readBoneInverseTransform(int boneIndex) {
    return ksg_readBoneTransform(boneIndex, 3);
}

mat3 ksg_readBoneNormalTransform(int boneIndex) {
    int base = boneIndex * 9 + 6;
    return mat3(
    texelFetch(ksg_BoneTransforms, base).xyz,
    texelFetch(ksg_BoneTransforms, base + 1).xyz,
    texelFetch(ksg_BoneTransforms, base + 2).xyz
    );
}

vec4 quat_mul(vec4 q1, vec4 q2) {
    return vec4(
    q1.w * q2.x + q1.x * q2.w + q1.y * q2.z - q1.z * q2.y,
    q1.w * q2.y - q1.x * q2.z + q1.y * q2.w + q1.z * q2.x,
    q1.w * q2.z + q1.x * q2.y - q1.y * q2.x + q1.z * q2.w,
    q1.w * q2.w - q1.x * q2.x - q1.y * q2.y - q1.z * q2.z
    );
}

vec4 quat_conj(vec4 q) {
    return vec4(-q.x, -q.y, -q.z, q.w);
}

vec4 quat_from_mat3(mat3 m) {
    float trace = m[0][0] + m[1][1] + m[2][2];
    vec4 q = vec4(0.0);
    if (trace > 0.0) {
        float s = sqrt(trace + 1.0) * 2.0;
        q.w = 0.25 * s;
        q.x = (m[1][2] - m[2][1]) / s;
        q.y = (m[2][0] - m[0][2]) / s;
        q.z = (m[0][1] - m[1][0]) / s;
    } else if ((m[0][0] > m[1][1]) && (m[0][0] > m[2][2])) {
        float s = sqrt(1.0 + m[0][0] - m[1][1] - m[2][2]) * 2.0;
        q.w = (m[1][2] - m[2][1]) / s;
        q.x = 0.25 * s;
        q.y = (m[1][0] + m[0][1]) / s;
        q.z = (m[2][0] + m[0][2]) / s;
    } else if (m[1][1] > m[2][2]) {
        float s = sqrt(1.0 + m[1][1] - m[0][0] - m[2][2]) * 2.0;
        q.w = (m[2][0] - m[0][2]) / s;
        q.x = (m[1][0] + m[0][1]) / s;
        q.y = 0.25 * s;
        q.z = (m[2][1] + m[1][2]) / s;
    } else {
        float s = sqrt(1.0 + m[2][2] - m[0][0] - m[1][1]) * 2.0;
        q.w = (m[0][1] - m[1][0]) / s;
        q.x = (m[2][0] + m[0][2]) / s;
        q.y = (m[2][1] + m[1][2]) / s;
        q.z = 0.25 * s;
    }
    return q;
}

vec3 quat_rotate(vec4 q, vec3 v) {
    vec4 vQuat = vec4(v, 0.0);
    vec4 qConj = vec4(-q.x, -q.y, -q.z, q.w);
    return quat_mul(quat_mul(q, vQuat), qConj).xyz;
}

vec4 quat_slerp(vec4 q0, vec4 q1, float t) {
    q0 = normalize(q0);
    q1 = normalize(q1);
    float cosTheta = dot(q0, q1);
    if (cosTheta < 0.0) {
        q1 = -q1;
        cosTheta = -cosTheta;
    }
    if (cosTheta > 0.9995) {
        return normalize(mix(q0, q1, t));
    }
    float theta = acos(clamp(cosTheta, -1.0, 1.0));
    float sinTheta = sin(theta);
    return (sin((1.0 - t) * theta) * q0 + sin(t * theta) * q1) / sinTheta;
}

// Fix (Bug-GPU-1, equivalent to pmx_bugs.md CPU Bug #8):
// BDEF normal must use the normal matrix of the composite skinning deformation
// M = T_anim * T_bind^(-1), not just N(T_anim).
// The boneNormal buffer (offset 6) stores N(T_anim), which is incorrect for skinning.
// We compute composite3x3 = mat3(absTransform) * mat3(invTransform) and
// use its inverse-transpose as the correct normal matrix.
void ksg_applyBdefSkinning(inout vec3 position, inout vec3 normal, inout vec4 tangent) {
    vec4 skinnedPosition = vec4(0.0);
    vec3 skinnedNormal = vec3(0.0);
    vec3 skinnedTangent = vec3(0.0);
    float totalWeight = 0.0;
    for (int i = 0; i < 4; i++) {
        float weight = BoneWeights[i];
        if (weight <= 0.0) {
            continue;
        }
        int boneIndex = int(BoneIndices[i] + 0.5);
        mat4 invTransform = ksg_readBoneInverseTransform(boneIndex);
        mat4 absTransform = ksg_readBoneAbsTransform(boneIndex);
        vec4 localPos = invTransform * vec4(position, 1.0);
        skinnedPosition += (absTransform * localPos) * weight;

        // Compute composite normal matrix: N(T_anim * T_bind^(-1))
        mat3 composite3x3 = mat3(absTransform) * mat3(invTransform);
        mat3 compositeNormal = transpose(inverse(composite3x3));
        skinnedNormal += (compositeNormal * normal) * weight;
        skinnedTangent += (compositeNormal * tangent.xyz) * weight;
        totalWeight += weight;
    }
    if (totalWeight > 0.0) {
        position = skinnedPosition.xyz / totalWeight;
        normal = normalize(skinnedNormal);
        tangent = vec4(normalize(skinnedTangent), tangent.w);
    }
}

// Fix (Bug-GPU-2, equivalent to pmx_bugs.md CPU Bug #7):
// Standard DQBS (Dual Quaternion Blend Skinning) formula:
//   v' = DQ_blend(w_i, DQ(T_anim_i * T_bind_i^(-1))) · v
// The DQ must be built from the composite skinning matrix M = T_anim * T_bind^(-1),
// NOT from T_anim alone.
// The previous code built DQ from absTransform only and blended local-space positions
// separately, which does not produce correct results even at bind pose.
void ksg_applyQdefSkinning(inout vec3 position, inout vec3 normal, inout vec4 tangent) {
    vec4 blend_qr = vec4(0.0);
    vec4 blend_qd = vec4(0.0);
    float totalWeight = 0.0;
    vec4 reference_qr = vec4(0.0);
    bool hasReference = false;
    for (int i = 0; i < 4; i++) {
        float weight = BoneWeights[i];
        if (weight <= 0.0) continue;
        int boneIndex = int(BoneIndices[i] + 0.5);
        mat4 invTransform = ksg_readBoneInverseTransform(boneIndex);
        mat4 absTransform = ksg_readBoneAbsTransform(boneIndex);
        // Build skinning composite M = T_anim * T_bind^(-1)
        mat4 composite = absTransform * invTransform;
        vec4 qr = quat_from_mat3(mat3(composite));
        vec3 t = composite[3].xyz;
        vec4 qd = 0.5 * quat_mul(vec4(t, 0.0), qr);
        totalWeight += weight; // Coverage uses the original positive weight, not the hemisphere sign.
        if (!hasReference) {
            reference_qr = qr;
            hasReference = true;
        }
        if (dot(reference_qr, qr) < 0.0) {
            weight = -weight;
        }
        blend_qr += qr * weight;
        blend_qd += qd * weight;
    }
    if (totalWeight <= 0.0) {
        return;
    }
    // Both DQ components must use the SAME real-quaternion norm. Normalizing
    // only the real part shrinks blended translations when bones rotate apart.
    float blendLength = length(blend_qr);
    if (blendLength <= 1.0e-6) return;
    blend_qr /= blendLength;
    blend_qd /= blendLength;
    blend_qd -= dot(blend_qr, blend_qd) * blend_qr;
    // Apply blended DQ directly to model-space position
    vec3 rotatedPos = quat_rotate(blend_qr, position);
    vec4 trans4 = 2.0 * quat_mul(blend_qd, quat_conj(blend_qr));
    position = rotatedPos + trans4.xyz;
    normal = normalize(quat_rotate(blend_qr, normal));
    tangent.xyz = normalize(quat_rotate(blend_qr, tangent.xyz));
}

void ksg_applySdefSkinning(inout vec3 position, inout vec3 normal, inout vec4 tangent) {
    float totalWeight = BoneWeights.x + BoneWeights.y;
    if (totalWeight <= 0.0) return;

    float w0 = BoneWeights.x / totalWeight;
    float w1 = BoneWeights.y / totalWeight;
    mat4 skin0 = ksg_readBoneAbsTransform(int(BoneIndices.x + 0.5))
            * ksg_readBoneInverseTransform(int(BoneIndices.x + 0.5));
    mat4 skin1 = ksg_readBoneAbsTransform(int(BoneIndices.y + 0.5))
            * ksg_readBoneInverseTransform(int(BoneIndices.y + 0.5));

    // PMX stores C/R0/R1 in model space. SDEF rotates the point around the
    // weighted pivot with the spherical interpolation of both skin rotations.
    vec4 q0 = normalize(quat_from_mat3(mat3(skin0)));
    vec4 q1 = normalize(quat_from_mat3(mat3(skin1)));
    vec4 rotation = quat_slerp(q0, q1, w1);
    vec3 weightedR = sdefR0 * w0 + sdefR1 * w1;
    vec3 pivot0 = (skin0 * vec4(sdefC + sdefR0, 1.0)).xyz;
    vec3 pivot1 = (skin1 * vec4(sdefC + sdefR1, 1.0)).xyz;

    position = quat_rotate(rotation, position - sdefC - weightedR)
            + pivot0 * w0 + pivot1 * w1;
    normal = normalize(quat_rotate(rotation, normal));
    tangent.xyz = normalize(quat_rotate(rotation, tangent.xyz));
}

void ksg_applyGpuSkinning(inout vec3 position, inout vec3 normal, inout vec4 tangent) {
    if (BoneBindingType == 2) {
        ksg_applyQdefSkinning(position, normal, tangent);
        return;
    } else if (BoneBindingType == 1) {
        ksg_applySdefSkinning(position, normal, tangent);
        return;
    }
    ksg_applyBdefSkinning(position, normal, tangent);
}

void main() {
    vec3 skinnedPosition = Position;
    vec3 skinnedNormal = Normal;
    vec4 skinnedTangent = Tangent;
    ksg_applyGpuSkinning(skinnedPosition, skinnedNormal, skinnedTangent);
    tf_Position = skinnedPosition;
    gl_Position = vec4(0.0);
}
