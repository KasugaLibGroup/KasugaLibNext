package lib.kasuga.testing.element;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.dom.DomSchema;
import lib.kasuga.widget.dom.style.StyleMap;
import lib.kasuga.widget.dom.stylesheet.Stylesheet;
import lib.kasuga.widget.dom.stylesheet.StylesheetContext;
import lib.kasuga.widget.renderer.model.Element3D;
import lib.kasuga.widget.renderer.model.style.ModelStyles;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

public class ComponentTest {
    DomSchema schema = new DomSchema();

    Element3D firstLayerElement = new Element3D(schema);

    SimpleBlockElement blockElement = new SimpleBlockElement(schema);

    Stylesheet<DomElement> stylesheet = new Stylesheet<>();


    public ComponentTest() {
        firstLayerElement.provideContext(StylesheetContext.STYLESHEET, stylesheet);

        stylesheet.addStyle((p)->true, StyleMap.of(Map.of(
                ModelStyles.TRANSLATE, new Vec3(0,1,0)
        )));

        firstLayerElement.getStyle().notifyUpdate();

        firstLayerElement.addChild(blockElement);

        blockElement.setAttribute(SimpleBlockElement.BLOCK_STATE, Blocks.BEDROCK.defaultBlockState());
    }

    public void render(RenderContext context) {
        this.firstLayerElement.getRenderer().render(context);
    }
}
