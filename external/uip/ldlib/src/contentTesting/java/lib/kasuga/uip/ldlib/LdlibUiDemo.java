package lib.kasuga.uip.ldlib;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scroller;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Switch;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.platform.NativeImage;
import dev.vfyjxf.taffy.style.FlexDirection;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.ui.WorldUiInputScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Development-only gallery of LDLib2's own controls and built-in stylesheets. */
@EventBusSubscriber(modid = LdLibUiProvider.MOD_ID, value = Dist.CLIENT)
public final class LdlibUiDemo {
    private static boolean opened;
    private static boolean captured;
    private static int renderedFrames;
    private static int worldWarmup;
    static LdElement worldPanel;
    static Contents worldContents;
    static Matrix4f worldTransform;

    private LdlibUiDemo() {}

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ksglib")
                .then(Commands.literal("debug").then(Commands.literal("ldlib_ui").executes(context -> {
                    // ChatScreen closes itself after executing a command. Open on the next client turn.
                    Minecraft.getInstance().tell(LdlibUiDemo::open);
                    return 1;
                }).then(Commands.literal("world").executes(context -> {
                    Minecraft.getInstance().tell(LdlibUiDemo::openWorld);
                    return 1;
                })).then(Commands.literal("cursor").executes(context -> {
                    Minecraft.getInstance().tell(() -> {
                        var mc = Minecraft.getInstance();
                        mc.setScreen(null);
                        if (worldPanel != null && !worldPanel.isClosed()) Constants.UI_BACKEND.openPointer();
                    });
                    return 1;
                })).then(Commands.literal("stop").executes(context -> {
                    Minecraft.getInstance().tell(LdlibUiDemo::closeWorld);
                    return 1;
                })))));
    }

    private static void open() {
        openScreen(new Contents());
    }

    static void openScreen(Contents contents) {
        Minecraft.getInstance().setScreen(new DemoScreen(contents));
    }

    private static void openWorld() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || Constants.UI_BACKEND == null) return;
        closeWorld();
        worldContents = new Contents();
        if (Boolean.getBoolean("kasuga.ldlibWorldSmoke")) worldContents.ui.setTickWhileRending(true);
        var camera = mc.gameRenderer.getMainCamera();
        var center = camera.getPosition().add(camera.getLookVector().x * 2.5,
                camera.getLookVector().y * 2.5, camera.getLookVector().z * 2.5);
        worldTransform = new Matrix4f().translation((float) center.x, (float) center.y, (float) center.z)
                .rotate(camera.rotation()).scale(.006f, -.006f, 1).translate(-205, -135, 0);
        worldPanel = LdElement.attach(Constants.UI_BACKEND, worldContents.ui, 410, 270, worldTransform);
        mc.setScreen(null);
        Constants.UI_BACKEND.openPointer();
    }

    static void closeWorld() {
        if (worldPanel != null) worldPanel.close();
        worldPanel = null;
        worldContents = null;
        worldTransform = null;
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof WorldUiInputScreen && Constants.UI_BACKEND.renderableSize() == 0) mc.setScreen(null);
    }

    @SubscribeEvent
    public static void worldState(ClientTickEvent.Post event) {
        if (worldPanel != null && worldPanel.isClosed()) closeWorld();
    }

    @SubscribeEvent
    public static void autoOpen(RenderFrameEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (Boolean.getBoolean("kasuga.ldlibDemo.world")) {
            if (System.getProperty("kasuga.ldlibDemo.capture") != null) mc.setWindowActive(true);
            if (!opened && mc.level != null && mc.player != null && mc.getOverlay() == null && ++worldWarmup >= 90) {
                opened = true;
                openWorld();
            }
            return;
        }
        if (!Boolean.getBoolean("kasuga.ldlibDemo") || opened) return;
        if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
        opened = true;
        mc.options.guiScale().set(2);
        mc.options.pauseOnLostFocus = false;
        mc.resizeDisplay();
        open();
    }

    @SubscribeEvent
    public static void capture(RenderFrameEvent.Post event) {
        String destination = System.getProperty("kasuga.ldlibDemo.capture");
        if (destination == null || captured || !opened) return;
        if (Boolean.getBoolean("kasuga.ldlibWorldSmoke")) return;
        var mc = Minecraft.getInstance();
        if (worldPanel != null) {
            if (mc.getOverlay() != null || ++renderedFrames < 30) return;
            captured = true;
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                var path = Path.of(destination).toAbsolutePath();
                Files.createDirectories(path.getParent());
                image.writeToFile(path);
                LogUtils.getLogger().info("LDLIB_WORLD_UI_DEMO_CAPTURED {}", path);
            } catch (Exception failure) {
                LogUtils.getLogger().error("LDLIB_WORLD_UI_DEMO_CAPTURE_FAILED", failure);
            } finally {
                closeWorld();
                mc.stop();
            }
            return;
        }
        if (!(mc.screen instanceof DemoScreen screen) || mc.getOverlay() != null || ++renderedFrames < 30) return;
        captured = true;
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            var path = Path.of(destination).toAbsolutePath();
            Files.createDirectories(path.getParent());
            image.writeToFile(path.resolveSibling("full-" + path.getFileName()));
            // Keep the preview readable in chat, using the actual framebuffer pixels around the panel.
            var ui = screen.modularUI;
            float scale = (float) image.getWidth() / mc.getWindow().getGuiScaledWidth();
            int x = Math.max(0, Math.round((ui.getLeftPos() - 10) * scale));
            int y = Math.max(0, Math.round((ui.getTopPos() - 10) * scale));
            int width = Math.min(image.getWidth() - x, Math.round((ui.getWidth() + 20) * scale));
            int height = Math.min(image.getHeight() - y, Math.round((ui.getHeight() + 20) * scale));
            try (var panel = new NativeImage(width, height, false)) {
                image.resizeSubRectTo(x, y, width, height, panel);
                panel.writeToFile(path);
            }
            LogUtils.getLogger().info("LDLIB_UI_DEMO_CAPTURED {}", path);
        } catch (Exception failure) {
            LogUtils.getLogger().error("LDLIB_UI_DEMO_CAPTURE_FAILED", failure);
        } finally {
            mc.stop();
        }
    }

    static final class Contents {
        private final UIElement root = new UIElement() {
            @Override public void drawBackgroundOverlay(GUIContext context) {
                super.drawBackgroundOverlay(context);
                LdlibWorldUiSmokeTest.sampleTextBackground(context, Contents.this);
            }
        }.addClass("panel_bg")
                .layout(layout -> layout.width(390).paddingAll(12).gapAll(9));
        private final Label status = new Label();
        private final Label themeName = new Label();
        final ModularUI ui;
        final Button counter;
        final TextField text;
        final Switch toggle;
        final ProgressBar progress;
        final Scroller slider;
        final Selector<String> selector;
        int clicks;
        String theme = "MODERN";

        private Contents() {
            var manager = StylesheetManager.INSTANCE;
            if (!manager.hasStylesheet(StylesheetManager.MODERN)) {
                throw new IllegalStateException("LDLib2 built-in MODERN stylesheet was not loaded");
            }

            root.addChildren(
                    new Label().setText("LDLib2 / Component studio", false)
                            .textStyle(style -> style.fontSize(16).textColor(0xFFE2EAFE)).setId("demo-title"),
                    themeName.setText("Built-in theme: MODERN", false)
                            .textStyle(style -> style.fontSize(8).textColor(0xFF9CB5D9))
            );

            var themes = row();
            themes.addChildren(themeButton("MODERN", StylesheetManager.MODERN),
                    themeButton("MC", StylesheetManager.MC), themeButton("GDP", StylesheetManager.GDP));
            root.addChild(themes);

            var left = column();
            counter = new Button().setText("Click me  /  0", false);
            counter.setOnClick(event -> {
                counter.setText("Click me  /  " + ++clicks, false);
                status.setText("Button clicked " + clicks + " times", false);
            });
            text = new TextField().setText("Hello, KasugaLib!")
                    .textFieldStyle(style -> style.font(ResourceLocation.withDefaultNamespace("default")));
            text.setTextResponder(value -> status.setText("Text: " + value, false));
            left.addChildren(section("BUTTONS & INPUT"), counter,
                    new Button().setText("Disabled button", false).disabled(),
                    section("EDITABLE TEXT"), text,
                    new Label().setText("中文显示 / Aa 123", false)
                            .textStyle(style -> style.font(ResourceLocation.withDefaultNamespace("default"))
                                    .textColor(0xFF9DD8CE)));

            var right = column();
            var switchLabel = new Label().setText("Enabled", false);
            toggle = new Switch().setOn(true);
            toggle.setOnSwitchChanged(on -> switchLabel.setText(on ? "Enabled" : "Disabled", false));
            progress = new ProgressBar().setValue(.65f)
                    .label(label -> label.setText("65%", false));
            slider = new Scroller.Horizontal().setValue(.65f);
            slider.setOnValueChanged(value -> {
                progress.setValue(value);
                progress.label(label -> label.setText(Math.round(value * 100) + "%", false));
            });
            selector = new Selector<String>().setCandidates(List.of("Balanced", "Quality", "Performance"))
                    .setSelected("Balanced");
            selector.setOnValueChanged(value -> status.setText("Preset: " + value, false));
            right.addChildren(section("SWITCH & PROGRESS"), row().addChildren(toggle, switchLabel),
                    slider, progress, section("SELECTOR"), selector);
            root.addChild(row().addChildren(left, right));

            root.addChildren(status.setText("Ready. Try the controls above.", false)
                            .textStyle(style -> style.fontSize(8)
                                    .font(ResourceLocation.withDefaultNamespace("default"))),
                    new Label().setText("Esc to close  /  Switch themes to compare", false)
                            .textStyle(style -> style.fontSize(7).textColor(0xFF9CB5D9)));
            ui = ModularUI.of(UI.of(root, manager.getStylesheet(StylesheetManager.MODERN)));
        }

        private UIElement themeButton(String name, ResourceLocation location) {
            return new Button().setText(name, false).setOnClick(event -> {
                var engine = ui.getStyleEngine();
                engine.clearAllStylesheets();
                engine.addStylesheet(StylesheetManager.INSTANCE.getStylesheetSafe(location));
                theme = name;
                themeName.setText("Built-in theme: " + name, false);
            }).setId("theme-" + name).layout(layout -> layout.flex(1).height(22));
        }

        private static UIElement row() {
            return new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW).gapAll(10));
        }

        private static UIElement column() {
            return new UIElement().layout(layout -> layout.flex(1).gapAll(7));
        }

        private static Label section(String text) {
            var label = new Label();
            label.setText(text, false).textStyle(style -> style.fontSize(7).textColor(0xFF9CB5D9));
            return label;
        }
    }

    private static final class DemoScreen extends ModularUIScreen {
        private DemoScreen(Contents contents) {
            super(contents.ui, Component.literal("LDLib2 built-in theme demo"));
        }

        @Override
        public void tick() {
            super.tick();
            modularUI.tick();
        }

        @Override
        public void removed() {
            modularUI.onRemoved();
            modularUI.setScreen(null);
            super.removed();
        }

        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xFF10151C);
        }
    }
}
