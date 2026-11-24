package lib.kasuga.rendering.models.uml.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.structure.Pair;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** One rigid collision box per bone with geometry, shared by every instance of the model. */
public final class BoneCollisionBounds {
    private static final float MINIMUM_HALF_THICKNESS = 0.00005f;

    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        private boolean matches(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            return Double.compare(this.minX, minX) == 0 && Double.compare(this.minY, minY) == 0
                    && Double.compare(this.minZ, minZ) == 0 && Double.compare(this.maxX, maxX) == 0
                    && Double.compare(this.maxY, maxY) == 0 && Double.compare(this.maxZ, maxZ) == 0;
        }
    }

    public record Entry(Bone bone, Box localBounds) {}

    private final Skeleton skeleton;
    private final List<Entry> entries;

    private BoneCollisionBounds(Skeleton skeleton, List<Entry> entries) {
        this.skeleton = skeleton;
        this.entries = List.copyOf(entries);
    }

    /** Every positive skin weight associates that vertex with its bone. Rigid unweighted geometry follows the root. */
    public static BoneCollisionBounds from(Model model) {
        Skeleton skeleton = model.getSkeleton();
        Vector3f local = new Vector3f();
        List<Entry> entries = new ArrayList<>();
        for (Bone bone : skeleton.getBones()) {
            Matrix4fc inverseBind = skeleton.getBindingInverse(bone).transform();
            Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
            Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);
            for (Vertex vertex : model.getVertexByBone(bone)) {
                if (hasWeight(vertex, bone)) include(vertex, inverseBind, local, minimum, maximum);
            }
            if (bone == skeleton.getRoot()) {
                for (Vertex vertex : model.getVertices()) {
                    if (vertex.getBinding().getWeights().length == 0) {
                        include(vertex, inverseBind, local, minimum, maximum);
                    }
                }
            }
            if (minimum.isFinite() && maximum.isFinite()) entries.add(new Entry(bone, box(minimum, maximum)));
        }
        return new BoneCollisionBounds(skeleton, entries);
    }

    private static boolean hasWeight(Vertex vertex, Bone bone) {
        for (Pair<Bone, Float> weight : vertex.getBinding().getWeights()) {
            if (weight.getFirst() == bone && weight.getSecond() > 0) return true;
        }
        return false;
    }

    private static void include(Vertex vertex, Matrix4fc inverseBind, Vector3f local,
                                Vector3f minimum, Vector3f maximum) {
        inverseBind.transformPosition(vertex.getPosition(), local);
        if (!local.isFinite()) throw new IllegalArgumentException("Non-finite vertex in collision bind pose");
        minimum.min(local);
        maximum.max(local);
    }

    private static Box box(Vector3f minimum, Vector3f maximum) {
        return new Box(minimum.x, minimum.y, minimum.z, maximum.x, maximum.y, maximum.z);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Encloses each posed box in Minecraft's world axes, after parent transforms, animation and IK. */
    public List<Box> worldBounds(SkeletonInstance pose) {
        return newWorldSampler().sample(pose);
    }

    public WorldSampler newWorldSampler() {
        return new WorldSampler();
    }

    /** Thread-confined sampler that reuses the last immutable snapshot while the world bounds are unchanged. */
    public final class WorldSampler {
        private final Vector3d origin = new Vector3d();
        private final Vector3f minimum = new Vector3f();
        private final Vector3f maximum = new Vector3f();
        private List<Box> previous = List.of();

        private WorldSampler() {}

        public List<Box> sample(SkeletonInstance pose) {
            if (pose.getSkeleton() != skeleton) throw new IllegalArgumentException("Pose belongs to a different skeleton");
            pose.getWorldOrigin(origin);
            List<Box> next = previous.size() == entries.size() ? null : new ArrayList<>(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                Box box = sampleBox(pose, entry, i < previous.size() ? previous.get(i) : null);
                if (next == null && box != previous.get(i)) {
                    next = new ArrayList<>(entries.size());
                    next.addAll(previous.subList(0, i));
                }
                if (next != null) next.add(box);
            }
            if (next != null) previous = List.copyOf(next);
            return previous;
        }

        private Box sampleBox(SkeletonInstance pose, Entry entry, Box previousBox) {
            Transform absolute = pose.getAbsoluteTransforms().get(entry.bone());
            Box local = entry.localBounds();
            absolute.transform().transformAab((float) local.minX(), (float) local.minY(), (float) local.minZ(),
                    (float) local.maxX(), (float) local.maxY(), (float) local.maxZ(), minimum, maximum);
            if (!minimum.isFinite() || !maximum.isFinite()) {
                throw new IllegalStateException("Non-finite collision pose for bone " + entry.bone().getName());
            }
            // Keep planar geometry collidable after conversion to a voxel shape.
            double padX = padding(minimum.x, maximum.x);
            double padY = padding(minimum.y, maximum.y);
            double padZ = padding(minimum.z, maximum.z);
            double minX = origin.x + minimum.x - padX;
            double minY = origin.y + minimum.y - padY;
            double minZ = origin.z + minimum.z - padZ;
            double maxX = origin.x + maximum.x + padX;
            double maxY = origin.y + maximum.y + padY;
            double maxZ = origin.z + maximum.z + padZ;
            if (previousBox != null && previousBox.matches(minX, minY, minZ, maxX, maxY, maxZ)) return previousBox;
            return new Box(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static double padding(float min, float max) {
        return Math.max(0, MINIMUM_HALF_THICKNESS - ((double) max - min) * 0.5);
    }
}
