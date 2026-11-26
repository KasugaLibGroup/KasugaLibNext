package lib.kasuga.widget.renderer.ui.box;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.renderer.ui.UiElementRenderer;
import lib.kasuga.widget.renderer.ui.style.enumaration.BoxSizing;
import lombok.Getter;
import net.minecraft.world.phys.Vec2;

public class MainRenderBox extends RenderBox {
    @Getter
    protected int zIndex = 0;
    private final BorderRenderBox borderBox;
    private final ContentRenderBox contentBox;
    private final BackgroundRenderBox backgroundBox;
    private final ScrollRenderBox scrollRenderBox;
    private final UiElementRenderer mainRenderer;

    public MainRenderBox(
            UiElementRenderer renderer, BorderRenderBox borderRenderBox,
            ContentRenderBox contentRenderBox,
            BackgroundRenderBox backgroundRenderBox,
            ScrollRenderBox scrollRenderBox
    ) {
        this.borderBox = borderRenderBox;
        this.contentBox = contentRenderBox;
        this.backgroundBox = backgroundRenderBox;
        this.scrollRenderBox = scrollRenderBox;
        this.mainRenderer = renderer;
    }

    @Override
    public double render(RenderContext context) {
        context.pushPose();
        boolean preparation = scrollRenderBox.prepare(context);
        double zDepth = 0.0D;
        double scrollZDepth = scrollRenderBox.render(context);
        context.pose().translate(0, 0, scrollZDepth);
        double borderZDepth = borderBox.render(context);
        context.pose().translate(0, 0, borderZDepth);
        double thisContentZDepth = mainRenderer.renderSelf(context);
        context.pose().translate(0, 0, thisContentZDepth);
        double contentZDepth =  contentBox.render(context);
        context.pose().translate(0, 0, contentZDepth);
        double backgroundZDepth =  backgroundBox.render(context);
        zDepth += scrollZDepth + borderZDepth + contentZDepth + backgroundZDepth + thisContentZDepth;
        if(preparation) scrollRenderBox.end(context);
        context.popPose();
        return zDepth;
    }

    @Getter
    BoxSizing boxSizing = BoxSizing.CONTENT_BOX;

    protected Vec2 originalOrigin = origin;

    protected Vec2 originalSize = size;

    @Override
    public void setOrigin(Vec2 originPoint) {
        originalOrigin = originPoint;

        super.setOrigin(boxSizing == BoxSizing.BORDER_BOX
                ? borderBox.borderSize.addOrigin(originPoint)
                : originPoint
        );

        this.updateBoxPosition();
    }

    @Override
    public void setSize(Vec2 size) {
        originalSize = size;

        super.setSize(boxSizing == BoxSizing.BORDER_BOX
                ? borderBox.borderSize.addSize(size)
                : size
        );

        this.updateBoxPosition();
    }

    public void updateBoxPosition() {
        Vec2 borderOrigin = borderBox.borderSize.addOrigin(originalOrigin);
        Vec2 borderSize = borderBox.borderSize.addSize(originalSize);

        contentBox.setOrigin(originalOrigin);
        contentBox.setSize(originalSize);

        borderBox.setOrigin(borderOrigin);
        borderBox.setSize(borderSize);

        backgroundBox.setOrigin(borderOrigin);
        backgroundBox.setSize(borderSize);

        scrollRenderBox.setOrigin(borderOrigin);
        scrollRenderBox.setSize(borderSize);
    }
}
