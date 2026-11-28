package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts the baked geometry emitted by Minecraft's Java entity model parts. */
public final class JavaEntityModelLoader {
    private static final String ROOT = "root";

    private JavaEntityModelLoader() {}

    public static Model bake(ModelPart root, MaterialSet materials, Material material) {
        Map<String, RigPart> parts = new LinkedHashMap<>();
        Bone rootBone = collectPart(root, "", parts).bone();
        List<Vertex> vertices = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        QuadCollector collector = new QuadCollector(material, vertices, meshes);

        root.visit(entitySpace(), (pose, path, index, cube) -> {
            RigPart part = collectPart(root, path, parts);
            if (part.visible() && !part.part().skipDraw) {
                collector.bind(part.bone());
                cube.compile(pose, collector, 0, 0, -1);
            }
        });

        Map<Bone, List<Bone>> children = new LinkedHashMap<>();
        Bone[] bones = new Bone[parts.size()];
        int index = 0;
        for (RigPart part : parts.values()) {
            Bone bone = part.bone();
            bones[index++] = bone;
            if (bone.getParent() != null) {
                children.computeIfAbsent(bone.getParent(), key -> new ArrayList<>()).add(bone);
            }
        }
        children.forEach((bone, descendants) -> bone.setChildren(descendants.toArray(Bone[]::new)));
        Skeleton skeleton = new Skeleton(bones, rootBone, new Anchor[0], null, new Transform());
        return new Model(vertices.toArray(Vertex[]::new), meshes.toArray(Mesh[]::new),
                bones, skeleton, materials, MeshMode.QUADS, new JavaRig(), null);
    }

    private static RigPart collectPart(ModelPart root, String path, Map<String, RigPart> parts) {
        RigPart cached = parts.get(path);
        if (cached != null) return cached;

        RigPart parent = null;
        ModelPart part = root;
        if (!path.isEmpty()) {
            int separator = path.lastIndexOf('/');
            parent = collectPart(root, path.substring(0, separator), parts);
            part = parent.part().getChild(path.substring(separator + 1));
        }
        Transform transform = localTransform(part, parent == null);
        float determinant = transform.transform().determinant3x3();
        if (!Float.isFinite(determinant) || determinant == 0.0f) {
            throw new IllegalArgumentException("Non-invertible bind pose for " + ROOT + path);
        }
        Bone bone = new Bone(ROOT + path, transform, null);
        if (parent != null) bone.setParent(parent.bone());
        RigPart result = new RigPart(part, bone, part.visible && (parent == null || parent.visible()));
        parts.put(path, result);
        return result;
    }

    /** Copies the transforms from a matching part tree after the caller runs its Java animation. */
    public static void applyPose(ModelInstance instance, ModelPart posedRoot) {
        if (!(instance.getModel().getModelData() instanceof JavaRig)) {
            throw new IllegalArgumentException("Instance was not loaded from a Java entity model");
        }
        Bone root = instance.getModel().getSkeleton().getRoot();
        applyPartPose(instance, root, posedRoot);
    }

    private static void applyPartPose(ModelInstance instance, Bone bone, ModelPart part) {
        Transform pose = localTransform(part, bone.getParent() == null);
        Transform delta = bone.getTransform().copy().invert().mul(pose);
        instance.getSkeletonInstance().transform(bone.getName(), delta);
        if (bone.getChildren() == null) return;
        for (Bone child : bone.getChildren()) {
            String name = child.getName().substring(bone.getName().length() + 1);
            applyPartPose(instance, child, part.getChild(name));
        }
    }

    private static Transform localTransform(ModelPart part, boolean root) {
        PoseStack stack = root ? entitySpace() : new PoseStack();
        part.translateAndRotate(stack);
        if (!stack.last().pose().isFinite()) {
            throw new IllegalArgumentException("Non-finite Java model part transform");
        }
        return new Transform().set(stack.last().pose());
    }

    private static PoseStack entitySpace() {
        PoseStack stack = new PoseStack();
        // Match the living-entity renderer's model axes and put its foot plane at y = 0.
        stack.translate(0, 1.5f, 0);
        stack.scale(-1, -1, 1);
        return stack;
    }

    private record RigPart(ModelPart part, Bone bone, boolean visible) {}

    private record JavaRig() implements ModelData {
        @Override
        public boolean isMeshTriangles() {
            return false;
        }
    }

    private static final class QuadCollector implements VertexConsumer {
        private final Material[] materials;
        private final List<Vertex> vertices;
        private final List<Mesh> meshes;
        private final Vector3f[] positions = new Vector3f[4];
        private final Vector2f[] uvs = new Vector2f[4];
        private BoneBinding binding;
        private int count;

        QuadCollector(Material material, List<Vertex> vertices, List<Mesh> meshes) {
            this.materials = new Material[]{material};
            this.vertices = vertices;
            this.meshes = meshes;
        }

        @SuppressWarnings("unchecked")
        void bind(Bone bone) {
            binding = new BoneBinding(new Pair[]{Pair.of(bone, 1f)}, BoneBindingFunc.BDEF, null);
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v,
                              int overlay, int light, float nx, float ny, float nz) {
            positions[count] = new Vector3f(x, y, z);
            uvs[count++] = new Vector2f(u, v);
            if (count != 4) return;

            Vertex[] corners = new Vertex[4];
            Mesh mesh = new Mesh(corners, new Vector3f(nx, ny, nz), new Transform(), materials, null);
            for (int index = 0; index < corners.length; index++) {
                Vertex vertex = new Vertex(positions[index], null);
                vertex.addUV(mesh, materials[0], uvs[index]);
                vertex.setBinding(binding);
                corners[index] = vertex;
                vertices.add(vertex);
            }
            meshes.add(mesh);
            count = 0;
        }

        // ModelPart.Cube emits complete vertices through the overload above.
        @Override public VertexConsumer addVertex(float x, float y, float z) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setUv(float u, float v) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setUv1(int u, int v) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setUv2(int u, int v) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { throw new UnsupportedOperationException(); }
    }
}
