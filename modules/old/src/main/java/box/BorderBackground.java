package lib.kasuga.widget.renderer.ui.box;

import lombok.Getter;

public class BorderBackground {
    @Getter
    protected int color;

    @Getter
    protected TextureImage texture;

    public void useTexture(TextureImage image) {
        this.texture = image;
    }

    public void useColor(int color) {
        this.color = color;
        this.texture = null;
    }
}
