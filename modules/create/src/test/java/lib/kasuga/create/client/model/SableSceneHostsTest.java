package lib.kasuga.create.client.model;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import lib.kasuga.scene.SceneHost;
import lib.kasuga.scene.ScenePose;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SableSceneHostsTest {
    @Test void distantPlotAnchorUsesActualRenderPoseRotationPointAndNonUniformScale() {
        var pose = new Pose3d();
        pose.position().set(29_999_998.125, 78.375, -29_999_999.625);
        pose.rotationPoint().set(1_280_064.5, 64.125, 1_280_064.5);
        pose.orientation().rotateXYZ(.2, -.7, .4);
        pose.scale().set(1.5, .8, 2.25);
        var anchor = new Vec3(1_280_065, 66, 1_280_064);
        var sample = SableSceneHosts.Bridge.anchored(pose, anchor);
        for (var offset : new Vec3[]{Vec3.ZERO, new Vec3(.125, .5, -.25), new Vec3(3, -2, 4)}) {
            Vec3 expected = pose.transformPosition(anchor.add(offset));
            assertEquals(0, expected.distanceTo(sample.toWorld(offset)), 1e-5);
            assertEquals(0, offset.distanceTo(sample.toLocal(expected)), 1e-5);
        }
        Vec3 camera = sample.origin().add(.125, .25, 3.75);
        assertEquals(-.125f, sample.relativeTo(camera).m30(), 1e-6);
        assertEquals(-.25f, sample.relativeTo(camera).m31(), 1e-6);
        assertEquals(-3.75f, sample.relativeTo(camera).m32(), 1e-6);
    }

    @Test void unloadedReplacedOrReusedUuidCannotReviveAnAttachment() {
        var first = new SubLevel();
        var current = new AtomicReference<ClientSubLevelAccess>(first);
        var host = SableSceneHosts.Bridge.track(current::get, Vec3.ZERO, null);
        assertTrue(host.isAlive());
        var replacement = new SubLevel(); replacement.id = first.id;
        current.set(replacement);
        assertFalse(host.isAlive(), "Object identity matters even when UUID is reused");
        current.set(first);
        assertFalse(host.isAlive(), "A lost binding stays expired");
        assertNull(host.pose(.5f));

        var missing = SableSceneHosts.Bridge.track(current::get, Vec3.ZERO, null);
        current.set(null);
        assertNull(missing.pose(.25f));
    }

    @Test void uuidChangesAlsoInvalidateTheOriginalObject() {
        var first = new SubLevel();
        var host = SableSceneHosts.Bridge.track(() -> first, Vec3.ZERO, null);
        first.id = UUID.randomUUID();
        assertFalse(host.isAlive());
    }

    @Test void removingChildContraptionExpiresTheNestedBindingPermanently() {
        var parent = new SubLevel();
        var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        SceneHost child = new SceneHost() {
            @Override public boolean isAlive() { return alive.get(); }
            @Override public ScenePose pose(float partial) { return new ScenePose(Vec3.ZERO, new Matrix4f()); }
        };
        var host = SableSceneHosts.Bridge.track(() -> parent, Vec3.ZERO, child);
        assertNotNull(host.pose(.5f));
        alive.set(false);
        assertNull(host.pose(.5f));
        alive.set(true);
        assertFalse(host.isAlive());
    }

    @Test void nestedCreateFrameComposesParentRotationAndScaleExactlyOnce() {
        var parent = new SubLevel();
        parent.render.position().set(20.125, 90, -5.375);
        parent.render.rotationPoint().set(1_280_064, 64, 1_280_064);
        parent.render.orientation().rotateY(.7).rotateZ(.3);
        parent.render.scale().set(1.25, .75, 2);
        var childPose = new ScenePose(new Vec3(1_280_066.125, 66, 1_280_061.25),
                new Matrix4f().rotateX(.4f).rotateY(-.25f));
        SceneHost child = new SceneHost() {
            @Override public boolean isAlive() { return true; }
            @Override public ScenePose pose(float partial) { return childPose; }
        };
        var host = SableSceneHosts.Bridge.track(() -> parent, Vec3.ZERO, child);
        var sample = host.pose(.35f);
        var point = new Vec3(.25, 2, -.5);
        var expected = parent.render.transformPosition(childPose.toWorld(point));
        assertEquals(0, expected.distanceTo(sample.toWorld(point)), 1e-5);
        assertEquals(.35f, parent.partial);
        assertEquals(0, parent.logicalSamples, "Drawing must never use the logical pose");
    }

    @Test void absenceOfCompanionDoesNotLinkItsClassesOrBreakTheOptionalEntryPoint() throws Exception {
        var parent = SableSceneHosts.class.getClassLoader();
        var name = SableSceneHosts.class.getName();
        var loader = new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String type, boolean resolve) throws ClassNotFoundException {
                if (type.startsWith("dev.ryanhcode.sable.")) throw new ClassNotFoundException("Companion deliberately absent");
                if (type.equals(name)) {
                    synchronized (getClassLoadingLock(type)) {
                        Class<?> found = findLoadedClass(type);
                        if (found == null) try (var input = parent.getResourceAsStream(type.replace('.', '/') + ".class")) {
                            byte[] bytes = input.readAllBytes(); found = defineClass(type, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) { throw new ClassNotFoundException(type, error); }
                        if (resolve) resolveClass(found);
                        return found;
                    }
                }
                return super.loadClass(type, resolve);
            }
        };
        var adapter = Class.forName(name, true, loader);
        assertEquals(false, adapter.getMethod("isAvailable").invoke(null));
        assertEquals(java.util.Optional.empty(), adapter.getMethod("block", net.minecraft.client.multiplayer.ClientLevel.class,
                net.minecraft.core.BlockPos.class).invoke(null, null, null));
    }

    private static final class SubLevel implements ClientSubLevelAccess {
        UUID id = UUID.randomUUID();
        final Pose3d render = new Pose3d();
        float partial;
        int logicalSamples;
        @Override public Pose3dc logicalPose() { logicalSamples++; return new Pose3d(); }
        @Override public Pose3dc lastPose() { return new Pose3d(); }
        @Override public Pose3dc renderPose() { return render; }
        @Override public Pose3dc renderPose(float partial) { this.partial = partial; return render; }
        @Override public BoundingBox3dc boundingBox() { return new BoundingBox3d(0, 0, 0, 1, 1, 1); }
        @Override public UUID getUniqueId() { return id; }
        @Override public String getName() { return "test"; }
    }
}
