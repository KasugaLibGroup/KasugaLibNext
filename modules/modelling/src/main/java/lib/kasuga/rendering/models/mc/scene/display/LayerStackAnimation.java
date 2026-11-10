package lib.kasuga.rendering.models.mc.scene.display;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.*;
import java.util.List;

/** Reusable progressive layer clock driven by the model proxy's logical Scope, never by drawing. */
public final class LayerStackAnimation implements RuntimeController<Void> {
    public enum State { PLAYING, PAUSED, COMPLETE }
    private State state=State.COMPLETE;
    private double previous,elapsed;
    private double secondsPerLayer=.35;
    private int layers;
    public LayerStackAnimation(int layers) {
        if(layers<0)throw new IllegalArgumentException("Layer count must be nonnegative");
        this.layers=layers;
    }
    public LayerStackAnimation secondsPerLayer(double value) {
        if(!Double.isFinite(value)||value<=0)throw new IllegalArgumentException("Layer duration must be positive");
        double ratio=value/secondsPerLayer;elapsed*=ratio;previous*=ratio;
        secondsPerLayer=value;return this;
    }
    public double secondsPerLayer() { return secondsPerLayer; }
    public LayerStackAnimation play() { previous=elapsed=0;state=layers==0?State.COMPLETE:State.PLAYING;return this; }
    public LayerStackAnimation pause() { if(state==State.PLAYING){state=State.PAUSED;previous=elapsed;}return this; }
    public LayerStackAnimation resume() { if(state==State.PAUSED)state=State.PLAYING;return this; }
    public LayerStackAnimation showAll() { state=State.COMPLETE;previous=elapsed=layers*secondsPerLayer;return this; }
    public State state() { return state; }
    public void layers(int count) { if(count<0)throw new IllegalArgumentException();layers=count;showAll(); }
    /** Number of completed layers plus the entering layer's fraction. No hidden clock mutation. */
    public double progress(float partialTick) {
        if(state==State.COMPLETE)return layers;
        double t=state==State.PAUSED?elapsed:previous+(elapsed-previous)*Math.clamp(partialTick,0,1);
        return Math.clamp(t/secondsPerLayer,0,layers);
    }
    @Override public ControllerCheckpoint beginStep() {
        double before=elapsed,prev=previous;State old=state;
        return ()->{elapsed=before;previous=prev;state=old;};
    }
    @Override public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
        if(state==State.PLAYING && request.timeStep().cause()==ScopeAdvanceCause.GAME_TICK) {
            previous=elapsed;elapsed=Math.min(layers*secondsPerLayer,elapsed+request.timeStep().deltaSeconds());
            if(elapsed>=layers*secondsPerLayer)state=State.COMPLETE;
        }
        return new ControlFrame<>(null,List.of(),List.of());
    }
}
