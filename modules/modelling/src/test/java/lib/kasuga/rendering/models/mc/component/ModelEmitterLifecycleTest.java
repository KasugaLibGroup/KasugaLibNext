package lib.kasuga.rendering.models.mc.component;

import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.dynamic.ModelParticleRuntime;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelEmitterLifecycleTest {
    static ModelComponents components(boolean local) {
        return ModelComponents.parse(JsonParser.parseString("""
            {"version":1,"bones":[{"id":"bone","name":"bone","parent":null,"pivot":[0,0,0],"components":{}}],
             "bindings":[],"emitters":[{"id":"source","bone":"bone","position":[16,0,0],"rotation":[0,0,0],
             "space":"SPACE","rate":60,"max_particles":128,"particle":{"speed":16,"spread":0,"size":2,"lifetime":2,"color":"#ff8844","gravity":[0,0,0]}}]}
            """.replace("SPACE",local ? "local" : "world")));
    }
    static ModelInstance instance(boolean local) {
        var c=components(local); var skeleton=c.createSkeleton();c.apply(skeleton);
        var material=ModelInstanceFixture.minimal().getModel().getMaterialSet();
        var model=new Model(new Vertex[0],new Mesh[0],skeleton.getBones(),skeleton,material,MeshMode.QUADS,new ModelComponentsData(c),null);
        return new ModelInstance(model,null,null,null,null,null);
    }
    @Test void instanceOwnsEmitterAndCloseCleansItEvenWhenAnotherModuleThrows() {
        var model=instance(false);
        var emitter=ModelParticleRuntime.module(model);
        assertNotNull(emitter,"model construction must install emitter module");
        ModelParticleRuntime.register(model);
        model.tick(1f/20);assertEquals(3,emitter.particleCount());
        model.getTickLoop().addPreIk("throwing-cleanup",new lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule(){
            public void tick(Model ignored,lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform[] transforms,lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop loop,float dt){}
            public void destroy(Model ignored){throw new IllegalStateException("fixture cleanup");}
        });
        assertThrows(IllegalStateException.class,model::close);
        assertTrue(emitter.closed());assertEquals(0,emitter.particleCount());model.close();
    }
    @Test void localParticlesRotateWithBoneWhileWorldParticlesKeepTheirWorldPosition() {
        try(var local=instance(true);var world=instance(false)){
            ModelParticleRuntime.register(local);ModelParticleRuntime.register(world);local.tick(1f/20);world.tick(1f/20);
            var a=ModelParticleRuntime.module(local);var b=ModelParticleRuntime.module(world);
            Vector3d before=b.particles().getFirst().position();
            local.getSkeletonInstance().transform("bone",new Transform().rotate(0,0,90,true));
            world.getSkeletonInstance().transform("bone",new Transform().rotate(0,0,90,true));local.tick(0);world.tick(0);
            assertEquals(before,b.particles().getFirst().position());
            assertTrue(a.particles().getFirst().position().distance(before)>0.5);
            ModelParticleRuntime.unregister(local);assertEquals(0,a.particleCount());assertFalse(a.enabled());
        }
    }
    @Test void emissionIsFrameIndependentBoundedAndDoesNotQueueDroppedEmissions() {
        for(int fps:new int[]{20,30,60})try(var instance=instance(false)){
            ModelParticleRuntime.register(instance);for(int i=0;i<fps;i++)instance.tick(1f/fps);
            assertEquals(60,ModelParticleRuntime.module(instance).particleCount());
            int count=ModelParticleRuntime.module(instance).particleCount();instance.tick(0);assertEquals(count,ModelParticleRuntime.module(instance).particleCount());
        }
        try(var one=instance(false);var two=instance(false)){
            ModelParticleRuntime.register(one);ModelParticleRuntime.register(two);one.tick(1f/20);two.tick(1f/20);
            var first=ModelParticleRuntime.module(one);var second=ModelParticleRuntime.module(two);
            ModelParticleRuntime.clearWorldState();assertEquals(0,first.particleCount());assertEquals(0,second.particleCount());assertFalse(first.enabled());
        }
    }
    @Test void definitionsAreCopiedAndInvalidValuesAreRejected() {
        var c=components(true);var e=c.emitters().getFirst();e.position().set(900);assertEquals(16,c.emitters().getFirst().position().x,1e-6);
        String raw="{\"version\":1,\"bones\":[],\"bindings\":[],\"emitters\":[{\"id\":\"e\",\"space\":\"bad\",\"position\":[0,0,0],\"rotation\":[0,0,0]}]}";
        assertThrows(IllegalArgumentException.class,()->ModelComponents.parse(JsonParser.parseString(raw)));
    }
}
