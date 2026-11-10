package lib.kasuga.rendering.models.mc.scene.display;

import lib.kasuga.rendering.models.mc.scene.*;
import lib.kasuga.rendering.models.mc.backend.ui.ModelDisplayPanel;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import java.util.*;

/** Native/UML/Create display with shared layer controls, statistics and a lazily created world panel. */
public final class ModelDisplayTarget extends WorldDisplayTarget {
    private final MinecraftModelSource source;
    private final LayerStackAnimation stacking;
    private LayerLayout layout=LayerLayout.ASSEMBLED;
    private ModelDisplayPanel panel;
    private Matrix4f panelPlacement=new Matrix4f();
    private List<Integer> coordinates;
    public ModelDisplayTarget(MinecraftModelSource source) {
        this.source=Objects.requireNonNull(source);
        try { coordinates=source.layerCoordinates(layout.axis());stacking=new LayerStackAnimation(coordinates.size());controller(stacking); }
        catch(RuntimeException|Error failure){source.close();throw failure;}
    }
    public LayerStackAnimation stacking() { return stacking; }
    public LayerLayout layers() { return layout; }
    public ModelDisplayTarget layers(LayerLayout next) {
        requireOpen();Objects.requireNonNull(next);
        if(next.axis()!=layout.axis()) { coordinates=source.layerCoordinates(next.axis());stacking.layers(coordinates.size()); }
        layout=next;return this;
    }
    public List<Integer> coordinates() { return coordinates; }
    @Override public List<Integer> layerCoordinates(LayerLayout.Axis axis) { return source.layerCoordinates(axis); }
    @Override public Map<Integer,Map<ResourceLocation,Integer>> blockStatistics(LayerLayout.Axis axis) { return source.blockStatistics(axis); }
    public Map<Integer,Map<ResourceLocation,Integer>> statistics() { return blockStatistics(layout.axis()); }
    /** Built-in toolkit-independent panel. Position is local to this target, so nested scenes move it too. */
    public ModelDisplayTarget panel(Matrix4f placement,int width,int height) {
        requireOpen();
        if(!placement.isFinite()||!placement.isAffine()||placement.determinant()==0)throw new IllegalArgumentException("Invalid panel placement");
        panel=new ModelDisplayPanel(this,width,height);panelPlacement=new Matrix4f(placement);return this;
    }
    public ModelDisplayPanel panel() { return panel; }
    /** Auto-place a readable panel beside the fitted model, in the same parent coordinate frame. */
    public ModelDisplayTarget autoPanel() {
        var frame=snapshot(LayerLayout.ASSEMBLED,0);
        if(frame==null)throw new IllegalArgumentException("Model source is empty");
        var b=frame.bounds();
        double span=Math.max(.1,Math.max(b.maxX()-b.minX(),Math.max(b.maxY()-b.minY(),b.maxZ()-b.minZ())));
        float pixels=(float)(span/900);
        var placement=new Matrix4f(transform()).invert().mul(new Matrix4f().translation((float)(b.maxX()+span*.05),
                (float)b.maxY(),(float)(b.maxZ()+span*.05)).scale(pixels,-pixels,pixels));
        return panel(placement,260,240);
    }
    @Override protected List<Panel> contentPanels() { return panel==null?List.of():List.of(new Panel(panel,panelPlacement)); }
    @Override protected Snapshot contentSnapshot(LayerLayout requested,float partial) {
        LayerLayout selected=requested.equals(LayerLayout.ASSEMBLED)?layout:requested;
        if(stacking.state()==LayerStackAnimation.State.COMPLETE || coordinates.isEmpty())return source.snapshot(selected,partial);
        double progress=stacking.progress(partial);
        var full=source.snapshotLayers(selected,partial);if(full==null)return null;
        int completed=(int)Math.floor(progress);
        var parts=new ArrayList<Part>();
        if(progress>0)for(var p:full.parts()) {
            int index=Collections.binarySearch(coordinates,p.layer());
            if(index<0 || index>completed)continue;
            if(index==completed) {
                double f=progress-completed;
                if(f<=0)continue;
                // Smoothstep settles with zero velocity. Geometry and picking use the same placement.
                double offset=(1-f*f*(3-2*f))*.8;
                var move=new Matrix4f().translation(selected.axis()==LayerLayout.Axis.X?(float)offset:0,
                        selected.axis()==LayerLayout.Axis.Y?(float)offset:0,selected.axis()==LayerLayout.Axis.Z?(float)offset:0);
                var one=placed(new Snapshot(p.bounds(),List.of(p)),move,"");parts.addAll(one.parts());
            } else parts.add(p);
        }
        return new Snapshot(full.bounds(),parts);
    }
    /** Fit the model (and any attached panel) into a world-sized box without modifying source coordinates. */
    public ModelDisplayTarget fit(double extent) {
        if(!Double.isFinite(extent)||extent<=0)throw new IllegalArgumentException("Invalid fit extent");
        var frame=source.snapshot(LayerLayout.ASSEMBLED,0);
        if(frame==null)throw new IllegalArgumentException("Model source is empty");
        var b=frame.bounds();var center=b.minimum().add(b.maximum()).mul(.5f);
        double span=Math.max(.001,Math.max(b.maxX()-b.minX(),Math.max(b.maxY()-b.minY(),b.maxZ()-b.minZ())));
        transform(new Matrix4f().scale((float)(extent/span)).translate(-center.x,-center.y,-center.z));return this;
    }
    @Override protected void closeContent() { source.close(); }
}
