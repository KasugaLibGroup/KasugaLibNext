package lib.kasuga.rendering.models.uml.structure.material.animators;

public class LoopAnimator implements Animator {

    public static LoopAnimator INSTANCE = new LoopAnimator();

    @Override
    public void nextFrame(Animatable animatable) {
        int frameCount = getFrameCount(animatable);
        animatable.setCurrentFrame((animatable.getCurrentFrame() + 1) % frameCount);
    }

    @Override
    public void previousFrame(Animatable animatable) {
        int frameCount = getFrameCount(animatable);
        animatable.setCurrentFrame((animatable.getCurrentFrame() - 1 + frameCount) % frameCount);
    }

    @Override
    public void stepFrame(Animatable animatable, int step) {
        int frameCount = getFrameCount(animatable);
        animatable.setCurrentFrame((animatable.getCurrentFrame() + step + frameCount) % frameCount);
    }
}
