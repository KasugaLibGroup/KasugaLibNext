package lib.kasuga.uip.modernui;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Opt-in actual ModernUI Fragment + MinecraftSurfaceView integration. */
@EventBusSubscriber(modid = ModernUiProvider.MOD_ID, value = Dist.CLIENT)
public final class ModernModelDemo {
    private static volatile ModelPreviewView view;
    private static boolean opened, finishing, finished;
    private static int frames;
    private static long rotationStart;

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!Boolean.getBoolean("kasuga.modernModelDemo") || finished) return;
        var mc = Minecraft.getInstance();
        mc.setWindowActive(true);
        if (opened || !(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
        opened = true;
        mc.setScreen(MuiModApi.get().createScreen(new DemoFragment()));
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!opened || finished || System.getProperty("kasuga.modelDemo.capture") == null) return;
        try {
            if (++frames > 1200) throw new IllegalStateException("ModernUI model callback or detach cleanup timed out");
            var current = view;
            if (current == null || current.renderedFrames() < 30) return;
            if (rotationStart == 0) {
                rotationStart = current.renderedFrames();
                MuiModApi.postToUiThread(() -> current.setModelView(.8f, .25f, 1));
                return;
            }
            if (current.renderedFrames() < rotationStart + 30) return;
            if (!finishing) {
                var path = output();
                try (var ignored = new GuiRenderScope(); var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                    image.writeToFile(path);
                }
                finishing = true;
                Minecraft.getInstance().setScreen(null);
                return;
            }
            if (current.releasedPreviews() == 0) return;
            finish(null);
        } catch (Exception failure) { finish(failure); }
    }

    private static Path output() throws Exception {
        var path = Path.of(System.getProperty("kasuga.modelDemo.capture")).toAbsolutePath();
        Files.createDirectories(path.getParent());
        return path;
    }

    private static void finish(Exception failure) {
        finished = true;
        try {
            Files.writeString(output().resolveSibling("model-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(
                    Map.of("toolkit", "modernui", "renderedFrames", view == null ? 0 : view.renderedFrames(),
                            "releasedPreviews", view == null ? 0 : view.releasedPreviews(), "passed", failure == null,
                            "failure", failure == null ? "" : failure.toString())));
            if (failure != null) LogUtils.getLogger().error("MODERN_MODEL_SMOKE_FAIL", failure);
        } catch (Exception report) { LogUtils.getLogger().error("Modern model report failed", report); }
        Minecraft.getInstance().stop();
    }

    public static final class DemoFragment extends Fragment {
        @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, DataSet savedState) {
            var root = new LinearLayout(requireContext());
            root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            var title = new TextView(requireContext());
            title.setText("model_loader / ModernUI / 独立模型预览");
            title.setTextSize(20);
            title.setGravity(Gravity.CENTER);
            root.addView(title);
            view = new ModelPreviewView(requireContext(), ResourceLocation.parse("kasuga_lib:models/gltf/maribel.glb"), null);
            root.addView(view, new LinearLayout.LayoutParams(root.dp(360), root.dp(260)));
            return root;
        }
    }
}
