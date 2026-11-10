package lib.kasuga.rendering.models.uml.dynamic.physics.box3d;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-level JNI access to the vendored Box3D C17 engine.
 *
 * <p>The API deliberately uses packed Box3D identifiers rather than native
 * pointers. Box3D validates their generation and owning world, preventing a
 * stale Java handle from aliasing a newly-created native object.</p>
 */
public final class NativeBox3D {
    public static final int STATIC_BODY = 0;
    public static final int KINEMATIC_BODY = 1;
    public static final int DYNAMIC_BODY = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger(NativeBox3D.class);
    private static final String LIBRARY_BASE_NAME = "kasuga_box3d";
    private static final AtomicBoolean UNAVAILABLE_WARNING_LOGGED = new AtomicBoolean();
    private static final Throwable LOAD_FAILURE;

    static {
        Throwable failure = null;
        try {
            loadLibrary();
        } catch (Throwable throwable) {
            failure = throwable;
        }
        LOAD_FAILURE = failure;
    }

    private NativeBox3D() {}

    public static boolean available() {
        return LOAD_FAILURE == null;
    }

    /**
     * Returns whether native physics can be used and logs its disabled state at
     * most once. High-level optional physics entry points should prefer this to
     * {@link #requireAvailable()} so the rest of the modelling runtime can keep
     * working without a native library.
     */
    public static boolean availableOrWarn() {
        if (LOAD_FAILURE == null) return true;
        if (UNAVAILABLE_WARNING_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("Box3D native library is unavailable; physics features are disabled: {}",
                    LOAD_FAILURE.toString());
        }
        return false;
    }

    public static void requireAvailable() {
        if (LOAD_FAILURE != null) {
            throw new IllegalStateException("Box3D native library is unavailable", LOAD_FAILURE);
        }
    }

    public static Throwable loadFailure() {
        return LOAD_FAILURE;
    }

