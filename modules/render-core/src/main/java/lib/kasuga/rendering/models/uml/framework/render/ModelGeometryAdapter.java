package lib.kasuga.rendering.models.uml.framework.render;

import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;

import java.util.HashMap;

/**
 * Model-to-renderer geometry adaptation, without scheduling or drawing.
 * An empty vertex map means unchanged geometry; absent keys retain the source
 * vertex. Returned meshes may borrow immutable model geometry. GPU-skinning
 * adapters may leave deformation to the render resource instead of transforming
 * vertices here. Bone binding selection must follow the model's binding data.
 */
public interface ModelGeometryAdapter {
    HashMap<Vertex, Vertex> transformVertices(Model model, SkeletonInstance skeleton, Vertex[] vertices);
    Mesh[] transformMeshes(Model model, SkeletonInstance skeleton, Mesh[] meshes);
    BoneBindingFunc getBoneBindingFunc(Model model, SkeletonInstance skeleton, Vertex vertex);
}
