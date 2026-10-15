// SPDX-License-Identifier: LGPL-3.0-or-later

#include <jni.h>
#include <box3d/box3d.h>

#include <math.h>
#include <stdint.h>
#include <stdlib.h>

#define JNI_METHOD(name) Java_lib_kasuga_rendering_models_uml_dynamic_physics_box3d_NativeBox3D_##name

static b3Vec3 vec3(jfloat x, jfloat y, jfloat z)
{
    return (b3Vec3){x, y, z};
}

static b3Quat quat(jfloat x, jfloat y, jfloat z, jfloat w)
{
    float lengthSquared = x * x + y * y + z * z + w * w;
    if (!(lengthSquared > 0.0f) || !isfinite(lengthSquared))
    {
        return (b3Quat){.v = {0.0f, 0.0f, 0.0f}, .s = 1.0f};
    }
    float inverseLength = 1.0f / sqrtf(lengthSquared);
    return (b3Quat){.v = {x * inverseLength, y * inverseLength, z * inverseLength}, .s = w * inverseLength};
}

static b3Pos position(jfloat x, jfloat y, jfloat z)
{
    b3Pos result = {x, y, z};
    return result;
}

static jfloat clamp_non_negative(jfloat value)
{
    return isfinite(value) ? fmaxf(0.0f, value) : 0.0f;
}

static b3WorldId world_id(jint packed)
{
    return b3LoadWorldId((uint32_t)packed);
}

static b3BodyId body_id(jlong packed)
{
    return b3LoadBodyId((uint64_t)packed);
}

JNIEXPORT jint JNICALL JNI_METHOD(createWorld)(JNIEnv* env, jclass type, jfloat gx, jfloat gy, jfloat gz,
                                                jboolean sleeping, jboolean continuous)
{
    (void)env;
    (void)type;
    b3WorldDef def = b3DefaultWorldDef();
    def.gravity = vec3(gx, gy, gz);
    def.enableSleep = sleeping == JNI_TRUE;
    def.enableContinuous = continuous == JNI_TRUE;
    return (jint)b3StoreWorldId(b3CreateWorld(&def));
}

JNIEXPORT void JNICALL JNI_METHOD(destroyWorld)(JNIEnv* env, jclass type, jint packed)
{
    (void)env;
    (void)type;
    b3WorldId id = world_id(packed);
    if (b3World_IsValid(id))
    {
        b3DestroyWorld(id);
    }
}

JNIEXPORT void JNICALL JNI_METHOD(step)(JNIEnv* env, jclass type, jint packed, jfloat timeStep, jint subSteps)
{
    (void)env;
    (void)type;
    b3WorldId id = world_id(packed);
    if (b3World_IsValid(id) && timeStep > 0.0f && subSteps > 0)
    {
        b3World_Step(id, timeStep, subSteps);
    }
}

JNIEXPORT void JNICALL JNI_METHOD(setGravity)(JNIEnv* env, jclass type, jint packed, jfloat x, jfloat y, jfloat z)
{
    (void)env;
    (void)type;
    b3World_SetGravity(world_id(packed), vec3(x, y, z));
}

