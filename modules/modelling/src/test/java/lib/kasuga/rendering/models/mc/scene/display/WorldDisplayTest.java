package lib.kasuga.rendering.models.mc.scene.display;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.models.mc.scene.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class WorldDisplayTest {
    static class Source extends LayeredModelSource {
        int closes; final List<Vector3f> draws = new ArrayList<>();
        @Override protected List<Element> elements() {
            return List.of(new Element(0,-3,0,new ModelBounds(0,-3,0,1,-2,1)),
                    new Element(0,2,0,new ModelBounds(0,2,0,1,3,1)));
        }
        @Override protected void renderSlice(Slice slice, Context ctx) {
            for(var e:slice.elements()) draws.add(ctx.pose().last().pose().transformPosition(new Vector3f(e.x(),e.y(),e.z())));
        }
        @Override public void close() { closes++; super.close(); }
    }
    @Test void animationUsesSparseLayersAndOneProxyTickWhileSamplingIsReadOnly() {
        var source = new Source(); var display = new ModelDisplayTarget(source);
        display.stacking().secondsPerLayer(.2).play();
        assertEquals(0,display.snapshot(LayerLayout.ASSEMBLED,0).parts().size());
        display.tick(1,.05); display.tick(1,.05);
        long revision = display.proxySession().revision();
        var a=display.snapshot(LayerLayout.ASSEMBLED,.5f);
        for(int i=0;i<100;i++) display.snapshot(LayerLayout.ASSEMBLED,.5f);
        assertEquals(revision,display.proxySession().revision());
        assertEquals(1,a.parts().size()); assertEquals(-3,a.parts().getFirst().layer());
        display.stacking().pause(); var paused=display.snapshot(LayerLayout.ASSEMBLED,.5f);
        display.tick(2,.05); assertEquals(paused.parts().getFirst().bounds(),display.snapshot(LayerLayout.ASSEMBLED,.5f).parts().getFirst().bounds());
        display.stacking().resume();
        for(int i=3;i<20;i++) display.tick(i,.05);
        assertEquals(LayerStackAnimation.State.COMPLETE,display.stacking().state());
        assertEquals(source.snapshot(LayerLayout.ASSEMBLED,0).bounds(),display.snapshot(LayerLayout.ASSEMBLED,0).bounds());
        display.close(); display.close(); assertEquals(1,source.closes); assertTrue(display.proxySession().isClosed());
    }
    @Test void nestingOwnsChildrenAndAppliesTransformsExactlyOnce() {
        var source = new Source(); var model = new ModelDisplayTarget(source);
        model.transform(new Matrix4f().translate(2,0,0));
        var inner=new WorldDisplayScene(); inner.transform(new Matrix4f().scale(2));inner.add(model);
        var root=new WorldDisplayScene();root.transform(new Matrix4f().translate(10,0,0));root.add(inner);
        assertThrows(IllegalArgumentException.class,()->inner.add(root));
        assertThrows(IllegalStateException.class,()->root.add(model));
        var frame=root.snapshot(LayerLayout.ASSEMBLED,0);var pose=new PoseStack();
        frame.render(new MinecraftModelSource.Context(pose,type->{throw new AssertionError();},new Matrix4f(),0,0));
        assertEquals(14,source.draws.getFirst().x,1e-5);assertEquals(-6,source.draws.getFirst().y,1e-5);
        assertEquals(new Matrix4f(),pose.last().pose());
        root.close();root.close();assertEquals(1,source.closes);
    }
    @Test void interruptionAndInvalidInputsHaveDefinedSemantics() {
        var d=new ModelDisplayTarget(new Source());
        assertThrows(IllegalArgumentException.class,()->d.stacking().secondsPerLayer(0));
        assertThrows(IllegalArgumentException.class,()->d.tick(1,Double.NaN));
        d.stacking().play();d.tick(1,.05);d.stacking().showAll();
        assertEquals(LayerStackAnimation.State.COMPLETE,d.stacking().state());
        d.stacking().play();assertEquals(0,d.snapshot(LayerLayout.ASSEMBLED,0).parts().size());
        d.close();assertNull(d.snapshot(LayerLayout.ASSEMBLED,0));
    }
    @Test void invalidLayerCountsAndDirectOwnershipCyclesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new LayerStackAnimation(-1));
        var target=new ModelDisplayTarget(new Source());
        assertThrows(IllegalArgumentException.class,()->target.claim(target));
        target.close();
    }
    @Test void controllerFailureRollsBackAnimationAndSceneClosesAllSiblingsOnFailure() {
        var display=new ModelDisplayTarget(new Source());
        display.controller(request->{throw new IllegalStateException("controller failed");});display.stacking().play();
        assertThrows(IllegalStateException.class,()->display.tick(1,.05));
        assertEquals(0,display.stacking().progress(1));assertEquals(0,display.proxySession().revision());display.close();
        var bad=new Source(){@Override public void close(){super.close();throw new IllegalStateException("close failed");}};
        var good=new Source();var root=new WorldDisplayScene();root.add(new ModelDisplayTarget(bad));root.add(new ModelDisplayTarget(good));
        assertThrows(IllegalStateException.class,root::close);root.close();
        assertEquals(1,bad.closes);assertEquals(1,good.closes);
    }
    @Test void animatedPickingUsesTheSameNestedTransformAsTheVisibleLayer() {
        var source=new Source(){
            @Override protected java.util.Optional<BlockHit> pickSlice(Slice slice,net.minecraft.world.phys.Vec3 from,net.minecraft.world.phys.Vec3 to) {
                return slice.elements().stream().flatMap(e->{var b=e.bounds();return new net.minecraft.world.phys.AABB(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()).clip(from,to)
                        .map(p->new BlockHit(new net.minecraft.core.BlockPos(e.x(),e.y(),e.z()),null,p,net.minecraft.core.Direction.NORTH)).stream();}).findFirst();
            }
        };
        var model=new ModelDisplayTarget(source);model.stacking().secondsPerLayer(.2).play();model.tick(1,.05);
        var root=new WorldDisplayScene();root.transform(new Matrix4f().translate(7,2,3).rotateY(.4f).scale(2));root.add(model);
        float y=-3+.675f+.5f;
        var matrix=root.transform();
        var from=matrix.transformPosition(new Vector3f(.5f,y,-2));var to=matrix.transformPosition(new Vector3f(.5f,y,2));
        var hit=root.snapshot(LayerLayout.ASSEMBLED,1).pick(new net.minecraft.world.phys.Vec3(from),new net.minecraft.world.phys.Vec3(to)).orElseThrow();
        assertEquals(-3,hit.position().getY());
        var expected=matrix.transformPosition(new Vector3f(.5f,y,0));
        assertEquals(0,hit.location().distanceTo(new net.minecraft.world.phys.Vec3(expected)),1e-4);
        root.close();
    }
    @Test void existingModelFsmSharesTheDisplayProxyClockAndRollback() {
        var target=new ModelDisplayTarget(new Source());
        var machine=lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine.builder(target)
                .layer("display",layer->layer.initial(layer.state("idle"))).build();
        var commits=new java.util.concurrent.atomic.AtomicInteger();var fail=new java.util.concurrent.atomic.AtomicBoolean();
        target.controller(new lib.kasuga.rendering.models.mc.proxies.ProxyMachineController(()->machine,m->{},m->commits.incrementAndGet(),null));
        target.controller(request->{if(fail.get())throw new IllegalStateException("rollback probe");return new lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlFrame<>(null,List.of(),List.of());});
        target.stacking().play();target.tick(1,.05);target.tick(1,.05);
        for(int i=0;i<20;i++)target.snapshot(LayerLayout.ASSEMBLED,.5f);
        assertEquals(1,machine.tickCount());assertEquals(1,commits.get());
        fail.set(true);assertThrows(IllegalStateException.class,()->target.tick(2,.05));
        assertEquals(1,machine.tickCount());assertEquals(1,commits.get());target.close();
    }
}
