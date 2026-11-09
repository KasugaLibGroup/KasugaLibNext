package test.kasuga.create.demo.preview;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.create.client.model.CreateModelSources;
import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Fresh-client CPU/submit benchmark. Readbacks happen after measured frames, never inside the timer. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CreateSchematicPerformanceSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testSchematicPerformance");
    private static final String[] NAMES = {"assembled", "layers-20-30", "exploded-20-30", "exploded-all"};
    private static final LayerLayout[] LAYOUTS = {LayerLayout.ASSEMBLED, LayerLayout.ASSEMBLED.range(20,30),
            LayerLayout.ASSEMBLED.range(20,30).separation(.2), LayerLayout.ASSEMBLED.separation(.2)};
    private static final LinkedHashMap<String, Object> REPORT = new LinkedHashMap<>();
    private static final ArrayList<Long> RENDER = new ArrayList<>(), SNAPSHOT = new ArrayList<>(), ALLOCATED = new ArrayList<>();
    private static final long START = System.nanoTime();
    private static final Path OUTPUT = Path.of(System.getProperty("kasuga.schematicPerformance.output", "build/schematic-performance"));
    private static GuiModelPreview preview;
    private static jdk.jfr.Recording recording;
    private static final com.sun.management.ThreadMXBean THREAD = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static long snapshotNanos;
    private static int stage, frames, warmup;
    private static boolean complete, finished;
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        if (System.nanoTime() - START > 600_000_000_000L) { finish(new IllegalStateException("Performance timeout " + stage)); return; }
        var mc = Minecraft.getInstance();
        if (preview != null || mc.level == null || mc.player == null || mc.getOverlay() != null || ++warmup < 40) return;
        try {
            Files.createDirectories(OUTPUT);
            recording = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")); recording.start();
            long begin = System.nanoTime();
            var source = CreateModelSources.schematic(mc.level, Path.of(System.getProperty("kasuga.minecraftPreview.schematic")));
            REPORT.put("loadMs", (System.nanoTime()-begin)/1e6);
            preview = new GuiModelPreview(new MinecraftModelSource() {
                @Override public Snapshot snapshot(LayerLayout layout, float partialTick) {
                    long start = System.nanoTime();
                    try { return source.snapshot(layout, partialTick); } finally { snapshotNanos = System.nanoTime() - start; }
                }
                @Override public void close() { source.close(); }
            });
            mc.options.pauseOnLostFocus = false; mc.setWindowActive(true);
            mc.setScreen(new Screen(Component.literal("Factory performance")) {
                @Override public void render(GuiGraphics graphics, int x, int y, float partial) {
                    graphics.fill(0,0,width,height,0xFF121B27);
                    long allocated = THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId()), start = System.nanoTime();
                    boolean rendered = preview.render(graphics,8,30,width-16,Math.max(1,height-72),partial);
                    long nanos = System.nanoTime()-start;
                    allocated = THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;
                    if (!rendered) throw new IllegalStateException(preview.failure());
                    if (frames == 0) REPORT.put(NAMES[stage]+"-firstMs",nanos/1e6);
                    if (frames++ >= 3) { RENDER.add(nanos); SNAPSHOT.add(snapshotNanos); ALLOCATED.add(allocated); }
                    complete = RENDER.size() >= Integer.getInteger("kasuga.schematicPerformance.samples",12);
                    graphics.drawString(font,NAMES[stage],12,12,0xFFFFFFFF);
                }
                @Override public void renderBackground(GuiGraphics g,int x,int y,float p) {}
                @Override public boolean isPauseScreen() { return false; }
            });
        } catch (Exception failure) { finish(failure); }
    }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || !complete) return;
        try {
            REPORT.put(NAMES[stage],java.util.Map.of("render",stats(RENDER),"snapshot",stats(SNAPSHOT),"allocatedBytes",stats(ALLOCATED)));
            try (var scope = new GuiRenderScope(); var image = net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                image.writeToFile(OUTPUT.resolve(NAMES[stage]+".png"));
            }
            LogUtils.getLogger().info("SCHEMATIC_PERFORMANCE {} {}",NAMES[stage],REPORT.get(NAMES[stage]));
            if (++stage == NAMES.length) { finish(null); return; }
            preview.setLayers(LAYOUTS[stage]); frames=0; complete=false; RENDER.clear();SNAPSHOT.clear();ALLOCATED.clear();
        } catch (Exception failure) { finish(failure); }
    }
    private static Object stats(ArrayList<Long> samples) {
        var ordered=samples.stream().mapToLong(Long::longValue).sorted().toArray();
        return java.util.Map.of("samples",ordered.length,"mean",java.util.Arrays.stream(ordered).average().orElse(0),
                "median",ordered[ordered.length/2],"p95",ordered[Math.min(ordered.length-1,(int)Math.ceil(ordered.length*.95)-1)]);
    }
    private static void finish(Exception failure) {
        if (finished) return;finished=true;
        try {
            if (recording!=null) { recording.stop();recording.dump(OUTPUT.resolve("factory.jfr"));recording.close(); }
            if (preview!=null) preview.close();
            REPORT.put("passed",failure==null);REPORT.put("failure",failure==null?"":failure.toString());
            REPORT.put("renderer",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
            Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure==null) LogUtils.getLogger().info("SCHEMATIC_PERFORMANCE_PASS {}",OUTPUT);
            else LogUtils.getLogger().error("SCHEMATIC_PERFORMANCE_FAIL",failure);
        } catch (Exception error) { LogUtils.getLogger().error("Cannot write performance report",error); }
        finally { Minecraft.getInstance().stop(); }
    }
}
