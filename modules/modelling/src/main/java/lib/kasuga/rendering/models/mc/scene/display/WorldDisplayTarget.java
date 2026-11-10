package lib.kasuga.rendering.models.mc.scene.display;

import lib.kasuga.rendering.models.mc.scene.*;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.*;
import lib.kasuga.rendering.models.uml.dynamic.proxy.*;
import net.minecraft.client.gui.components.Renderable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.util.*;

/** Client-thread owner of a display subtree: model, UI and proxy controllers share one lifecycle.
 * Snapshots never advance time. A host ticks only the root; children use the same tick identity.
 */
public abstract class WorldDisplayTarget implements MinecraftModelSource {
    public record Panel(Renderable view, Matrix4f transform) {
        public Panel { Objects.requireNonNull(view); transform = new Matrix4f(transform); }
        @Override public Matrix4f transform() { return new Matrix4f(transform); }
    }
    private Matrix4f transform = new Matrix4f();
    private boolean visible = true;
    private boolean closed;
    private Object owner;
    private Long lastTick;
    private final List<RuntimeController<Void>> controllers = new ArrayList<>();
    private ProxySession<WorldDisplayTarget, Void, Void, Runnable, Float> proxy;

    public final WorldDisplayTarget transform(Matrix4fc value) {
        if (!value.isFinite() || !value.isAffine() || value.determinant() == 0) throw new IllegalArgumentException("Invalid display transform");
        requireOpen(); transform = new Matrix4f(value); return this;
    }
    public final Matrix4f transform() { return new Matrix4f(transform); }
    public final WorldDisplayTarget visible(boolean value) { requireOpen(); visible = value; return this; }
    public final boolean visible() { return visible && !closed; }
    public final boolean isClosed() { return closed; }
    protected final void requireOpen() { if (closed) throw new IllegalStateException("Display is closed"); }

    /** Called by owning scenes, including world mounts. A target cannot be owned twice. */
    public final void claim(Object nextOwner) {
        requireOpen(); Objects.requireNonNull(nextOwner);
        for(Object node=nextOwner; node instanceof WorldDisplayTarget target; node=target.owner)
            if(target==this)throw new IllegalArgumentException("Display ownership cycle");
        if (owner != null) throw new IllegalStateException("Display already has an owner");
        owner = nextOwner;
    }
    protected final Object owner() { return owner; }
    /** True when an ancestor is already owned by an external host such as SceneModels. */
    public final boolean isHosted() {
        WorldDisplayTarget root=this;
        while(root.owner instanceof WorldDisplayTarget parent)root=parent;
        return root.owner!=null;
    }

    /** Adds a model/FSM controller before the first tick (e.g. ProxyMachineController).
     * All controllers participate in the existing Scope transaction and rollback protocol.
     */
    public final WorldDisplayTarget controller(RuntimeController<Void> controller) {
        requireOpen(); if (proxy != null) throw new IllegalStateException("Controllers are fixed after attachment");
        controllers.add(Objects.requireNonNull(controller)); return this;
    }
    public final ProxySession<WorldDisplayTarget, Void, Void, Runnable, Float> proxySession() {
        if (proxy == null) {
            requireOpen();
            var runtime = new ProxyRuntime();
            RuntimeController<Void> composite = new RuntimeController<>() {
                @Override public ControllerCheckpoint beginStep() {
                    var checkpoints = new ArrayList<ControllerCheckpoint>();
                    try { for (var c : controllers) checkpoints.add(c.beginStep()); }
                    catch (RuntimeException e) {
                        for (int i=checkpoints.size()-1;i>=0;i--) try { checkpoints.get(i).rollback(); } catch(RuntimeException cleanup) { e.addSuppressed(cleanup); }
                        throw e;
                    }
                    return new ControllerCheckpoint() {
                        @Override public void rollback() { for(int i=checkpoints.size()-1;i>=0;i--) checkpoints.get(i).rollback(); }
                        @Override public void commit() { for(var checkpoint:checkpoints) checkpoint.commit(); }
                    };
                }
                @Override public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
                    var commands = new ArrayList<PlaybackCommand>(); var events = new ArrayList<ControlEvent>();
                    for (var c : controllers) {
                        var frame=c.evaluate(request); commands.addAll(frame.commands()); events.addAll(frame.events());
                    }
                    return new ControlFrame<>(null, commands, events);
                }
            };
            var port = new ProxyControllerPort<Void,Void>() {
                @Override public void prepare(Void input) {}
                @Override public RuntimeController<Void> controller() { return composite; }
                @Override public Void output() { return null; }
            };
            var definition = new ProxyDefinition<WorldDisplayTarget,Void,Void,Runnable,Float>(
                    ProxyKey.external("kasuga_lib","world_display"), target -> ProxyComposition.of(
                    LogicalStepPolicy.fixed(.05, 200), ignored -> null, port, (ignored, output) -> {},
                    (ignored, command) -> command.run(), ProxyVisualPort.none(), List.of()));
            runtime.register(definition);
            proxy = runtime.attach(definition, this, ProxyAttachToken.random()).orElseThrow();
        }
        return proxy;
    }
    public final void tick(long tick, double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0) throw new IllegalArgumentException("Invalid display time step");
        if (closed || Objects.equals(lastTick, tick)) return;
        var outcome = proxySession().tick(tick, deltaSeconds);
        if (outcome.aborted()) throw outcome.failure();
        lastTick=tick; tickChildren(tick,deltaSeconds);
    }
    protected void tickChildren(long tick, double deltaSeconds) {}

    protected abstract Snapshot contentSnapshot(LayerLayout layout, float partialTick);
    protected List<Panel> contentPanels() { return List.of(); }
    protected abstract void closeContent();
    @Override public final Snapshot snapshot(LayerLayout layout, float partialTick) {
        if (!visible()) return null;
        if (!Float.isFinite(partialTick)) throw new IllegalArgumentException("Invalid partial tick");
        var frame=contentSnapshot(layout,partialTick);
        return frame == null ? null : placed(frame,transform,"");
    }
    public final List<Panel> panels() {
        if (!visible()) return List.of();
        return contentPanels().stream().map(p -> new Panel(p.view(),new Matrix4f(transform).mul(p.transform()))).toList();
    }
    /** Borrowed model view: a viewer may close its canvas without destroying a mounted target. */
    public final MinecraftModelSource borrowedSource() {
        return new MinecraftModelSource() {
            @Override public Snapshot snapshot(LayerLayout layout,float partial) { return WorldDisplayTarget.this.snapshot(layout,partial); }
            @Override public List<Integer> layerCoordinates(LayerLayout.Axis axis) { return WorldDisplayTarget.this.layerCoordinates(axis); }
            @Override public Map<Integer,Map<net.minecraft.resources.ResourceLocation,Integer>> blockStatistics(LayerLayout.Axis axis) { return WorldDisplayTarget.this.blockStatistics(axis); }
        };
    }
    public static Snapshot placed(Snapshot frame, Matrix4fc matrix, String prefix) {
        var placement=new Matrix4f(matrix);
        return new Snapshot(frame.bounds().transform(placement),frame.parts().stream().map(p -> new Part(
                prefix+p.id(),p.layer(),p.bounds().transform(placement),context -> {
                    context.pose().pushPose();
                    try { context.pose().mulPose(placement); p.renderer().render(context); }
                    finally { context.pose().popPose(); }
                },MinecraftModelSource.placedPicker(placement,p.picker()))).toList());
    }
    @Override public final void close() {
        if(closed) return; closed=true;
        try { if(proxy != null) proxy.close(); }
        finally { closeContent(); }
    }
}
