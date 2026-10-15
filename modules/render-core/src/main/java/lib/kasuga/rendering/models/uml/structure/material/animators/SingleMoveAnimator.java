package lib.kasuga.rendering.models.uml.structure.material.animators;

import lombok.Getter;

public class SingleMoveAnimator implements Animator {

    public static final SingleMoveAnimator STOP_AT_END = new SingleMoveAnimator(false);
    public static final SingleMoveAnimator RETURN_TO_START = new SingleMoveAnimator(true);

    @Getter
    private final boolean returnToStart;

    public SingleMoveAnimator(boolean returnToStart) {
        this.returnToStart = returnToStart;
    }

    @Override
    public void nextFrame(Animatable animatable) {
        int current = getCurrentFrame(animatable);
        int frameCount = getFrameCount(animatable);
        if (frameCount <= 1) {return;}
        if (current >= frameCount - 1) {
            if (returnToStart) {
                setFrame(animatable, 0);
            }
            return;
        }
        setFrame(animatable, Math.min(getFrameCount(animatable) - 1, current + 1));
    }

    @Override
    public void previousFrame(Animatable animatable) {
        int current = getCurrentFrame(animatable);
        int frameCount = getFrameCount(animatable);
        if (frameCount <= 1) {return;}
        setFrame(animatable, Math.max(0, current - 1));
    }

    @Override
    public void stepFrame(Animatable animatable, int step) {
        int current = getCurrentFrame(animatable);
        int frameCount = getFrameCount(animatable);
        if (frameCount <= 1 || step == 0) {return;}
        if (step > 0 && current >= frameCount - 1) {
            if (returnToStart) {
                setFrame(animatable, 0);
            }
            return;
        }
        setFrame(animatable, Math.clamp(current + step, 0, frameCount - 1));
    }
}
