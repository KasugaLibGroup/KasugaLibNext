package lib.kasuga.rendering.models.uml.structure.material.animators;

import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.SpriteSet;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteData;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteSetData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;

import java.util.List;

public interface Animatable {

    List<SpriteSet> getSprites();

    int getCurrentFrame();

    void setCurrentFrame(int frame);

    default SpriteSet getCurrentSprite() {
        List<SpriteSet> sprites = getSprites();
        if (sprites.isEmpty()) {
            throw new IllegalStateException("Animatable must have at least one sprite");
        }
        int currentFrame = getCurrentFrame();
        if (currentFrame < 0 || currentFrame >= sprites.size()) {
            return sprites.getFirst();
        }
        return sprites.get(currentFrame);
    }
}
