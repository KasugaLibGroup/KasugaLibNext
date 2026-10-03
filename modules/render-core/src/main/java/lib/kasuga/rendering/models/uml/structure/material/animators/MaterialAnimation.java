package lib.kasuga.rendering.models.uml.structure.material.animators;

import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.SpriteSet;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
public class MaterialAnimation implements Animatable, AutoCloseable {

    private final Animator animator;
    private final Material material;

    private int currentFrame;

    public MaterialAnimation(Animator animator, Material material, int currentFrame) {
        this.animator = animator;
        this.material = material;
        this.currentFrame = Math.clamp(currentFrame, 0, getFrameCount() - 1);
    }

    public int getFrameCount() {
        return material.getSprites().size();
    }

    @Override
    public List<SpriteSet> getSprites() {
        return material.getSprites();
    }

    @Override
    public int getCurrentFrame() {
        return currentFrame;
    }

    @Override
    public void setCurrentFrame(int frame) {
        currentFrame = Math.clamp(frame, 0, getFrameCount() - 1);
    }

    @Override
    public void close() throws Exception {
        animator.removeAnimatable(this);
    }
}
