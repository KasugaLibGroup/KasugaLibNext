package lib.kasuga.rendering.effect.particle.instance;

import lib.kasuga.shader.Mat4Expr;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.Vec3Expr;
import lib.kasuga.shader.Vec4Expr;

/** Backend-translatable Shader IR programs matching the standard packed particle instance layout. */
public final class ParticleInstanceShaderPrograms {
    private ParticleInstanceShaderPrograms() {
    }

    /**
     * Position-only base mesh transformed by four instance-matrix columns and tinted per instance.
     */
    public static ShaderProgram colored(String id) {
        return ShaderProgram.graphics(
                id,
                vertex -> {
                    Vec3Expr position = vertex.inputVec3("Position");
                    Vec4Expr model0 = vertex.inputVec4("InstanceModel0");
                    Vec4Expr model1 = vertex.inputVec4("InstanceModel1");
                    Vec4Expr model2 = vertex.inputVec4("InstanceModel2");
                    Vec4Expr model3 = vertex.inputVec4("InstanceModel3");
                    Vec4Expr color = vertex.inputVec4("InstanceColor");
                    Mat4Expr model = vertex.mat4(
                            model0.x(), model0.y(), model0.z(), model0.w(),
                            model1.x(), model1.y(), model1.z(), model1.w(),
                            model2.x(), model2.y(), model2.z(), model2.w(),
                            model3.x(), model3.y(), model3.z(), model3.w()
                    );
                    Vec4Expr world = model.transform(vertex.vec4(position, vertex.f32(1)));
                    Vec3Expr cameraOffset = vertex.uniformVec3("CameraOffset", 0, 0, 0);
                    Vec3Expr relative = vertex.vec3(world.x(), world.y(), world.z())
                            .sub(cameraOffset);
                    Mat4Expr modelView = vertex.uniformMat4("ModelViewMat");
                    Mat4Expr projection = vertex.uniformMat4("ProjMat");
                    vertex.outputVec4("particleColor", color);
                    vertex.position(projection.transform(modelView.transform(
                            vertex.vec4(relative, vertex.f32(1))
                    )));
                },
                fragment -> fragment.fragmentColor(fragment.inputVec4("particleColor"))
        );
    }

    /**
     * Ray-marched procedural density inside each instanced cube.
     *
     * <p>The fragment stage integrates a soft, turbulent 3D density field from the visible cube
     * surface to the far side of the volume. This produces a spatial smoke volume rather than a
     * camera-facing particle surface.</p>
     */
    public static ShaderProgram volumetricSmoke(String id) {
        return ShaderProgram.graphics(
                id,
                vertex -> {
                    Vec3Expr position = vertex.inputVec3("Position");
                    Vec4Expr model0 = vertex.inputVec4("InstanceModel0");
                    Vec4Expr model1 = vertex.inputVec4("InstanceModel1");
                    Vec4Expr model2 = vertex.inputVec4("InstanceModel2");
                    Vec4Expr model3 = vertex.inputVec4("InstanceModel3");
                    Vec4Expr color = vertex.inputVec4("InstanceColor");
                    Mat4Expr model = vertex.mat4(
                            model0.x(), model0.y(), model0.z(), model0.w(),
                            model1.x(), model1.y(), model1.z(), model1.w(),
                            model2.x(), model2.y(), model2.z(), model2.w(),
                            model3.x(), model3.y(), model3.z(), model3.w()
                    );
                    Vec4Expr world = model.transform(vertex.vec4(position, vertex.f32(1)));
                    Vec3Expr center = vertex.vec3(model3.x(), model3.y(), model3.z());
                    Vec3Expr cameraOffset = vertex.uniformVec3("CameraOffset", 0, 0, 0);
                    Vec3Expr relative = vertex.vec3(world.x(), world.y(), world.z())
                            .sub(cameraOffset);
                    Mat4Expr modelView = vertex.uniformMat4("ModelViewMat");
                    Mat4Expr projection = vertex.uniformMat4("ProjMat");
                    vertex.outputVec3("smokeLocalPosition", position);
                    vertex.outputVec3(
                            "smokeCameraPosition",
                            cameraOffset.sub(center).div(
                                    vertex.vec3(model0.x(), model0.y(), model0.z()).length()
                            )
                    );
                    vertex.outputVec4("particleColor", color);
                    vertex.outputFloat(
                            "smokeSeed",
                            center.dot(vertex.vec3(0.7548777f, 0.5698403f, 0.4382891f))
                    );
                    vertex.position(projection.transform(modelView.transform(
                            vertex.vec4(relative, vertex.f32(1))
                    )));
                },
                fragment -> {
                    Vec3Expr localPosition = fragment.inputVec3("smokeLocalPosition");
                    Vec3Expr cameraPosition = fragment.inputVec3("smokeCameraPosition");
                    Vec4Expr color = fragment.inputVec4("particleColor");
                    var seed = fragment.inputFloat("smokeSeed");
                    Vec3Expr ray = fragment.letVec3(
                            "smokeRay", localPosition.sub(cameraPosition).normalize()
                    );
                    Vec3Expr inverseRay = fragment.letVec3(
                            "smokeInverseRay",
                            fragment.vec3(1, 1, 1).div(
                                    ray.add(fragment.vec3(0.00001f, 0.00001f, 0.00001f))
                            )
                    );
                    Vec3Expr farPlanes = fragment.letVec3(
                            "smokeFarPlanes",
                            fragment.vec3(-0.5f, -0.5f, -0.5f)
                                    .sub(localPosition).mul(inverseRay).max(
                                    fragment.vec3(0.5f, 0.5f, 0.5f)
                                            .sub(localPosition).mul(inverseRay)
                            )
                    );
                    var distance = fragment.letFloat(
                            "smokeDistance",
                            farPlanes.x().min(farPlanes.y().min(farPlanes.z())).max(0)
                    );
                    var stepLength = fragment.letFloat(
                            "smokeStepLength", distance.div(12)
                    );
                    var density = fragment.localFloat("smokeDensity", fragment.f32(0));
                    fragment.forRange("smokeStep", fragment.i32(0), fragment.i32(12), step -> {
                        Vec3Expr point = fragment.letVec3(
                                "smokePoint",
                                localPosition.add(ray.mul(
                                        step.toFloat().add(0.5f).mul(stepLength)
                                ))
                        );
                        Vec3Expr shape = fragment.letVec3(
                                "smokeShape", point.mul(fragment.vec3(1, 0.82f, 1))
                        );
                        var envelope = fragment.letFloat(
                                "smokeEnvelope",
                                fragment.f32(1).sub(shape.dot(shape).mul(4)).max(0)
                        );
                        var detail = fragment.letFloat(
                                "smokeDetail",
                                point.dot(fragment.vec3(17, 23, 19)).add(seed)
                                        .sin().mul(0.22f).add(0.78f)
                        );
                        density.set(density.get().add(
                                envelope.mul(envelope).mul(detail).mul(stepLength)
                        ));
                    });
                    var alpha = fragment.letFloat(
                            "smokeAlpha",
                            fragment.f32(1).sub(
                                    density.get().mul(color.a()).mul(-3.2f).exp()
                            )
                    );
                    fragment.fragmentColor(fragment.vec4(color.rgb(), alpha));
                }
        );
    }
}
