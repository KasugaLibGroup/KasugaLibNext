package lib.kasuga.widget.renderer.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.dom.DomStyleValue;
import lib.kasuga.widget.dom.style.StyleValue;
import lib.kasuga.widget.renderer.ElementRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class ModelElementRenderer implements ElementRenderer {

    protected final Element3D element;
    protected final List<ElementRenderer> children = new ArrayList<>();
    protected final DomStyleValue<Vector3f> translate = new DomStyleValue<>(new Vector3f());
    protected final DomStyleValue<Vector3f> scale = new DomStyleValue<>(new Vector3f(1,1,1));
    protected final DomStyleValue<Vector3f> rotation = new DomStyleValue<>(new Vector3f());
    protected DomStyleValue<RotationMode> rotationMode = new DomStyleValue<>(RotationMode.XYZ);
    protected Transformation preTransform = Transformation.identity();
    protected Transformation postTransform = Transformation.identity();
    protected Transformation finalTransform = Transformation.identity();

    public ModelElementRenderer(Element3D element) {
        this.element = element;
    }

    @Override
    public void addElement(DomElement element, int index) {
        children.add(index, element.getRenderer());
    }

    @Override
    public void removeElement(DomElement element, int index) {
        children.remove(index);
    }

    public void setTransform(Transformation element) {
        this.postTransform = element;
        updateFinalTransformation();
    }

    public void setTranslate(double x, double y, double z) {
        translate.set(x, y, z);
        updatePreTransformation();
    }

    public void setScale(double x, double y, double z) {
        scale.set(x, y, z);
        updatePreTransformation();
    }

    public void setRotation(double x, double y, double z) {
        rotation.set(x, y, z);
        updatePreTransformation();
    }

    public void setRotationMode(RotationMode mode) {
        rotationMode = mode;
        updatePreTransformation();
    }

    public void updatePreTransformation() {
        PoseStack stack = new PoseStack();
        stack.translate(this.translate.x, this.translate.y, this.translate.z);
        Quaterniond quaterniond = new Quaterniond();
        stack.scale(this.scale.x, this.scale.y, this.scale.z);
        this.rotationMode.rotate(quaterniond, new Vec3(this.rotation.x, this.rotation.y, this.rotation.z));
        stack.mulPose(new Quaternionf(quaterniond));
        this.preTransform = new Transformation(stack.last().pose());
        updateFinalTransformation();
    }

    public void updateFinalTransformation() {
        finalTransform = preTransform.compose(postTransform);
    }

    @Override
    public void render(RenderContext context) {
        context.pushPose();
        context.pose().pushTransformation(finalTransform); // @TODO: AnimationEngine
        this.element.renderSelf(context);
        for (ElementRenderer child : this.children) {
            if(child != null) {
                child.render(context);
            }
        }
        context.popPose();
    }
}
