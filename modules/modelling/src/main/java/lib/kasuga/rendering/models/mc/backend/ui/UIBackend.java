package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.core.rendering.IBlurControl;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.util.RayTracingHelper;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.Input;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(value = Dist.CLIENT)
public class UIBackend {

    public static GuiGraphics constructGuiGraphics(PoseStack stack) {
        return new GuiGraphics(
                Minecraft.getInstance(),
                Minecraft.getInstance().renderBuffers().bufferSource()
        );
    }

    public static GuiGraphics constructGuiGraphics() {
        return constructGuiGraphics(new PoseStack());
    }

    public static void suppressZAxis(PoseStack stack) {
        stack.scale(1f, 1f, .1f / (GuiGraphics.MAX_GUI_Z - GuiGraphics.MIN_GUI_Z));
    }

    @Getter
    private final List<Renderable> renderables;

    @Getter
    private final Map<GuiEventListener, UIInstanceData> interactData;

    protected UIInstanceData currentCallbackData;

    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Pre event) {
        if (Minecraft.getInstance().screen != null) return;
        if (Constants.UI_BACKEND.currentCallbackData == null) return;
        Constants.UI_BACKEND.currentCallbackData.operate(Minecraft.getInstance().player, (op, dat) -> {
            if (event.getAction() == 1) {
                dat.mouseClicked(Constants.UI_BACKEND.currentCallbackData.getListener(), event.getButton());
            } else {
                dat.mouseReleased(Constants.UI_BACKEND.currentCallbackData.getListener(), event.getButton());
            }
        });
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (Minecraft.getInstance().screen != null) return;
        if (Constants.UI_BACKEND.currentCallbackData == null) return;
        Constants.UI_BACKEND.currentCallbackData.operate(Minecraft.getInstance().player, (op, dat) -> {
            dat.mouseScrolled(Constants.UI_BACKEND.currentCallbackData.getListener(), event.getScrollDeltaX(), event.getScrollDeltaY());
        });
        event.setCanceled(true);
    }

    public UIBackend() {
        this.renderables = new ArrayList<>();
        this.interactData = new HashMap<>();
        currentCallbackData = null;
    }

    public void addRenderable(Renderable renderable) {
        this.renderables.add(renderable);
        if (renderable instanceof GuiEventListener listener) {
            this.getInteractData().put(listener, new UIInstanceData(listener));
        }
    }

    public void removeRenderable(Renderable renderable) {
        this.renderables.remove(renderable);
        if (renderable instanceof GuiEventListener listener) {
            this.getInteractData().remove(listener);
        }
    }

    public boolean containsRenderable(Renderable renderable) {
        return this.renderables.contains(renderable);
    }

    public boolean containsListener(Renderable renderable) {
        return this.interactData.containsKey(renderable);
    }

    public int renderableSize() {
        return this.renderables.size();
    }

    public int listenerSize() {
        return this.interactData.size();
    }

    public void renderAllUis(GuiGraphics gui, float partialTicks) {
        float minDistance = Float.MAX_VALUE;
        Pair<Vector2f, Float> posAndDist = null;
        GuiEventListener currentListener = null;
        UIInstanceData lastCallbackData = currentCallbackData;
        UIInstanceData currentData = null;
        for (Renderable renderable : this.renderables) {
            UIInstanceData data = null;
            posAndDist = renderRenderable(gui, partialTicks, renderable, data);
            if (renderable instanceof GuiEventListener listener) {
                 data = interactData.computeIfAbsent(listener,
                        k -> new UIInstanceData(listener));
                 if (posAndDist.getSecond() < minDistance) {
                     minDistance = posAndDist.getSecond();
                     currentListener = listener;
                     currentData = data;
                 }
            }
        }
        if (currentListener != null) {
            currentCallbackData = currentData;
            if (lastCallbackData != currentData) {
                if (lastCallbackData != null) {
                    lastCallbackData.removeFocused(Minecraft.getInstance().player);
                }
            }
            currentData.setFocused(Minecraft.getInstance().player, posAndDist.getFirst());
        } else if (currentCallbackData != null) {
            currentCallbackData.removeFocused(Minecraft.getInstance().player);
            currentCallbackData = null;
        }
    }

    public Pair<Vector2f, Float> renderRenderable(GuiGraphics guiGraphics, float partialTicks,
                                   Renderable renderable, @Nullable UIInstanceData interactions) {
        Vector2i size = getRenderableSize(renderable);
        Screen scr = renderable instanceof Screen ? (Screen) renderable : null;
        if (scr != null) {
            ((IBlurControl) scr).ksg$SetBlurEnabled(false);
        }
        float distance = Float.MAX_VALUE;
        guiGraphics.pose().pushPose();
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 pos = camera.getPosition();
        guiGraphics.pose().translate(- pos.x(), - pos.y(), - pos.z());
        guiGraphics.pose().scale(1f, -1f, 1f);
        boolean mouseOutOfRange = false;
        Vector2f mousePos = new Vector2f();
        if (size == null) {
            renderable.render(guiGraphics, -1, -1, partialTicks);
        } else {
            int mouseX = -1, mouseY = -1;
            PoseStack pose = guiGraphics.pose();
            pose.scale(1f / (float) guiGraphics.guiHeight(), 1f / (float) guiGraphics.guiHeight(), 1f);
            pose.translate(-0.5f, -0.5f, 0);
            suppressZAxis(pose);
            Vector3f rayOrigin = new Vector3f(), rayDirection = new Vector3f(), dstHitPoint = new Vector3f();
            RayTracingHelper.getRayFromCamera(rayOrigin, rayDirection);
            rayOrigin.add(0f, 0.05f, 0f);
            distance = RayTracingHelper.intersectRayWithPlane(
                    rayOrigin, rayDirection,
                    pose.last().pose(), pose.last().normal(),
                    dstHitPoint, true
            );
            if (distance < 5) {
                Matrix4f inv = new Matrix4f(pose.last().pose()).invert();
                inv.transformPosition(dstHitPoint);
                mouseX = Math.round(dstHitPoint.x - 0.5f * size.x);
                mouseY = Math.round(dstHitPoint.y - 0.5f * size.y);
            }
            mouseOutOfRange =
                    mouseX < 0 || mouseY < 0 ||
                    mouseX >= size.x() || mouseY >= size.y();
            mousePos.set(mouseX, mouseY);
            renderable.render(guiGraphics, mouseX, mouseY, partialTicks);
        }
        if (scr != null) {
            ((IBlurControl) scr).ksg$SetBlurEnabled(true);
        }
        guiGraphics.pose().popPose();
        return Pair.of(mousePos, mouseOutOfRange ? Float.MAX_VALUE : distance);
    }

    public static Vector2i getRenderableSize(Renderable renderable) {
        if (renderable instanceof Screen screen) {
            return new Vector2i(screen.width, screen.height);
        } else if (renderable instanceof LayoutElement element) {
            return new Vector2i(element.getWidth(), element.getHeight());
        } else {
            return null;
        }
    }

    public void mouseMoved(Entity operator, long window, double xPos, double yPos) {
        if (currentCallbackData == null) return;
        currentCallbackData.operate(operator, (op, dat) -> {
            dat.updatePosition(new Vector2f((float) xPos, (float) yPos));
        });
    }

    public void mouseClicked(Entity operator, long window, int button, int action, int mods) {
        boolean flag = action == 1;
        if (Minecraft.ON_OSX && button == 0) {
            if (flag) {
                if ((mods & 2) == 2) {
                    button = 1;
                }
            }
        }
        final int fb = button;

        currentCallbackData.operate(operator, (op, dat) -> {
            if (flag) {
                dat.mouseClicked(currentCallbackData.getListener(), fb);
            } else {
                dat.mouseReleased(currentCallbackData.getListener(), fb);
            }
        });
    }

    public void mouseScrolled(Entity operator, long window, double xOffset, double yOffset) {
        if (currentCallbackData == null) return;
        currentCallbackData.operate(operator, (op, dat) -> {
            boolean flag = Minecraft.getInstance().options.discreteMouseScroll().get();
            double d0 = Minecraft.getInstance().options.mouseWheelSensitivity().get();
            double d1 = (flag ? Math.signum(xOffset) : xOffset) * d0;
            double d2 = (flag ? Math.signum(yOffset) : yOffset) * d0;
            dat.mouseScrolled(currentCallbackData.getListener(), d1, d2);
        });
    }

    public void mouseDragged(Entity operator, long window, int count, long names) {
        if (currentCallbackData == null) return;
        currentCallbackData.operate(operator, (op, dat) -> {

        });
    }
}
