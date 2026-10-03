package lib.kasuga.rendering.models.uml.bridge;

import lib.kasuga.rendering.models.uml.backend.Backend;
import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;
import lib.kasuga.rendering.models.uml.framework.render.ModelGeometryAdapter;
import lib.kasuga.rendering.models.uml.framework.render.RenderableFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Geometry adapter plus legacy resource factory. Backends should create their own
 * typed contexts and supply backend services explicitly to the resource factory.
 * The context/registry methods remain for existing pipeline integrations; they
 * are not the ownership boundary for GPU resources or frame scheduling.
 */
public interface Bridge<R> extends ModelGeometryAdapter, RenderableFactory<R> {
    /** Builds a resource from adapted geometry; ownership passes to its context. */
    R getBackendRenderable(ModelInstance modelInstance,
                           HashMap<Vertex, Vertex> vertices, Mesh[] meshes);

    /** Legacy context factory; each call must return a fresh, unmounted context. */
    BackendContext<?, R, ?, ?> getBackendContext(ModelInstance modelInstance);

    /** Legacy service lookup, initialized by ModelPipeLine.Builder. */
    void setBackends(Map<String, Backend<?, R, ?, ?>> backends);

    Map<String, Backend<?, R, ?, ?>> getBackends();

    @Override
    default R createRenderable(ModelInstance instance) {
        return apply(instance);
    }

    default R apply(ModelInstance modelInstance) {
        Model model = modelInstance.getModel();
        SkeletonInstance skeleton = modelInstance.getSkeletonInstance();
        Vertex[] vertices = model.getVertices();
        Mesh[] meshes = model.getMeshes();
        long transformVerticesStart = ModelProfiler.start();
        HashMap<Vertex, Vertex> vertexMap = transformVertices(model, skeleton, vertices);
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("bridge.transformVertices", transformVerticesStart,
                    "vertices=" + vertices.length);
        }
        long transformMeshesStart = ModelProfiler.start();
        Mesh[] transformedMeshes = transformMeshes(model, skeleton, meshes);
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("bridge.transformMeshes", transformMeshesStart,
                    "meshes=" + meshes.length);
        }
        long backendRenderableStart = ModelProfiler.start();
        R renderable = getBackendRenderable(modelInstance, vertexMap, transformedMeshes);
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("bridge.getBackendRenderable", backendRenderableStart,
                    "meshes=" + transformedMeshes.length);
        }
        return renderable;
    }
}