    private static void loadLibrary() throws IOException {
        String explicit = System.getProperty("kasuga.box3d.library");
        if (explicit != null && !explicit.isBlank()) {
            System.load(Path.of(explicit).toAbsolutePath().normalize().toString());
            return;
        }

        String mappedName = System.mapLibraryName(LIBRARY_BASE_NAME);
        String resource = "/native/" + platformClassifier() + "/" + mappedName;
        try (InputStream input = NativeBox3D.class.getResourceAsStream(resource)) {
            if (input == null) {
                System.loadLibrary(LIBRARY_BASE_NAME);
                return;
            }
            Path extracted = Files.createTempFile("kasuga-box3d-", suffix(mappedName));
            extracted.toFile().deleteOnExit();
            Files.copy(input, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.load(extracted.toAbsolutePath().toString());
        }
    }

    static String platformClassifier() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String os = osName.contains("win") ? "windows"
                : osName.contains("mac") || osName.contains("darwin") ? "macos"
                : osName.contains("linux") ? "linux" : sanitize(osName);
        String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = switch (archName) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> sanitize(archName);
        };
        return os + "-" + arch;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-z0-9_-]", "_");
    }

    private static String suffix(String mappedName) {
        int dot = mappedName.lastIndexOf('.');
        return dot < 0 ? ".bin" : mappedName.substring(dot);
    }

    public static native int createWorld(float gravityX, float gravityY, float gravityZ,
                                         boolean sleeping, boolean continuous);
    public static native void destroyWorld(int worldId);
    public static native void step(int worldId, float timeStep, int subStepCount);
    public static native void setGravity(int worldId, float x, float y, float z);
    public static native void setSleepingEnabled(int worldId, boolean enabled);
    public static native void setContinuousEnabled(int worldId, boolean enabled);
    public static native void setRestitutionThreshold(int worldId, float threshold);
    public static native void setMaximumLinearSpeed(int worldId, float speed);
    public static native int contactCount(int worldId);
    /** Returns the hit body id and writes point xyz, normal xyz and distance. */
    public static native long raycast(int worldId, float originX, float originY, float originZ,
                                      float directionX, float directionY, float directionZ,
                                      float maximumDistance, float[] hit);

    public static native long createBody(int worldId, int bodyType,
                                         float positionX, float positionY, float positionZ,
                                         float rotationX, float rotationY, float rotationZ, float rotationW,
                                         float velocityX, float velocityY, float velocityZ,
                                         float angularX, float angularY, float angularZ,
                                         float linearDamping, float angularDamping,
                                         boolean bullet);
    public static native long addSphereShape(long bodyId,
                                             float centerX, float centerY, float centerZ, float radius,
                                             float density, float friction, float restitution, float rollingResistance,
                                             long categoryBits, long maskBits, int groupIndex);
    public static native long addBoxShape(long bodyId,
                                          float centerX, float centerY, float centerZ,
                                          float rotationX, float rotationY, float rotationZ, float rotationW,
                                          float halfX, float halfY, float halfZ,
                                          float density, float friction, float restitution, float rollingResistance,
                                          long categoryBits, long maskBits, int groupIndex);
    public static native long addCapsuleShape(long bodyId,
                                              float centerAx, float centerAy, float centerAz,
                                              float centerBx, float centerBy, float centerBz, float radius,
                                              float density, float friction, float restitution, float rollingResistance,
                                              long categoryBits, long maskBits, int groupIndex);
    /**
     * Creates one static body backed by a retained Box3D triangle mesh.
     * Returns {@code [bodyId, meshPointer]}; both values must be passed to
     * {@link #destroyStaticMesh(long, long)} in that order.
     */
    public static native long[] createStaticMesh(int worldId, float[] vertices, int[] indices,
                                                  float friction, float restitution);
    public static native void destroyStaticMesh(long bodyId, long meshPointer);
    /** Recomputes inertia from every shape and scales it to the requested total mass. */
    public static native void finalizeBodyMass(long bodyId, float mass);
    public static native void destroyBody(long bodyId);
    public static native void setBodyTransform(long bodyId, float positionX, float positionY, float positionZ,
                                               float rotationX, float rotationY, float rotationZ, float rotationW);
    public static native void setBodyTarget(long bodyId, float positionX, float positionY, float positionZ,
                                            float rotationX, float rotationY, float rotationZ, float rotationW,
                                            float timeStep);
    public static native void setBodyVelocity(long bodyId, float velocityX, float velocityY, float velocityZ,
                                              float angularX, float angularY, float angularZ);
    /** Writes position xyz, rotation xyzw, linear velocity xyz and angular velocity xyz. */
    public static native void readBodyState(long bodyId, float[] state);
    public static native void applyImpulse(long bodyId, float impulseX, float impulseY, float impulseZ,
                                           float pointX, float pointY, float pointZ);
    public static native void applyCenterImpulse(long bodyId, float impulseX, float impulseY, float impulseZ);
    public static native void applyAngularImpulse(long bodyId, float impulseX, float impulseY, float impulseZ);
    public static native void applyForce(long bodyId, float forceX, float forceY, float forceZ);
    public static native void applyForceAtPoint(long bodyId, float forceX, float forceY, float forceZ,
                                                float pointX, float pointY, float pointZ);
    public static native void applyTorque(long bodyId, float torqueX, float torqueY, float torqueZ);
    public static native void setBodyGravityScale(long bodyId, float scale);
    public static native float bodyGravityScale(long bodyId);
    public static native void setBodyAwake(long bodyId, boolean awake);
    public static native void setBodyEnabled(long bodyId, boolean enabled);
    public static native boolean bodyEnabled(long bodyId);
    public static native void wakeBody(long bodyId);
    public static native boolean bodyAwake(long bodyId);
    /** Low 32 bits: unique non-static contacts; bit 32: this body touches static geometry. */
    public static native long bodyContactSummary(long bodyId);
    /** Upper bound on contact points that can be returned by {@link #readBodyContacts}. */
    public static native int bodyContactCapacity(long bodyId);
    /**
     * Writes one other-body id and ten floats per point: point xyz, normal xyz,
     * separation, final normal impulse, total normal impulse and pre-solve
     * normal velocity. The normal points from the other body toward bodyId.
     */
    public static native int readBodyContacts(long bodyId, long[] otherBodyIds, float[] contacts);
    public static native void setBodySleepThreshold(long bodyId, float threshold);
    public static native void setBodyBullet(long bodyId, boolean bullet);
    public static native void setBodyFilter(long bodyId, long categoryBits, long maskBits, int groupIndex);

    public static native long createSphericalJoint(int worldId, long bodyA, long bodyB,
                                                   float localAx, float localAy, float localAz,
                                                   float localAqx, float localAqy, float localAqz, float localAqw,
                                                   float localBx, float localBy, float localBz,
                                                   float localBqx, float localBqy, float localBqz, float localBqw,
                                                   float constraintHertz, float dampingRatio,
                                                   float coneAngle, float lowerTwistAngle, float upperTwistAngle,
                                                   boolean collideConnected);
    public static native void configureSphericalJointDynamics(long jointId,
                                                               float springHertz,
                                                               float springDampingRatio,
                                                               float maximumMotorTorque);
    public static native void destroyJoint(long jointId);
    public static native long createFilterJoint(int worldId, long bodyA, long bodyB);

    // ------------------------------------------------------------------
    // Rigid/limited/motored joint types: weld, distance, revolute, prismatic
    // ------------------------------------------------------------------

    /** Weld: rigidly locks two bodies with configurable softness (0 hertz = rigid). */
    public static native long createWeldJoint(int worldId, long bodyA, long bodyB,
                                              float localAx, float localAy, float localAz,
                                              float localAqx, float localAqy, float localAqz, float localAqw,
                                              float localBx, float localBy, float localBz,
                                              float localBqx, float localBqy, float localBqz, float localBqw,
                                              float linearHertz, float angularHertz,
                                              float linearDampingRatio, float angularDampingRatio,
                                              float constraintHertz, float constraintDampingRatio,
                                              boolean collideConnected);

    /** Rope/spring between two anchor points with optional spring, length limits and motor. */
    public static native long createDistanceJoint(int worldId, long bodyA, long bodyB,
                                                  float localAx, float localAy, float localAz,
                                                  float localAqx, float localAqy, float localAqz, float localAqw,
                                                  float localBx, float localBy, float localBz,
                                                  float localBqx, float localBqy, float localBqz, float localBqw,
                                                  float length,
                                                  boolean enableSpring, float hertz, float dampingRatio,
                                                  boolean enableLimit, float minLength, float maxLength,
                                                  boolean enableMotor, float motorSpeed, float maxMotorForce,
                                                  float constraintHertz, float constraintDampingRatio,
                                                  boolean collideConnected);
    public static native void setDistanceJointLength(long jointId, float length);
    public static native void setDistanceJointSpring(long jointId, boolean enable, float hertz, float dampingRatio);
    public static native void setDistanceJointLimits(long jointId, boolean enable, float minLength, float maxLength);
    public static native void setDistanceJointMotor(long jointId, boolean enable, float speed, float maxForce);

    /** Hinge around the shared frame X axis with optional spring, angle limits and motor. */
    public static native long createRevoluteJoint(int worldId, long bodyA, long bodyB,
                                                  float localAx, float localAy, float localAz,
                                                  float localAqx, float localAqy, float localAqz, float localAqw,
                                                  float localBx, float localBy, float localBz,
                                                  float localBqx, float localBqy, float localBqz, float localBqw,
                                                  float targetAngle,
                                                  boolean enableSpring, float springHertz, float springDampingRatio,
                                                  boolean enableLimit, float lowerAngle, float upperAngle,
                                                  boolean enableMotor, float maxMotorTorque, float motorSpeed,
                                                  float constraintHertz, float constraintDampingRatio,
                                                  boolean collideConnected);
    public static native void setRevoluteJointLimits(long jointId, boolean enable, float lowerAngle, float upperAngle);
    public static native void setRevoluteJointMotor(long jointId, boolean enable, float speed, float maxTorque);
    public static native void setRevoluteJointSpring(long jointId, boolean enable, float hertz,
                                                     float dampingRatio, float targetAngle);

    /** Slider along the shared frame X axis with optional spring, translation limits and motor. */
    public static native long createPrismaticJoint(int worldId, long bodyA, long bodyB,
                                                   float localAx, float localAy, float localAz,
                                                   float localAqx, float localAqy, float localAqz, float localAqw,
                                                   float localBx, float localBy, float localBz,
                                                   float localBqx, float localBqy, float localBqz, float localBqw,
                                                   boolean enableSpring, float springHertz, float springDampingRatio,
                                                   float targetTranslation,
                                                   boolean enableLimit, float lowerTranslation, float upperTranslation,
                                                   boolean enableMotor, float maxMotorForce, float motorSpeed,
                                                   float constraintHertz, float constraintDampingRatio,
                                                   boolean collideConnected);
    public static native void setPrismaticJointLimits(long jointId, boolean enable, float lowerTranslation, float upperTranslation);
    public static native void setPrismaticJointMotor(long jointId, boolean enable, float speed, float maxForce);
    public static native void setPrismaticJointSpring(long jointId, boolean enable, float hertz,
                                                      float dampingRatio, float targetTranslation);

    /** Returns [kinematic anchor body id, spherical joint id]. */
    public static native long[] createDrag(int worldId, long bodyId,
                                           float pointX, float pointY, float pointZ,
                                           float hertz, float dampingRatio);
    public static native void updateDrag(long anchorBodyId,
                                         float pointX, float pointY, float pointZ, float timeStep);
    public static native void destroyDrag(long anchorBodyId, long jointId);
}
