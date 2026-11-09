package test.kasuga.create.demo.preview;

import com.google.gson.GsonBuilder;
import lib.kasuga.KasugaLib;
import lib.kasuga.create.client.model.CreateModelSources;
import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import lib.kasuga.rendering.models.mc.scene.*;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Same-frame reference-vs-cached native geometry; no simulated image or whole-frame freeze. */
@EventBusSubscriber(modid=KasugaLib.MODID,value=Dist.CLIENT)
public final class CreateGeometryCacheSmoke {
    private static final boolean ENABLED=Boolean.getBoolean("kasuga.testGeometryCache");
    private static GuiModelPreview cached,reference;
    private static BlockModelSource source;
    private static int stage,frames,warmup;
    private static boolean finished,ready;
    private static final LinkedHashMap<String,Object> REPORT=new LinkedHashMap<>();
    private static long builds;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED||finished) return;
        var mc=Minecraft.getInstance();
        if (cached!=null||mc.level==null||mc.player==null||mc.getOverlay()!=null||++warmup<40) return;
        try {
            var path=Path.of(System.getProperty("kasuga.minecraftPreview.schematic"));
            source=(BlockModelSource)CreateModelSources.schematic(mc.level,path);
            cached=new GuiModelPreview(wrap(source,Boolean.getBoolean("kasuga.testGeometryCache.disable")));
            reference=new GuiModelPreview(wrap(CreateModelSources.schematic(mc.level,path),true));
            mc.setWindowActive(true);mc.options.pauseOnLostFocus=false;
            mc.setScreen(new Screen(Component.literal("Geometry cache pixel regression")) {
                @Override public void render(GuiGraphics g,int x,int y,float partial) {
                    g.fill(0,0,width,height,0xFF121B27);
                    check(reference.render(g,8,30,width-16,height-72,.5f),reference.failure());
                    check(cached.render(g,8,30,width-16,height-72,.5f),cached.failure());
                    ready=++frames>=3;
                }
                @Override public void renderBackground(GuiGraphics g,int x,int y,float p) {}
                @Override public boolean isPauseScreen() {return false;}
            });
        } catch(Exception failure) {finish(failure);}
    }
    private static MinecraftModelSource wrap(MinecraftModelSource source,boolean bypass) {
        return new MinecraftModelSource() {
            @Override public Snapshot snapshot(LayerLayout layout,float partial) {
                var snapshot=source.snapshot(layout,partial);
                if(!bypass||snapshot==null)return snapshot;
                return new Snapshot(snapshot.bounds(),snapshot.parts().stream().map(part ->new Part(part.id(),part.layer(),part.bounds(),context ->
                        part.renderer().render(new Context(context.pose(),type->context.buffers().getBuffer(type),context.projection(),
                                context.partialTick(),context.packedLight(),context.modelView())),part.picker())).toList());
            }
            @Override public void close(){source.close();}
        };
    }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if(!ENABLED||finished||!ready||Minecraft.getInstance().getOverlay()!=null||reload!=null&&!reload.isDone())return;
        try {
            if(reload!=null)reload.join();
            var a=RgbaReadback.copy(reference.currentFrame());var b=RgbaReadback.copy(cached.currentFrame());
            check(a.length==b.length,"Cache output size changed");
            long different=0,totalDifference=0,max=0;
            for(int i=0;i<a.length;i++) {
                int delta=Math.abs((a[i]&255)-(b[i]&255));totalDifference+=delta;max=Math.max(max,delta);
                if(delta>3)different++;
            }
            REPORT.put("stage-"+stage,java.util.Map.of("channelsBeyond3",different,"maxDelta",max,"meanDelta",(double)totalDifference/a.length,"builds",source.geometryBuilds()));
            check(different < a.length*.0005,"Cached native pixels differ from immediate rendering: "+different+" max="+max);
            check(source.geometryBuilds()>0,"Static native geometry was never cached");
            var output=Minecraft.getInstance().gameDirectory.toPath().resolve("debug/geometry-cache");Files.createDirectories(output);
            try(var image=net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())){image.writeToFile(output.resolve("cache-"+stage+".png"));}
            if(stage==0){builds=source.geometryBuilds();setView(.7f,.35f,1.2f);}
            else if(stage==1){check(source.geometryBuilds()==builds,"Changing the camera rebuilt static geometry");setLayout(LayerLayout.ASSEMBLED.range(20,30));}
            else if(stage==2){builds=source.geometryBuilds();setLayout(LayerLayout.ASSEMBLED.range(20,30).separation(.2));}
            else if(stage==3){check(source.geometryBuilds()==builds,"Explosion spacing rebuilt cached layers");setLayout(LayerLayout.ASSEMBLED.separation(.2));}
            else if(stage==4){
                builds=source.geometryBuilds();
                reload=Minecraft.getInstance().reloadResourcePacks();
            } else {
                check(source.geometryBuilds()>builds,"Resource reload did not rebuild native geometry");
                var objects=gpuObjects();
                check(!objects.isEmpty(),"Cache acceptance recorded no GPU buffers");
                int texture=cached.currentFrame().textureId();cached.close();reference.close();
                for(var object:objects) check(!org.lwjgl.opengl.GL15.glIsBuffer(object),"Native geometry VBO leaked after source close");
                check(!GL11.glIsTexture(texture),"Preview cache retained its color target after close");
                finish(null);return;
            }
            frames=0;ready=false;stage++;
        }catch(Exception failure){finish(failure);}
    }
    private static java.util.List<Integer> gpuObjects() throws Exception {
        var result=new java.util.ArrayList<Integer>();
        var geometry=BlockModelSource.class.getDeclaredField("geometry");geometry.setAccessible(true);
        var layers=CachedBlockGeometry.class.getDeclaredField("layers");layers.setAccessible(true);
        var gpu=CachedBlockGeometry.Layer.class.getDeclaredField("gpu");gpu.setAccessible(true);
        var buffer=com.mojang.blaze3d.vertex.VertexBuffer.class.getDeclaredField("vertexBufferId");buffer.setAccessible(true);
        for(var cache:((java.util.Map<?,?>)geometry.get(source)).values())for(var layer:(java.util.List<?>)layers.get(cache)) {
            Object nativeBuffer=gpu.get(layer);if(nativeBuffer!=null)result.add(buffer.getInt(nativeBuffer));
        }
        return result;
    }
    private static void setView(float yaw,float pitch,float zoom){cached.setView(yaw,pitch,zoom);reference.setView(yaw,pitch,zoom);}
    private static void setLayout(LayerLayout layout){cached.setLayers(layout);reference.setLayers(layout);}
    private static void check(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    private static void finish(Exception failure){
        if(finished)return;finished=true;
        try{
            if(cached!=null)cached.close();if(reference!=null)reference.close();
            REPORT.put("passed",failure==null);REPORT.put("failure",failure==null?"":failure.toString());
            var output=Minecraft.getInstance().gameDirectory.toPath().resolve("debug/geometry-cache/report.json");Files.createDirectories(output.getParent());
            Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if(failure==null)com.mojang.logging.LogUtils.getLogger().info("GEOMETRY_CACHE_SMOKE_PASS {}",output);
            else com.mojang.logging.LogUtils.getLogger().error("GEOMETRY_CACHE_SMOKE_FAIL",failure);
        }catch(Exception error){com.mojang.logging.LogUtils.getLogger().error("Cannot write geometry cache report",error);}
        finally{Minecraft.getInstance().stop();}
    }
}
