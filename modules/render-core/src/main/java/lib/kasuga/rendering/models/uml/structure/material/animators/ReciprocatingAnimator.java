package lib.kasuga.rendering.models.uml.structure.material.animators;

import java.util.HashMap;

public class ReciprocatingAnimator implements Animator {

    public static final ReciprocatingAnimator INSTANCE = new ReciprocatingAnimator();

    private final HashMap<Animatable, Boolean> movingDirectionMap;

    public ReciprocatingAnimator() {
        this.movingDirectionMap = new HashMap<>();
    }

    public void registerAnimatable(Animatable animatable) {
        movingDirectionMap.put(animatable, true);
    }

    public void removeAnimatable(Animatable animatable) {
        movingDirectionMap.remove(animatable);
    }

    public boolean isMovingForward(Animatable animatable, boolean onDefault) {
        if (!movingDirectionMap.containsKey(animatable)) {
            movingDirectionMap.put(animatable, onDefault);
        }
        return movingDirectionMap.get(animatable);
    }

    public boolean isMovingBackward(Animatable animatable, boolean onDefault) {
        return !isMovingForward(animatable, onDefault);
    }

    public void changeDirection(Animatable animatable) {
        if (movingDirectionMap.containsKey(animatable)) {
            movingDirectionMap.put(animatable, !movingDirectionMap.get(animatable));
        }
    }

    public void setDirection(Animatable animatable, boolean forward) {
        movingDirectionMap.put(animatable, forward);
    }

    public void moveByDirection(Animatable animatable, int step) {
        int frameCount = getFrameCount(animatable);
        if (frameCount <= 1 || step == 0) return;
        boolean forward = isMovingForward(animatable, true);
        step = forward ? step : -step;
        setFrame(animatable, Math.clamp(getCurrentFrame(animatable) + step, 0, frameCount - 1));
    }

    @Override
    public void nextFrame(Animatable animatable) {
        int currentFrame = getCurrentFrame(animatable);
        int frameCount = getFrameCount(animatable);
        if (frameCount <= 1) return;
        if (isMovingForward(animatable, true)) {
            if (currentFrame + 1 >= frameCount) changeDirection(animatable);
        } else if (currentFrame - 1 < 0) changeDirection(animatable);
        moveByDirection(animatable, 1);
    }

    @Override
    public void previousFrame(Animatable animatable) {
        int currentFrame = getCurrentFrame(animatable);
        int frameCount = getFrameCount(animatable);
        if (frameCount <= 1) return;
        if (isMovingForward(animatable, true)) {
            if (currentFrame - 1 < 0) changeDirection(animatable);
        } else if (currentFrame + 1 >= frameCount) changeDirection(animatable);
        moveByDirection(animatable, -1);
    }

    @Override
    public void stepFrame(Animatable animatable, int step) {
        int currentFrame = getCurrentFrame(animatable);
        int frameCount = getFrameCount(animatable);
        if (frameCount <= 1 || step == 0) return;
        if (currentFrame == 0) {
            setDirection(animatable, true);
        } else if (currentFrame == frameCount - 1) {
            setDirection(animatable, false);
        }
        moveByDirection(animatable, step);
        currentFrame = getCurrentFrame(animatable);
        if (currentFrame == 0) {
            setDirection(animatable, true);
        } else if (currentFrame == frameCount - 1) {
            setDirection(animatable, false);
        }
    }
}