JNIEXPORT void JNICALL JNI_METHOD(setSleepingEnabled)(JNIEnv* env, jclass type, jint packed, jboolean enabled)
{
    (void)env;
    (void)type;
    b3World_EnableSleeping(world_id(packed), enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_METHOD(setContinuousEnabled)(JNIEnv* env, jclass type, jint packed, jboolean enabled)
{
    (void)env;
    (void)type;
    b3World_EnableContinuous(world_id(packed), enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_METHOD(setRestitutionThreshold)(JNIEnv* env, jclass type, jint packed, jfloat threshold)
{
    (void)env;
    (void)type;
    b3World_SetRestitutionThreshold(world_id(packed), threshold);
}

JNIEXPORT void JNICALL JNI_METHOD(setMaximumLinearSpeed)(JNIEnv* env, jclass type, jint packed, jfloat speed)
{
    (void)env;
    (void)type;
    b3World_SetMaximumLinearSpeed(world_id(packed), speed);
}

JNIEXPORT jint JNICALL JNI_METHOD(contactCount)(JNIEnv* env, jclass type, jint packed)
{
    (void)env;
    (void)type;
    return b3World_GetCounters(world_id(packed)).contactCount;
}

JNIEXPORT jlong JNICALL JNI_METHOD(raycast)(JNIEnv* env, jclass type, jint packed,
                                             jfloat ox, jfloat oy, jfloat oz,
                                             jfloat dx, jfloat dy, jfloat dz,
                                             jfloat maximumDistance, jfloatArray output)
{
    (void)type;
    if ((*env)->GetArrayLength(env, output) < 7)
    {
        jclass exception = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        (*env)->ThrowNew(env, exception, "hit array must contain at least 7 floats");
        return 0;
    }
    b3QueryFilter filter = b3DefaultQueryFilter();
    filter.maskBits = UINT64_C(0xFFFF);
    b3Vec3 translation = vec3(dx * maximumDistance, dy * maximumDistance, dz * maximumDistance);
    b3RayResult result = b3World_CastRayClosest(world_id(packed), position(ox, oy, oz), translation, filter);
    if (!result.hit)
    {
        return 0;
    }
    jfloat hit[7] = {
        (jfloat)result.point.x, (jfloat)result.point.y, (jfloat)result.point.z,
        result.normal.x, result.normal.y, result.normal.z,
        result.fraction * maximumDistance
    };
    (*env)->SetFloatArrayRegion(env, output, 0, 7, hit);
    return (jlong)b3StoreBodyId(b3Shape_GetBody(result.shapeId));
}

JNIEXPORT jlong JNICALL JNI_METHOD(createBody)(JNIEnv* env, jclass type, jint packedWorld, jint bodyType,
                                                jfloat px, jfloat py, jfloat pz,
                                                jfloat qx, jfloat qy, jfloat qz, jfloat qw,
                                                jfloat vx, jfloat vy, jfloat vz,
                                                jfloat wx, jfloat wy, jfloat wz,
                                                jfloat linearDamping, jfloat angularDamping,
                                                jboolean bullet)
{
    (void)env;
    (void)type;
    b3BodyDef bodyDef = b3DefaultBodyDef();
    bodyDef.type = (b3BodyType)bodyType;
    bodyDef.position = position(px, py, pz);
    bodyDef.rotation = quat(qx, qy, qz, qw);
    bodyDef.linearVelocity = vec3(vx, vy, vz);
    bodyDef.angularVelocity = vec3(wx, wy, wz);
    bodyDef.linearDamping = linearDamping;
    bodyDef.angularDamping = angularDamping;
    bodyDef.isBullet = bullet == JNI_TRUE;

    b3BodyId bodyId = b3CreateBody(world_id(packedWorld), &bodyDef);
    return (jlong)b3StoreBodyId(bodyId);
}

static b3ShapeDef shape_def(jfloat density, jfloat friction, jfloat restitution,
                            jfloat rollingResistance, jlong categoryBits, jlong maskBits, jint groupIndex)
{
    b3ShapeDef shapeDef = b3DefaultShapeDef();
    shapeDef.density = density;
    shapeDef.baseMaterial.friction = friction;
    shapeDef.baseMaterial.restitution = restitution;
    shapeDef.baseMaterial.rollingResistance = rollingResistance;
    shapeDef.filter.categoryBits = (uint64_t)categoryBits;
    shapeDef.filter.maskBits = (uint64_t)maskBits;
    shapeDef.filter.groupIndex = groupIndex;
    return shapeDef;
}

JNIEXPORT jlong JNICALL JNI_METHOD(addSphereShape)(JNIEnv* env, jclass type, jlong packedBody,
                                                    jfloat cx, jfloat cy, jfloat cz, jfloat radius,
                                                    jfloat density, jfloat friction, jfloat restitution,
                                                    jfloat rollingResistance,
                                                    jlong categoryBits, jlong maskBits, jint groupIndex)
{
    (void)env;
    (void)type;
    b3ShapeDef def = shape_def(density, friction, restitution, rollingResistance,
                              categoryBits, maskBits, groupIndex);
    b3Sphere sphere = {.center = {cx, cy, cz}, .radius = radius};
    return (jlong)b3StoreShapeId(b3CreateSphereShape(body_id(packedBody), &def, &sphere));
}

JNIEXPORT jlong JNICALL JNI_METHOD(addBoxShape)(JNIEnv* env, jclass type, jlong packedBody,
                                                 jfloat cx, jfloat cy, jfloat cz,
                                                 jfloat qx, jfloat qy, jfloat qz, jfloat qw,
                                                 jfloat hx, jfloat hy, jfloat hz,
                                                 jfloat density, jfloat friction, jfloat restitution,
                                                 jfloat rollingResistance,
                                                 jlong categoryBits, jlong maskBits, jint groupIndex)
{
    (void)env;
    (void)type;
    b3ShapeDef def = shape_def(density, friction, restitution, rollingResistance,
                              categoryBits, maskBits, groupIndex);
    b3Transform transform = {.p = vec3(cx, cy, cz), .q = quat(qx, qy, qz, qw)};
    b3BoxHull box = b3MakeTransformedBoxHull(hx, hy, hz, transform);
    return (jlong)b3StoreShapeId(b3CreateHullShape(body_id(packedBody), &def, &box.base));
}

JNIEXPORT jlong JNICALL JNI_METHOD(addCapsuleShape)(JNIEnv* env, jclass type, jlong packedBody,
                                                     jfloat ax, jfloat ay, jfloat az,
                                                     jfloat bx, jfloat by, jfloat bz, jfloat radius,
                                                     jfloat density, jfloat friction, jfloat restitution,
                                                     jfloat rollingResistance,
                                                     jlong categoryBits, jlong maskBits, jint groupIndex)
{
    (void)env;
    (void)type;
    b3ShapeDef def = shape_def(density, friction, restitution, rollingResistance,
                              categoryBits, maskBits, groupIndex);
    b3Capsule capsule = {.center1 = {ax, ay, az}, .center2 = {bx, by, bz}, .radius = radius};
    return (jlong)b3StoreShapeId(b3CreateCapsuleShape(body_id(packedBody), &def, &capsule));
}

JNIEXPORT jlongArray JNICALL JNI_METHOD(createStaticMesh)(JNIEnv* env, jclass type, jint packedWorld,
                                                           jfloatArray packedVertices, jintArray packedIndices,
                                                           jfloat friction, jfloat restitution)
{
    (void)type;
    jsize floatCount = (*env)->GetArrayLength(env, packedVertices);
    jsize indexCount = (*env)->GetArrayLength(env, packedIndices);
    if (floatCount < 9 || floatCount % 3 != 0 || indexCount < 3 || indexCount % 3 != 0)
    {
        jclass exception = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        (*env)->ThrowNew(env, exception, "terrain mesh requires xyz vertices and triangle indices");
        return NULL;
    }

    jfloat* sourceVertices = (*env)->GetFloatArrayElements(env, packedVertices, NULL);
    jint* sourceIndices = (*env)->GetIntArrayElements(env, packedIndices, NULL);
    if (sourceVertices == NULL || sourceIndices == NULL)
    {
        if (sourceVertices != NULL) (*env)->ReleaseFloatArrayElements(env, packedVertices, sourceVertices, JNI_ABORT);
        if (sourceIndices != NULL) (*env)->ReleaseIntArrayElements(env, packedIndices, sourceIndices, JNI_ABORT);
        return NULL;
    }

    int vertexCount = floatCount / 3;
    int triangleCount = indexCount / 3;
    b3Vec3* vertices = malloc((size_t)vertexCount * sizeof(b3Vec3));
    int32_t* indices = malloc((size_t)indexCount * sizeof(int32_t));
    if (vertices == NULL || indices == NULL)
    {
        free(vertices);
        free(indices);
        (*env)->ReleaseFloatArrayElements(env, packedVertices, sourceVertices, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, packedIndices, sourceIndices, JNI_ABORT);
        jclass exception = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
        (*env)->ThrowNew(env, exception, "unable to allocate terrain mesh input");
        return NULL;
    }
    for (int index = 0; index < vertexCount; ++index)
    {
        vertices[index] = vec3(sourceVertices[3 * index], sourceVertices[3 * index + 1], sourceVertices[3 * index + 2]);
    }
    for (int index = 0; index < indexCount; ++index) indices[index] = (int32_t)sourceIndices[index];
    (*env)->ReleaseFloatArrayElements(env, packedVertices, sourceVertices, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, packedIndices, sourceIndices, JNI_ABORT);

    b3MeshDef meshDef = {0};
    meshDef.vertices = vertices;
    meshDef.indices = indices;
    meshDef.vertexCount = vertexCount;
    meshDef.triangleCount = triangleCount;
    meshDef.weldVertices = true;
    meshDef.weldTolerance = 0.0001f;
    meshDef.useMedianSplit = true;
    meshDef.identifyEdges = true;
    b3MeshData* mesh = b3CreateMesh(&meshDef, NULL, 0);
    free(vertices);
    free(indices);
    if (mesh == NULL) return NULL;

    b3BodyDef bodyDef = b3DefaultBodyDef();
    bodyDef.type = b3_staticBody;
    b3BodyId body = b3CreateBody(world_id(packedWorld), &bodyDef);
    b3ShapeDef shapeDef = shape_def(0.0f, friction, restitution, 0.0f, INT64_MIN, -1, 0);
    b3ShapeId shape = b3CreateMeshShape(body, &shapeDef, mesh, b3Vec3_one);
    if (shape.index1 == 0)
    {
        b3DestroyBody(body);
        b3DestroyMesh(mesh);
        return NULL;
    }

    jlong values[2] = {(jlong)b3StoreBodyId(body), (jlong)(intptr_t)mesh};
    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result == NULL)
    {
        b3DestroyBody(body);
        b3DestroyMesh(mesh);
        return NULL;
    }
    (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL JNI_METHOD(destroyStaticMesh)(JNIEnv* env, jclass type,
                                                       jlong packedBody, jlong meshPointer)
{
    (void)env;
    (void)type;
    b3BodyId body = body_id(packedBody);
    if (b3Body_IsValid(body)) b3DestroyBody(body);
    if (meshPointer != 0) b3DestroyMesh((b3MeshData*)(intptr_t)meshPointer);
}

JNIEXPORT void JNICALL JNI_METHOD(finalizeBodyMass)(JNIEnv* env, jclass type, jlong packedBody, jfloat mass)
{
    (void)env;
    (void)type;
    b3BodyId bodyId = body_id(packedBody);
    if (b3Body_GetType(bodyId) != b3_dynamicBody || !(mass > 0.0f))
    {
        return;
    }
    b3Body_ApplyMassFromShapes(bodyId);
    b3MassData massData = b3Body_GetMassData(bodyId);
    if (massData.mass > 0.0f)
    {
        float scale = mass / massData.mass;
        massData.mass = mass;
        massData.inertia.cx = b3MulSV(scale, massData.inertia.cx);
        massData.inertia.cy = b3MulSV(scale, massData.inertia.cy);
        massData.inertia.cz = b3MulSV(scale, massData.inertia.cz);
        b3Body_SetMassData(bodyId, massData);
    }
}

JNIEXPORT void JNICALL JNI_METHOD(destroyBody)(JNIEnv* env, jclass type, jlong packed)
{
    (void)env;
    (void)type;
    b3BodyId id = body_id(packed);
    if (b3Body_IsValid(id))
    {
        b3DestroyBody(id);
    }
}

JNIEXPORT void JNICALL JNI_METHOD(setBodyTransform)(JNIEnv* env, jclass type, jlong packed,
                                                     jfloat px, jfloat py, jfloat pz,
                                                     jfloat qx, jfloat qy, jfloat qz, jfloat qw)
{
    (void)env;
    (void)type;
    b3Body_SetTransform(body_id(packed), position(px, py, pz), quat(qx, qy, qz, qw));
}

JNIEXPORT void JNICALL JNI_METHOD(setBodyTarget)(JNIEnv* env, jclass type, jlong packed,
                                                  jfloat px, jfloat py, jfloat pz,
                                                  jfloat qx, jfloat qy, jfloat qz, jfloat qw,
                                                  jfloat timeStep)
{
    (void)env;
    (void)type;
    b3WorldTransform target = {.p = position(px, py, pz), .q = quat(qx, qy, qz, qw)};
    b3Body_SetTargetTransform(body_id(packed), target, timeStep, true);
}

JNIEXPORT void JNICALL JNI_METHOD(setBodyVelocity)(JNIEnv* env, jclass type, jlong packed,
                                                    jfloat vx, jfloat vy, jfloat vz,
                                                    jfloat wx, jfloat wy, jfloat wz)
{
    (void)env;
    (void)type;
    b3BodyId id = body_id(packed);
    b3Body_SetLinearVelocity(id, vec3(vx, vy, vz));
    b3Body_SetAngularVelocity(id, vec3(wx, wy, wz));
}

JNIEXPORT void JNICALL JNI_METHOD(readBodyState)(JNIEnv* env, jclass type, jlong packed, jfloatArray output)
{
    (void)type;
    if ((*env)->GetArrayLength(env, output) < 13)
    {
        jclass exception = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        (*env)->ThrowNew(env, exception, "state array must contain at least 13 floats");
        return;
    }
    b3BodyId id = body_id(packed);
    b3Pos p = b3Body_GetPosition(id);
    b3Quat q = b3Body_GetRotation(id);
    b3Vec3 v = b3Body_GetLinearVelocity(id);
    b3Vec3 w = b3Body_GetAngularVelocity(id);
    jfloat state[13] = {
        (jfloat)p.x, (jfloat)p.y, (jfloat)p.z,
        q.v.x, q.v.y, q.v.z, q.s,
        v.x, v.y, v.z,
        w.x, w.y, w.z
    };
    (*env)->SetFloatArrayRegion(env, output, 0, 13, state);
}

JNIEXPORT void JNICALL JNI_METHOD(applyImpulse)(JNIEnv* env, jclass type, jlong packed,
                                                 jfloat ix, jfloat iy, jfloat iz,
                                                 jfloat px, jfloat py, jfloat pz)
{
    (void)env;
    (void)type;
    b3Body_ApplyLinearImpulse(body_id(packed), vec3(ix, iy, iz), position(px, py, pz), true);
}

JNIEXPORT void JNICALL JNI_METHOD(applyCenterImpulse)(JNIEnv* env, jclass type, jlong packed,
                                                       jfloat x, jfloat y, jfloat z)
{
    (void)env;
    (void)type;
    b3Body_ApplyLinearImpulseToCenter(body_id(packed), vec3(x, y, z), true);
}

JNIEXPORT void JNICALL JNI_METHOD(applyAngularImpulse)(JNIEnv* env, jclass type, jlong packed,
                                                        jfloat x, jfloat y, jfloat z)
{
    (void)env;
    (void)type;
    b3Body_ApplyAngularImpulse(body_id(packed), vec3(x, y, z), true);
}

JNIEXPORT void JNICALL JNI_METHOD(applyForce)(JNIEnv* env, jclass type, jlong packed,
                                               jfloat x, jfloat y, jfloat z)
{
    (void)env;
    (void)type;
    b3Body_ApplyForceToCenter(body_id(packed), vec3(x, y, z), true);
}

JNIEXPORT void JNICALL JNI_METHOD(applyForceAtPoint)(JNIEnv* env, jclass type, jlong packed,
                                                      jfloat x, jfloat y, jfloat z,
                                                      jfloat px, jfloat py, jfloat pz)
{
    (void)env;
    (void)type;
    b3Body_ApplyForce(body_id(packed), vec3(x, y, z), position(px, py, pz), true);
}

JNIEXPORT void JNICALL JNI_METHOD(applyTorque)(JNIEnv* env, jclass type, jlong packed,
                                                jfloat x, jfloat y, jfloat z)
{
    (void)env;
    (void)type;
    b3Body_ApplyTorque(body_id(packed), vec3(x, y, z), true);
}

JNIEXPORT void JNICALL JNI_METHOD(setBodyGravityScale)(JNIEnv* env, jclass type, jlong packed,
                                                        jfloat scale)
{
    (void)env;
    (void)type;
    b3Body_SetGravityScale(body_id(packed), scale);
}

JNIEXPORT jfloat JNICALL JNI_METHOD(bodyGravityScale)(JNIEnv* env, jclass type, jlong packed)
{
    (void)env;
    (void)type;
    return b3Body_GetGravityScale(body_id(packed));
}

JNIEXPORT void JNICALL JNI_METHOD(setBodyAwake)(JNIEnv* env, jclass type, jlong packed,
                                                 jboolean awake)
{
    (void)env;
    (void)type;
    b3Body_SetAwake(body_id(packed), awake == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_METHOD(setBodyEnabled)(JNIEnv* env, jclass type, jlong packed,
                                                   jboolean enabled)
{
    (void)env;
    (void)type;
    b3BodyId id = body_id(packed);
    if (enabled == JNI_TRUE)
    {
        b3Body_Enable(id);
    }
    else
    {
        b3Body_Disable(id);
    }
}

JNIEXPORT jboolean JNICALL JNI_METHOD(bodyEnabled)(JNIEnv* env, jclass type, jlong packed)
{
    (void)env;
    (void)type;
    return b3Body_IsEnabled(body_id(packed)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_METHOD(wakeBody)(JNIEnv* env, jclass type, jlong packed)
{
    (void)env;
    (void)type;
    b3Body_SetAwake(body_id(packed), true);
}

JNIEXPORT jboolean JNICALL JNI_METHOD(bodyAwake)(JNIEnv* env, jclass type, jlong packed)
{
    (void)env;
    (void)type;
    return b3Body_IsAwake(body_id(packed)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL JNI_METHOD(bodyContactSummary)(JNIEnv* env, jclass type, jlong packed)
{
    (void)env;
    (void)type;
    b3BodyId id = body_id(packed);
    if (!b3Body_IsValid(id) || b3Body_GetType(id) == b3_staticBody)
    {
        return 0;
    }
    int capacity = b3Body_GetContactCapacity(id);
    if (capacity <= 0)
    {
        return 0;
    }
    b3ContactData* contacts = malloc((size_t)capacity * sizeof(b3ContactData));
    if (contacts == NULL)
    {
        return 0;
    }
    int count = b3Body_GetContactData(id, contacts, capacity);
    uint32_t bodyContacts = 0;
    bool touchesStatic = false;
    uint64_t storedId = b3StoreBodyId(id);
    for (int index = 0; index < count; ++index)
    {
        if (contacts[index].manifoldCount <= 0)
        {
            continue;
        }
        b3BodyId bodyA = b3Shape_GetBody(contacts[index].shapeIdA);
        b3BodyId other = B3_ID_EQUALS(bodyA, id)
                ? b3Shape_GetBody(contacts[index].shapeIdB) : bodyA;
        if (b3Body_GetType(other) == b3_staticBody)
        {
            touchesStatic = true;
        }
        else if (storedId < b3StoreBodyId(other))
        {
            bodyContacts += 1;
        }
    }
    free(contacts);
    return (jlong)bodyContacts | (touchesStatic ? ((jlong)1 << 32) : 0);
}

JNIEXPORT jint JNICALL JNI_METHOD(bodyContactCapacity)(JNIEnv* env, jclass type, jlong packed)
{
    (void)env;
    (void)type;
    b3BodyId id = body_id(packed);
    return b3Body_IsValid(id) ? b3Body_GetContactCapacity(id) * B3_MAX_MANIFOLD_POINTS : 0;
}

JNIEXPORT jint JNICALL JNI_METHOD(readBodyContacts)(JNIEnv* env, jclass type, jlong packed,
                                                     jlongArray otherBodyIds, jfloatArray output)
{
    (void)type;
    b3BodyId id = body_id(packed);
    if (!b3Body_IsValid(id) || otherBodyIds == NULL || output == NULL)
    {
        return 0;
    }
    jsize bodyCapacity = (*env)->GetArrayLength(env, otherBodyIds);
    jsize floatCapacity = (*env)->GetArrayLength(env, output) / 10;
    int pointCapacity = bodyCapacity < floatCapacity ? bodyCapacity : floatCapacity;
    int contactCapacity = b3Body_GetContactCapacity(id);
    if (pointCapacity <= 0 || contactCapacity <= 0)
    {
        return 0;
    }
    b3ContactData* contacts = malloc((size_t)contactCapacity * sizeof(b3ContactData));
    jlong* bodies = malloc((size_t)pointCapacity * sizeof(jlong));
    jfloat* values = malloc((size_t)pointCapacity * 10 * sizeof(jfloat));
    if (contacts == NULL || bodies == NULL || values == NULL)
    {
        free(contacts);
        free(bodies);
        free(values);
        return 0;
    }

    int contactCount = b3Body_GetContactData(id, contacts, contactCapacity);
    int outputCount = 0;
    b3Pos center = b3Body_GetWorldCenter(id);
    for (int contactIndex = 0; contactIndex < contactCount && outputCount < pointCapacity; ++contactIndex)
    {
        b3BodyId bodyA = b3Shape_GetBody(contacts[contactIndex].shapeIdA);
        bool selfIsA = B3_ID_EQUALS(bodyA, id);
        b3BodyId other = selfIsA
                ? b3Shape_GetBody(contacts[contactIndex].shapeIdB) : bodyA;
        for (int manifoldIndex = 0;
             manifoldIndex < contacts[contactIndex].manifoldCount && outputCount < pointCapacity;
             ++manifoldIndex)
        {
            const b3Manifold* manifold = contacts[contactIndex].manifolds + manifoldIndex;
            b3Vec3 normal = selfIsA ? b3Neg(manifold->normal) : manifold->normal;
            for (int pointIndex = 0;
                 pointIndex < manifold->pointCount && outputCount < pointCapacity;
                 ++pointIndex)
            {
                const b3ManifoldPoint* point = manifold->points + pointIndex;
                b3Pos worldPoint = b3OffsetPos(center, selfIsA ? point->anchorA : point->anchorB);
                int offset = outputCount * 10;
                bodies[outputCount] = (jlong)b3StoreBodyId(other);
                values[offset + 0] = (jfloat)worldPoint.x;
                values[offset + 1] = (jfloat)worldPoint.y;
                values[offset + 2] = (jfloat)worldPoint.z;
                values[offset + 3] = normal.x;
                values[offset + 4] = normal.y;
                values[offset + 5] = normal.z;
                values[offset + 6] = point->separation;
                values[offset + 7] = point->normalImpulse;
                values[offset + 8] = point->totalNormalImpulse;
                values[offset + 9] = point->normalVelocity;
                outputCount += 1;
            }
        }
    }
    (*env)->SetLongArrayRegion(env, otherBodyIds, 0, outputCount, bodies);
    (*env)->SetFloatArrayRegion(env, output, 0, outputCount * 10, values);
    free(contacts);
    free(bodies);
    free(values);
    return outputCount;
}

JNIEXPORT void JNICALL JNI_METHOD(setBodySleepThreshold)(JNIEnv* env, jclass type, jlong packed, jfloat threshold)
{
    (void)env;
    (void)type;
    b3Body_SetSleepThreshold(body_id(packed), threshold);
}

JNIEXPORT void JNICALL JNI_METHOD(setBodyBullet)(JNIEnv* env, jclass type, jlong packed, jboolean bullet)
{
    (void)env;
    (void)type;
    b3Body_SetBullet(body_id(packed), bullet == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_METHOD(setBodyFilter)(JNIEnv* env, jclass type, jlong packed,
                                                  jlong categoryBits, jlong maskBits, jint groupIndex)
{
    (void)env;
    (void)type;
    b3BodyId bodyId = body_id(packed);
    int capacity = b3Body_GetShapeCount(bodyId);
    if (capacity <= 0)
    {
        return;
    }
    b3ShapeId* shapeIds = malloc((size_t)capacity * sizeof(b3ShapeId));
    if (shapeIds == NULL)
    {
        return;
    }
    int count = b3Body_GetShapes(bodyId, shapeIds, capacity);
    for (int index = 0; index < count; ++index)
    {
        b3Filter filter = b3Shape_GetFilter(shapeIds[index]);
        filter.categoryBits = (uint64_t)categoryBits;
        filter.maskBits = (uint64_t)maskBits;
        filter.groupIndex = groupIndex;
        b3Shape_SetFilter(shapeIds[index], filter, true);
    }
    free(shapeIds);
}

JNIEXPORT jlong JNICALL JNI_METHOD(createSphericalJoint)(JNIEnv* env, jclass type, jint packedWorld,
                                                          jlong packedA, jlong packedB,
                                                          jfloat ax, jfloat ay, jfloat az,
                                                          jfloat aqx, jfloat aqy, jfloat aqz, jfloat aqw,
                                                          jfloat bx, jfloat by, jfloat bz,
                                                          jfloat bqx, jfloat bqy, jfloat bqz, jfloat bqw,
                                                          jfloat constraintHertz, jfloat dampingRatio,
                                                          jfloat coneAngle, jfloat lowerTwistAngle, jfloat upperTwistAngle,
                                                          jboolean collideConnected)
{
    (void)env;
    (void)type;
    b3SphericalJointDef def = b3DefaultSphericalJointDef();
    def.base.bodyIdA = body_id(packedA);
    def.base.bodyIdB = body_id(packedB);
    def.base.localFrameA = (b3Transform){.p = vec3(ax, ay, az), .q = quat(aqx, aqy, aqz, aqw)};
    def.base.localFrameB = (b3Transform){.p = vec3(bx, by, bz), .q = quat(bqx, bqy, bqz, bqw)};
    def.base.constraintHertz = constraintHertz;
    def.base.constraintDampingRatio = dampingRatio;
    def.base.collideConnected = collideConnected == JNI_TRUE;
    if (isfinite(coneAngle))
    {
        def.enableConeLimit = true;
        def.coneAngle = fmaxf(0.0f, fminf(coneAngle, B3_PI));
    }
    if (isfinite(lowerTwistAngle) && isfinite(upperTwistAngle))
    {
        def.enableTwistLimit = true;
        def.lowerTwistAngle = fmaxf(-0.99f * B3_PI, lowerTwistAngle);
        def.upperTwistAngle = fminf(0.99f * B3_PI, upperTwistAngle);
    }
    return (jlong)b3StoreJointId(b3CreateSphericalJoint(world_id(packedWorld), &def));
}

JNIEXPORT void JNICALL JNI_METHOD(destroyJoint)(JNIEnv* env, jclass type, jlong packed)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (b3Joint_IsValid(id))
    {
        b3DestroyJoint(id, true);
    }
}

JNIEXPORT void JNICALL JNI_METHOD(configureSphericalJointDynamics)(JNIEnv* env, jclass type,
                                                                    jlong packed, jfloat springHertz,
                                                                    jfloat springDampingRatio,
                                                                    jfloat maximumTorque)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (!b3Joint_IsValid(id))
    {
        return;
    }
    b3SphericalJoint_SetSpringHertz(id, fmaxf(0.0f, springHertz));
    b3SphericalJoint_SetSpringDampingRatio(id, fmaxf(0.0f, springDampingRatio));
    b3SphericalJoint_EnableSpring(id, springHertz > 0.0f);
    b3SphericalJoint_SetMotorVelocity(id, b3Vec3_zero);
    b3SphericalJoint_SetMaxMotorTorque(id, fmaxf(0.0f, maximumTorque));
    b3SphericalJoint_EnableMotor(id, maximumTorque > 0.0f);
}

JNIEXPORT jlong JNICALL JNI_METHOD(createFilterJoint)(JNIEnv* env, jclass type, jint packedWorld,
                                                       jlong packedA, jlong packedB)
{
    (void)env;
    (void)type;
    b3FilterJointDef def = b3DefaultFilterJointDef();
    def.base.bodyIdA = body_id(packedA);
    def.base.bodyIdB = body_id(packedB);
    return (jlong)b3StoreJointId(b3CreateFilterJoint(world_id(packedWorld), &def));
}

JNIEXPORT jlongArray JNICALL JNI_METHOD(createDrag)(JNIEnv* env, jclass type, jint packedWorld,
                                                     jlong packedBody, jfloat px, jfloat py, jfloat pz,
                                                     jfloat hertz, jfloat dampingRatio)
{
    (void)type;
    b3Pos worldPoint = position(px, py, pz);
    b3BodyDef anchorDef = b3DefaultBodyDef();
    anchorDef.type = b3_kinematicBody;
    anchorDef.position = worldPoint;
    anchorDef.enableSleep = false;
    b3BodyId anchorId = b3CreateBody(world_id(packedWorld), &anchorDef);

    b3SphericalJointDef jointDef = b3DefaultSphericalJointDef();
    jointDef.base.bodyIdA = anchorId;
    jointDef.base.bodyIdB = body_id(packedBody);
    jointDef.base.localFrameA.p = b3Vec3_zero;
    jointDef.base.localFrameB.p = b3Body_GetLocalPoint(jointDef.base.bodyIdB, worldPoint);
    jointDef.base.constraintHertz = hertz;
    jointDef.base.constraintDampingRatio = dampingRatio;
    b3JointId jointId = b3CreateSphericalJoint(world_id(packedWorld), &jointDef);

    jlong values[2] = {(jlong)b3StoreBodyId(anchorId), (jlong)b3StoreJointId(jointId)};
    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result != NULL)
    {
        (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    }
    return result;
}

JNIEXPORT void JNICALL JNI_METHOD(updateDrag)(JNIEnv* env, jclass type, jlong packedAnchor,
                                               jfloat px, jfloat py, jfloat pz, jfloat timeStep)
{
    (void)env;
    (void)type;
    b3WorldTransform target = {.p = position(px, py, pz), .q = b3Quat_identity};
    b3Body_SetTargetTransform(body_id(packedAnchor), target, timeStep, true);
}

JNIEXPORT void JNICALL JNI_METHOD(destroyDrag)(JNIEnv* env, jclass type, jlong packedAnchor, jlong packedJoint)
{
    (void)env;
    (void)type;
    b3JointId jointId = b3LoadJointId((uint64_t)packedJoint);
    if (b3Joint_IsValid(jointId)) b3DestroyJoint(jointId, true);
    b3BodyId anchorId = body_id(packedAnchor);
    if (b3Body_IsValid(anchorId)) b3DestroyBody(anchorId);
}

// ---------------------------------------------------------------------------
// Rigid joint types ported for the generic physics surface: weld, distance,
// revolute (hinge) and prismatic (slider). Constraint solving stays entirely
// inside native Box3D; the JNI layer only maps packed ids onto def structs.
// ---------------------------------------------------------------------------

static void fill_joint_base(b3JointDef* base, jint packedWorld, jlong packedA, jlong packedB,
                            jfloat ax, jfloat ay, jfloat az,
                            jfloat aqx, jfloat aqy, jfloat aqz, jfloat aqw,
                            jfloat bx, jfloat by, jfloat bz,
                            jfloat bqx, jfloat bqy, jfloat bqz, jfloat bqw,
                            jfloat constraintHertz, jfloat constraintDampingRatio,
                            jboolean collideConnected)
{
    base->bodyIdA = body_id(packedA);
    base->bodyIdB = body_id(packedB);
    base->localFrameA = (b3Transform){.p = vec3(ax, ay, az), .q = quat(aqx, aqy, aqz, aqw)};
    base->localFrameB = (b3Transform){.p = vec3(bx, by, bz), .q = quat(bqx, bqy, bqz, bqw)};
    if (isfinite(constraintHertz) && constraintHertz > 0.0f)
    {
        base->constraintHertz = constraintHertz;
    }
    if (isfinite(constraintDampingRatio) && constraintDampingRatio > 0.0f)
    {
        base->constraintDampingRatio = fminf(constraintDampingRatio, 1.0f);
    }
    base->collideConnected = collideConnected == JNI_TRUE;
}

JNIEXPORT jlong JNICALL JNI_METHOD(createWeldJoint)(JNIEnv* env, jclass type, jint packedWorld,
                                                     jlong packedA, jlong packedB,
                                                     jfloat ax, jfloat ay, jfloat az,
                                                     jfloat aqx, jfloat aqy, jfloat aqz, jfloat aqw,
                                                     jfloat bx, jfloat by, jfloat bz,
                                                     jfloat bqx, jfloat bqy, jfloat bqz, jfloat bqw,
                                                     jfloat linearHertz, jfloat angularHertz,
                                                     jfloat linearDampingRatio, jfloat angularDampingRatio,
                                                     jfloat constraintHertz, jfloat constraintDampingRatio,
                                                     jboolean collideConnected)
{
    (void)env;
    (void)type;
    b3WeldJointDef def = b3DefaultWeldJointDef();
    fill_joint_base(&def.base, packedWorld, packedA, packedB,
                    ax, ay, az, aqx, aqy, aqz, aqw, bx, by, bz, bqx, bqy, bqz, bqw,
                    constraintHertz, constraintDampingRatio, collideConnected);
    def.linearHertz = isfinite(linearHertz) ? fmaxf(0.0f, linearHertz) : 0.0f;
    def.angularHertz = isfinite(angularHertz) ? fmaxf(0.0f, angularHertz) : 0.0f;
    def.linearDampingRatio = isfinite(linearDampingRatio) ? fmaxf(0.0f, linearDampingRatio) : 1.0f;
    def.angularDampingRatio = isfinite(angularDampingRatio) ? fmaxf(0.0f, angularDampingRatio) : 1.0f;
    return (jlong)b3StoreJointId(b3CreateWeldJoint(world_id(packedWorld), &def));
}

JNIEXPORT jlong JNICALL JNI_METHOD(createDistanceJoint)(JNIEnv* env, jclass type, jint packedWorld,
                                                        jlong packedA, jlong packedB,
                                                        jfloat ax, jfloat ay, jfloat az,
                                                        jfloat aqx, jfloat aqy, jfloat aqz, jfloat aqw,
                                                        jfloat bx, jfloat by, jfloat bz,
                                                        jfloat bqx, jfloat bqy, jfloat bqz, jfloat bqw,
                                                        jfloat length,
                                                        jboolean enableSpring, jfloat hertz, jfloat dampingRatio,
                                                        jboolean enableLimit, jfloat minLength, jfloat maxLength,
                                                        jboolean enableMotor, jfloat motorSpeed, jfloat maxMotorForce,
                                                        jfloat constraintHertz, jfloat constraintDampingRatio,
                                                        jboolean collideConnected)
{
    (void)env;
    (void)type;
    b3DistanceJointDef def = b3DefaultDistanceJointDef();
    fill_joint_base(&def.base, packedWorld, packedA, packedB,
                    ax, ay, az, aqx, aqy, aqz, aqw, bx, by, bz, bqx, bqy, bqz, bqw,
                    constraintHertz, constraintDampingRatio, collideConnected);
    def.length = isfinite(length) ? fmaxf(0.0f, length) : 0.0f;
    def.enableSpring = enableSpring == JNI_TRUE;
    def.hertz = clamp_non_negative(hertz);
    def.dampingRatio = clamp_non_negative(dampingRatio);
    def.enableLimit = enableLimit == JNI_TRUE;
    def.minLength = isfinite(minLength) ? fmaxf(0.0f, minLength) : 0.0f;
    def.maxLength = isfinite(maxLength) && maxLength >= def.minLength
                        ? maxLength
                        : (isfinite(def.minLength) ? def.minLength + 1.0f : 1.0f);
    def.enableMotor = enableMotor == JNI_TRUE;
    def.motorSpeed = isfinite(motorSpeed) ? motorSpeed : 0.0f;
    def.maxMotorForce = clamp_non_negative(maxMotorForce);
    return (jlong)b3StoreJointId(b3CreateDistanceJoint(world_id(packedWorld), &def));
}

JNIEXPORT void JNICALL JNI_METHOD(setDistanceJointLength)(JNIEnv* env, jclass type, jlong packed,
                                                          jfloat length)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (b3Joint_IsValid(id) && isfinite(length)) b3DistanceJoint_SetLength(id, fmaxf(0.0f, length));
}

JNIEXPORT void JNICALL JNI_METHOD(setDistanceJointSpring)(JNIEnv* env, jclass type, jlong packed,
                                                          jboolean enable, jfloat hertz, jfloat dampingRatio)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (!b3Joint_IsValid(id)) return;
    b3DistanceJoint_EnableSpring(id, enable == JNI_TRUE);
    b3DistanceJoint_SetSpringHertz(id, clamp_non_negative(hertz));
    b3DistanceJoint_SetSpringDampingRatio(id, clamp_non_negative(dampingRatio));
}

JNIEXPORT void JNICALL JNI_METHOD(setDistanceJointLimits)(JNIEnv* env, jclass type, jlong packed,
                                                          jboolean enable, jfloat minLength, jfloat maxLength)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (!b3Joint_IsValid(id)) return;
    b3DistanceJoint_EnableLimit(id, enable == JNI_TRUE);
    if (!isfinite(minLength) || !isfinite(maxLength)) return;
    float lower = fmaxf(0.0f, minLength);
    float upper = fmaxf(lower, maxLength);
    b3DistanceJoint_SetLengthRange(id, lower, upper);
}

JNIEXPORT void JNICALL JNI_METHOD(setDistanceJointMotor)(JNIEnv* env, jclass type, jlong packed,
                                                         jboolean enable, jfloat speed, jfloat maxForce)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (!b3Joint_IsValid(id)) return;
    b3DistanceJoint_EnableMotor(id, enable == JNI_TRUE);
    b3DistanceJoint_SetMotorSpeed(id, isfinite(speed) ? speed : 0.0f);
    b3DistanceJoint_SetMaxMotorForce(id, clamp_non_negative(maxForce));
}

JNIEXPORT jlong JNICALL JNI_METHOD(createRevoluteJoint)(JNIEnv* env, jclass type, jint packedWorld,
                                                        jlong packedA, jlong packedB,
                                                        jfloat ax, jfloat ay, jfloat az,
                                                        jfloat aqx, jfloat aqy, jfloat aqz, jfloat aqw,
                                                        jfloat bx, jfloat by, jfloat bz,
                                                        jfloat bqx, jfloat bqy, jfloat bqz, jfloat bqw,
                                                        jfloat targetAngle,
                                                        jboolean enableSpring, jfloat springHertz, jfloat springDampingRatio,
                                                        jboolean enableLimit, jfloat lowerAngle, jfloat upperAngle,
                                                        jboolean enableMotor, jfloat maxMotorTorque, jfloat motorSpeed,
                                                        jfloat constraintHertz, jfloat constraintDampingRatio,
                                                        jboolean collideConnected)
{
    (void)env;
    (void)type;
    b3RevoluteJointDef def = b3DefaultRevoluteJointDef();
    fill_joint_base(&def.base, packedWorld, packedA, packedB,
                    ax, ay, az, aqx, aqy, aqz, aqw, bx, by, bz, bqx, bqy, bqz, bqw,
                    constraintHertz, constraintDampingRatio, collideConnected);
    def.targetAngle = isfinite(targetAngle) ? targetAngle : 0.0f;
    def.enableSpring = enableSpring == JNI_TRUE;
    def.hertz = clamp_non_negative(springHertz);
    def.dampingRatio = clamp_non_negative(springDampingRatio);
    def.enableLimit = enableLimit == JNI_TRUE;
    def.lowerAngle = isfinite(lowerAngle) ? fmaxf(-0.99f * B3_PI, lowerAngle) : -0.99f * B3_PI;
    def.upperAngle = isfinite(upperAngle) ? fminf(0.99f * B3_PI, upperAngle) : 0.99f * B3_PI;
    def.enableMotor = enableMotor == JNI_TRUE;
    def.maxMotorTorque = clamp_non_negative(maxMotorTorque);
    def.motorSpeed = isfinite(motorSpeed) ? motorSpeed : 0.0f;
    return (jlong)b3StoreJointId(b3CreateRevoluteJoint(world_id(packedWorld), &def));
}

JNIEXPORT void JNICALL JNI_METHOD(setRevoluteJointLimits)(JNIEnv* env, jclass type, jlong packed,
                                                          jboolean enable, jfloat lowerAngle, jfloat upperAngle)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (!b3Joint_IsValid(id)) return;
    b3RevoluteJoint_EnableLimit(id, enable == JNI_TRUE);
    if (!isfinite(lowerAngle) || !isfinite(upperAngle)) return;
    float lower = fmaxf(-0.99f * B3_PI, lowerAngle);
    float upper = fminf(fmaxf(lower, upperAngle), 0.99f * B3_PI);
    b3RevoluteJoint_SetLimits(id, lower, upper);
}

JNIEXPORT void JNICALL JNI_METHOD(setRevoluteJointMotor)(JNIEnv* env, jclass type, jlong packed,
                                                         jboolean enable, jfloat speed, jfloat maxTorque)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (!b3Joint_IsValid(id)) return;
    b3RevoluteJoint_EnableMotor(id, enable == JNI_TRUE);
    b3RevoluteJoint_SetMotorSpeed(id, isfinite(speed) ? speed : 0.0f);
    b3RevoluteJoint_SetMaxMotorTorque(id, clamp_non_negative(maxTorque));
}

JNIEXPORT void JNICALL JNI_METHOD(setRevoluteJointSpring)(JNIEnv* env, jclass type, jlong packed,
                                                          jboolean enable, jfloat hertz, jfloat dampingRatio,
                                                          jfloat targetAngle)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (!b3Joint_IsValid(id)) return;
    b3RevoluteJoint_EnableSpring(id, enable == JNI_TRUE);
    b3RevoluteJoint_SetSpringHertz(id, clamp_non_negative(hertz));
    b3RevoluteJoint_SetSpringDampingRatio(id, clamp_non_negative(dampingRatio));
    if (isfinite(targetAngle)) b3RevoluteJoint_SetTargetAngle(id, targetAngle);
}

JNIEXPORT jlong JNICALL JNI_METHOD(createPrismaticJoint)(JNIEnv* env, jclass type, jint packedWorld,
                                                         jlong packedA, jlong packedB,
                                                         jfloat ax, jfloat ay, jfloat az,
                                                         jfloat aqx, jfloat aqy, jfloat aqz, jfloat aqw,
                                                         jfloat bx, jfloat by, jfloat bz,
                                                         jfloat bqx, jfloat bqy, jfloat bqz, jfloat bqw,
                                                         jboolean enableSpring, jfloat springHertz, jfloat springDampingRatio,
                                                         jfloat targetTranslation,
                                                         jboolean enableLimit, jfloat lowerTranslation, jfloat upperTranslation,
                                                         jboolean enableMotor, jfloat maxMotorForce, jfloat motorSpeed,
                                                         jfloat constraintHertz, jfloat constraintDampingRatio,
                                                         jboolean collideConnected)
{
    (void)env;
    (void)type;
    b3PrismaticJointDef def = b3DefaultPrismaticJointDef();
    fill_joint_base(&def.base, packedWorld, packedA, packedB,
                    ax, ay, az, aqx, aqy, aqz, aqw, bx, by, bz, bqx, bqy, bqz, bqw,
                    constraintHertz, constraintDampingRatio, collideConnected);
    def.enableSpring = enableSpring == JNI_TRUE;
    def.hertz = clamp_non_negative(springHertz);
    def.dampingRatio = clamp_non_negative(springDampingRatio);
    def.targetTranslation = isfinite(targetTranslation) ? targetTranslation : 0.0f;
    def.enableLimit = enableLimit == JNI_TRUE;
    def.lowerTranslation = isfinite(lowerTranslation) ? lowerTranslation : -1.0f;
    def.upperTranslation = isfinite(upperTranslation) && upperTranslation >= def.lowerTranslation
                               ? upperTranslation
                               : def.lowerTranslation + 1.0f;
    def.enableMotor = enableMotor == JNI_TRUE;
    def.maxMotorForce = clamp_non_negative(maxMotorForce);
    def.motorSpeed = isfinite(motorSpeed) ? motorSpeed : 0.0f;
    return (jlong)b3StoreJointId(b3CreatePrismaticJoint(world_id(packedWorld), &def));
}

JNIEXPORT void JNICALL JNI_METHOD(setPrismaticJointLimits)(JNIEnv* env, jclass type, jlong packed,
                                                           jboolean enable, jfloat lowerTranslation, jfloat upperTranslation)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (!b3Joint_IsValid(id)) return;
    b3PrismaticJoint_EnableLimit(id, enable == JNI_TRUE);
    if (!isfinite(lowerTranslation) || !isfinite(upperTranslation)) return;
    float lower = lowerTranslation;
    float upper = fmaxf(lower, upperTranslation);
    b3PrismaticJoint_SetLimits(id, lower, upper);
}

JNIEXPORT void JNICALL JNI_METHOD(setPrismaticJointMotor)(JNIEnv* env, jclass type, jlong packed,
                                                          jboolean enable, jfloat speed, jfloat maxForce)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (!b3Joint_IsValid(id)) return;
    b3PrismaticJoint_EnableMotor(id, enable == JNI_TRUE);
    b3PrismaticJoint_SetMotorSpeed(id, isfinite(speed) ? speed : 0.0f);
    b3PrismaticJoint_SetMaxMotorForce(id, clamp_non_negative(maxForce));
}

JNIEXPORT void JNICALL JNI_METHOD(setPrismaticJointSpring)(JNIEnv* env, jclass type, jlong packed,
                                                           jboolean enable, jfloat hertz, jfloat dampingRatio,
                                                           jfloat targetTranslation)
{
    (void)env;
    (void)type;
    b3JointId id = b3LoadJointId((uint64_t)packed);
    if (!b3Joint_IsValid(id)) return;
    b3PrismaticJoint_EnableSpring(id, enable == JNI_TRUE);
    b3PrismaticJoint_SetSpringHertz(id, clamp_non_negative(hertz));
    b3PrismaticJoint_SetSpringDampingRatio(id, clamp_non_negative(dampingRatio));
    if (isfinite(targetTranslation)) b3PrismaticJoint_SetTargetTranslation(id, targetTranslation);
}
