package lib.kasuga.rendering.models.uml.dynamic.ik;

import au.edu.federation.caliko.FabrikBone3D;
import au.edu.federation.caliko.FabrikChain3D;
import au.edu.federation.caliko.FabrikJoint3D;
import au.edu.federation.utils.Vec3f;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/** Caliko solves positions; this adapter inverse-maps segment directions into local bone rotations. */
public final class CalikoIkSolver {
    public interface PoseAccess {
        Transform absolute(Bone bone);
        Transform correction(Bone bone);
        Vector3f target(IkChain chain);
        boolean enabled(String chain);
        void refresh(Bone driver, Bone endpoint);
    }

    public record SolveResult(float residual, boolean satisfied) {}

    private final Skeleton skeleton;
    private final PoseAccess pose;
    private final Map<String, SolveResult> results = new LinkedHashMap<>();

    public CalikoIkSolver(Skeleton skeleton, PoseAccess pose) {
        this.skeleton = skeleton;
        this.pose = pose;
    }

    public Map<String, SolveResult> results() { return Collections.unmodifiableMap(results); }

    public void solve() {
        results.clear();
        Map<String, IkChain> chains = skeleton.getDynamics().chainsByName();
        Set<String> closed = new HashSet<>();
        for (DiamondConstraint diamond : skeleton.getDynamics().diamonds()) {
            closed.add(diamond.firstChain()); closed.add(diamond.secondChain());
        }
        for (IkChain chain : chains.values()) {
            if (!closed.contains(chain.name()) && pose.enabled(chain.name())) {
                solveChain(chain, pose.target(chain));
            }
        }
        for (DiamondConstraint diamond : skeleton.getDynamics().diamonds()) {
            IkChain first = chains.get(diamond.firstChain()), second = chains.get(diamond.secondChain());
            if (!pose.enabled(first.name()) || !pose.enabled(second.name())) continue;
            Vector3f goal = pose.target(first).add(pose.target(second)).mul(0.5f);
            float residual = Float.POSITIVE_INFINITY;
            for (int iteration = 0; iteration < diamond.iterations(); iteration++) {
                solveChain(first, goal);
                solveChain(second, goal);
                Vector3f a = position(first.effector()), b = position(second.effector());
                residual = Math.max(a.distance(b), position(first.links().getFirst().bone())
                        .distance(position(second.links().getFirst().bone())));
                if (residual <= diamond.tolerance()) break;
                // Project the shared endpoint into both branches' reachable regions, using Caliko for each.
                goal.set(a).add(b).mul(0.5f);
            }
            results.put(diamond.name(), new SolveResult(residual, residual <= diamond.tolerance()));
        }
    }

    private void solveChain(IkChain definition, Vector3f target) {
        if (target == null || !target.isFinite()) return;
        float residual = position(definition.effector()).distance(target);
        // A neutral PMX knee may exclude zero by a tiny Euler epsilon. Feeding an already
        // satisfied chain into FABRIK would unnecessarily choose another bend solution.
        if (residual <= definition.tolerance()) {
            results.put(definition.name(), new SolveResult(residual, true));
            return;
        }
        float previousResidual = Float.POSITIVE_INFINITY;
        // Reconstruct from the evaluated pose each pass. Caliko caches unchanged target/base pairs;
        // reusing such a cache after animation or limits change would return stale geometry.
        for (int pass = 0; pass < definition.iterations(); pass++) {
            FabrikChain3D chain = new FabrikChain3D();
            chain.setMaxIterationAttempts(definition.iterations());
            chain.setSolveDistanceThreshold(definition.tolerance());
            chain.setMinIterationChange(definition.tolerance() * 0.1f);
            List<IkLink> active = new ArrayList<>();
            List<Bone> endpoints = new ArrayList<>();
            for (int index = 0; index < definition.links().size(); index++) {
                IkLink link = definition.links().get(index);
                Bone endpoint = index + 1 < definition.links().size()
                        ? definition.links().get(index + 1).bone() : definition.effector();
                Vector3f start = position(link.bone()), end = position(endpoint);
                if (start.distanceSquared(end) < 1e-12f) continue;
                FabrikBone3D segment = new FabrikBone3D(vec(start), vec(end));
                configureJoint(segment, link, active.isEmpty());
                chain.addBone(segment);
                if (active.isEmpty()) configureBase(chain, link, start, end);
                active.add(link); endpoints.add(endpoint);
            }
            if (active.isEmpty()) break;
            chain.solveForTarget(vec(target));
            for (int index = 0; index < active.size(); index++) {
                IkLink link = active.get(index);
                Bone endpoint = endpoints.get(index);
                Vector3f current = position(endpoint).sub(position(link.bone()));
                Vec3f solved = chain.getBone(index).getDirectionUV();
                Vector3f desired = new Vector3f(solved.x, solved.y, solved.z);
                Matrix4f inverse = pose.absolute(link.bone()).invertTransform();
                inverse.transformDirection(current);
                inverse.transformDirection(desired);
                if (current.lengthSquared() < 1e-12f || desired.lengthSquared() < 1e-12f) continue;
                Quaternionf delta = new Quaternionf().rotationTo(current.normalize(), desired.normalize());
                float angle = 2f * (float) Math.acos(Math.clamp(Math.abs(delta.w), 0f, 1f));
                if (angle > definition.maxRotationStep() && angle > 1e-6f) {
                    delta = new Quaternionf().slerp(delta, definition.maxRotationStep() / angle);
                }
                Transform correction = pose.correction(link.bone());
                correction.mul(delta);
                if (link.limit() != null) clamp(correction, link.limit());
                pose.refresh(link.bone(), endpoint);
            }
            residual = position(definition.effector()).distance(target);
            if (residual <= definition.tolerance()) break;
            if (Math.abs(previousResidual - residual) < definition.tolerance() * 0.01f) break;
            previousResidual = residual;
        }
        results.put(definition.name(), new SolveResult(residual, residual <= definition.tolerance()));
    }

