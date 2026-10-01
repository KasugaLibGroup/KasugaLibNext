package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.*;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.VertexPosMorph;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.*;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.*;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread) @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MICROSECONDS)
public class RenderPreparationBenchmark {
    @Param({"64","512"}) public int count;
    private FloatBuffer palette;
    private ModelInstance instance;
    private final Matrix4f absolute = new Matrix4f().translation(2,3,4), inverse = new Matrix4f();
    private final Matrix3f normal = new Matrix3f();
    private final Vector3f min = new Vector3f(), max = new Vector3f();
    private ElementChanges changes;
    private BitSet dirty;
    private long readVersion;
    private int step;
    private Integer[] morphIds;
    @Setup public void setup() {
        palette=FloatBuffer.allocate(count*BonePalettePacker.FLOATS_PER_BONE);
        Bone[] bones=new Bone[count];
        bones[0]=new Bone("root",new Transform(),null);
        for(int i=1;i<count;i++) { bones[i]=new Bone("bone"+i,new Transform().translate(0,.01f,0),null); bones[i].setParent(bones[i-1]); }
        for(int i=0;i<count;i++) bones[i].setChildren(i+1<count?new Bone[]{bones[i+1]}:new Bone[0]);
        Skeleton skeleton=new Skeleton(bones,bones[0],new Anchor[0],null,new Transform());
        Vertex[] vertices=new Vertex[count];
        for(int i=0;i<count;i++) {
            vertices[i]=new Vertex(new Vector3f(i,0,0),null);
            vertices[i].setBinding(new BoneBinding(new lib.kasuga.structure.Pair[0],
                    lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc.IDENTITY,null));
        }
        Model model=new Model(vertices,new Mesh[0],bones,skeleton,new MaterialSet(List.of(),List.of()),MeshMode.TRIANGLES,null,null);
        morphIds = new Integer[count];
        for(int i=0;i<count;i++) {
            morphIds[i] = i;
            model.getMorph().addMorph(morphIds[i],new VertexPosMorph<>(vertices[i],morphIds[i],new Vector3f(i,1,0)));
        }
        instance=new ModelInstance(model,null,null,null,null,null);
        instance.update();
        changes=new ElementChanges(count);dirty=new BitSet(count);
    }
    @Benchmark public void palettePacking(Blackhole sink) {
        palette.clear();for(int i=0;i<count;i++) BonePalettePacker.put(palette,absolute,inverse,normal);sink.consume(palette.get(3));
    }
    @Benchmark public void evaluatedBounds(Blackhole sink) {sink.consume(MCBackend.scanEvaluatedBounds(instance,min,max));sink.consume(max.y);}
    @Benchmark public void skeletonHierarchy(Blackhole sink) {instance.getSkeletonInstance().setShouldUpdate(true);instance.getSkeletonInstance().updateTransform();sink.consume(instance.getSkeletonInstance().getVersion());}
    @Benchmark public void morphScatter(Blackhole sink) {
        float weight=(++step&1)==0?1f:.5f;
        for(int i=0;i<count;i+=8) instance.getMorph().activateMorph(morphIds[i],weight);
        instance.getMorph().update();sink.consume(instance.getMorph().getVertexChanges().version());
    }
    @Benchmark public void sparseConsumerCatchUp(Blackhole sink) {
        changes.mark((++step)%count);readVersion=changes.collectSince(readVersion,dirty);sink.consume(dirty);
    }
}
