package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.backend.Backend;
import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPipeLinePoseBindingTest {

    @Test
    void createInstanceInstallsTheDriverThroughModelPoseBinding() {
        TestBridge bridge = new TestBridge();
        TestBackend backend = new TestBackend();
        ModelPipeLine<String, String> pipeline =
                new ModelPipeLine.Builder<Object, Object, String, String, Object>()
                        .withBridge("bridge", bridge)
                        .withBackend("backend", backend)
                        .buildForPublishedModels();
        Model model = ModelInstanceFixture.minimal().getModel();
        try (ModelPublicationBatch<String> publication = new ModelPublicationBatch<>()) {
            publication.put(pipeline, Map.of("model", model));
            PoseDriver source = new PoseDriver() {};

            ModelInstance instance = pipeline.createInstance(
                    "model", "instance", null, null, null, source);

            assertNotNull(instance);
            assertTrue(instance.getPoseDriver() instanceof ModelPoseBinding);
            ModelPoseBinding binding = (ModelPoseBinding) instance.getPoseDriver();
            assertSame(source, binding.source());
        }
    }

    private static final class TestBridge implements Bridge<Object> {
        @Override
        public HashMap<Vertex, Vertex> transformVertices(Model model, SkeletonInstance skeleton,
                                                         Vertex[] vertices) {
            return new HashMap<>();
        }

        @Override
        public Mesh[] transformMeshes(Model model, SkeletonInstance skeleton, Mesh[] meshes) {
            return meshes;
        }

        @Override
        public Object getBackendRenderable(ModelInstance modelInstance,
                                           HashMap<Vertex, Vertex> vertices, Mesh[] meshes) {
            return new Object();
        }

        @Override
        public BoneBindingFunc getBoneBindingFunc(Model model, SkeletonInstance skeleton,
                                                  Vertex vertex) {
            return vertex.getBinding().getFunc();
        }

        @Override
        public BackendContext<?, Object, ?, ?> getBackendContext(ModelInstance instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setBackends(Map<String, Backend<?, Object, ?, ?>> backends) {
        }

        @Override
        public Map<String, Backend<?, Object, ?, ?>> getBackends() {
            return Map.of();
        }
    }

    private static final class TestBackend extends Backend<TestBridge, Object, Void, Void> {
        @Override
        public void render(BackendContext<TestBridge, Object, Void, Void> renderable,
                           Void renderContext) {
        }
    }
}
