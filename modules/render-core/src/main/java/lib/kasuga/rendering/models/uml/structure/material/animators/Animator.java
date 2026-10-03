package lib.kasuga.rendering.models.uml.structure.material.animators;

public interface Animator {

    default void reset(Animatable animatable) {
        animatable.setCurrentFrame(0);
    }

    default int getFrameCount(Animatable animatable) {
        return animatable.getSprites().size();
    }

    default int getCurrentFrame(Animatable animatable) {
        return animatable.getCurrentFrame();
    }

    void nextFrame(Animatable animatable);

    void previousFrame(Animatable animatable);

    void stepFrame(Animatable animatable, int step);

    default void setFrame(Animatable animatable, int frame) {
        int frameCount = getFrameCount(animatable);
        animatable.setCurrentFrame(Math.clamp(0, frameCount - 1, frame));
    }

    default void removeAnimatable(Animatable animatable) {}
}