    private Vector3f position(Bone bone) { return pose.absolute(bone).getPosition(); }
    private static Vec3f vec(Vector3f value) { return new Vec3f(value.x, value.y, value.z); }

    private Quaternionf referenceRotation(IkLink link) {
        return pose.absolute(link.bone()).copy().mul(pose.correction(link.bone()).invert()).getRotation();
    }

    private void configureJoint(FabrikBone3D segment, IkLink link, boolean base) {
        if (base) return;
        if (link.joint() instanceof Rotor rotor) {
            segment.setBallJointConstraintDegs((float) Math.toDegrees(rotor.angle()));
        } else if (link.joint() instanceof Hinge hinge) {
            Quaternionf rotation = referenceRotation(link);
            Vector3f axis = rotation.transform(hinge.axis());
            Vector3f reference = rotation.transform(hinge.reference());
            float midpoint = (hinge.minimum() + hinge.maximum()) * 0.5f;
            new Quaternionf().rotationAxis(midpoint, axis).transform(reference);
            float halfRange = (float) Math.toDegrees((hinge.maximum() - hinge.minimum()) * 0.5f);
            FabrikJoint3D joint = new FabrikJoint3D();
            joint.setAsGlobalHinge(vec(axis), halfRange, halfRange, vec(reference));
            segment.setJoint(joint);
        }
    }

    private void configureBase(FabrikChain3D chain, IkLink link, Vector3f start, Vector3f end) {
        if (link.joint() instanceof Rotor rotor) {
            Vector3f localDirection = pose.absolute(link.bone()).invertTransform()
                    .transformDirection(new Vector3f(end).sub(start));
            referenceRotation(link).transform(localDirection).normalize();
            chain.setRotorBaseboneConstraint(FabrikChain3D.BaseboneConstraintType3D.GLOBAL_ROTOR,
                    vec(localDirection), (float) Math.toDegrees(rotor.angle()));
        } else if (link.joint() instanceof Hinge hinge) {
            Quaternionf rotation = referenceRotation(link);
            Vector3f axis = rotation.transform(hinge.axis());
            Vector3f reference = rotation.transform(hinge.reference());
            float midpoint = (hinge.minimum() + hinge.maximum()) * 0.5f;
            new Quaternionf().rotationAxis(midpoint, axis).transform(reference);
            float halfRange = (float) Math.toDegrees((hinge.maximum() - hinge.minimum()) * 0.5f);
            chain.setGlobalHingedBasebone(vec(axis), halfRange, halfRange, vec(reference));
        }
    }

    private static void clamp(Transform correction, RotationLimit limit) {
        Vector3f euler = correction.getRotation().getEulerAnglesXYZ(new Vector3f());
        Vector3f min = limit.minimum(), max = limit.maximum();
        euler.set(Math.clamp(euler.x, min.x, max.x), Math.clamp(euler.y, min.y, max.y),
                Math.clamp(euler.z, min.z, max.z));
        correction.set(new Matrix4f().rotationXYZ(euler.x, euler.y, euler.z));
    }
}
