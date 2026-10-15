package lib.kasuga.rendering.models.mc.util;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class RayTracingHelper {

    /**
     * Performs ray-plane intersection from a vector to the GUI.
     * @param rayOrigin The origin of the ray in world space.
     * @param rayDirection The direction of the ray in world space (should be normalized).
     * @param transform The transformation matrix of the plane (the plane is defined as the XY plane in local space).
     * @param normal The normal matrix for transforming the plane normal.
     * @param dstHitPoint The output parameter to store the hit point in world space if an intersection occurs.
     * @param ignoreBackface Whether to ignore intersections that occur on the back face of the plane.
     * @return absolute distance between the ray origin and hit point if an intersection occurs, -1 otherwise.
     */
    public static float intersectRayWithPlane(
            Vector3f rayOrigin, Vector3f rayDirection,
            Matrix4f transform, Matrix3f normal,
            Vector3f dstHitPoint, boolean ignoreBackface) {
        Vector3f planeOrigin = new Vector3f(transform.m30(), transform.m31(), transform.m32());
        Vector3f planeNormal = new Vector3f(0, 0, 1);
        normal.transform(planeNormal);

        float denom = planeNormal.dot(rayDirection);
        if (Math.abs(denom) < 1e-6) {
            return -1f; // Ray is parallel to the plane
        }

        Vector3f diff = new Vector3f(planeOrigin).sub(rayOrigin);
        float t = diff.dot(planeNormal) / denom;

        if (t < 0 && ignoreBackface) {
            return -1f; // Intersection is behind the ray origin
        }

        dstHitPoint.set(rayOrigin).fma(t, rayDirection);
        return Math.abs(t);
    }

    public static void getRayFromCamera(Vector3f dstOrigin, Vector3f dstDirection) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Vector3f lookVec = camera.getLookVector();

        dstOrigin.set((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
        dstDirection.set(lookVec.x, lookVec.y, lookVec.z);
    }

    public static void getRayFromEntity(Entity entity, Vector3f dstOrigin, Vector3f dstDirection) {
        Vec3 eyePos = entity.getEyePosition();
        Vec3 lookVec = entity.getLookAngle();

        dstOrigin.set((float) eyePos.x, (float) eyePos.y, (float) eyePos.z);
        dstDirection.set(lookVec.x, lookVec.y, lookVec.z).normalize();
    }
}
