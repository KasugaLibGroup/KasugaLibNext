package lib.kasuga.rendering.models.mc.scene.display;

import lib.kasuga.rendering.models.mc.scene.*;
import java.util.*;

/** Composite display, itself a target; owns children and composes model and UI transforms together. */
public final class WorldDisplayScene extends WorldDisplayTarget {
    private final List<WorldDisplayTarget> children = new ArrayList<>();
    public <T extends WorldDisplayTarget> T add(T target) {
        requireOpen(); Objects.requireNonNull(target);
        for (Object node=this; node instanceof WorldDisplayTarget parent; node=parent.owner())
            if(node == target) throw new IllegalArgumentException("Display scene cycle");
        target.claim(this);children.add(target);return target;
    }
    public List<WorldDisplayTarget> children() { return List.copyOf(children); }
    /** Removal destroys the entire owned subtree. */
    public void remove(WorldDisplayTarget target) { if(children.remove(target)) target.close(); }
    @Override protected void tickChildren(long tick,double dt) { for(var child:List.copyOf(children)) child.tick(tick,dt); }
    @Override protected Snapshot contentSnapshot(LayerLayout layout,float partial) {
        var parts=new ArrayList<Part>();ModelBounds bounds=null;
        for(int i=0;i<children.size();i++) {
            var frame=children.get(i).snapshot(layout,partial); if(frame==null)continue;
            bounds=bounds==null?frame.bounds():bounds.union(frame.bounds());
            String prefix=i+"/";
            for(var p:frame.parts())parts.add(new Part(prefix+p.id(),p.layer(),p.bounds(),p.renderer(),p.picker()));
        }
        return bounds==null?null:new Snapshot(bounds,parts);
    }
    @Override protected List<Panel> contentPanels() { return children.stream().flatMap(c -> c.panels().stream()).toList(); }
    @Override protected void closeContent() {
        RuntimeException failure=null;
        for(var c:children)try { c.close(); }catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        children.clear();if(failure!=null)throw failure;
    }
}
