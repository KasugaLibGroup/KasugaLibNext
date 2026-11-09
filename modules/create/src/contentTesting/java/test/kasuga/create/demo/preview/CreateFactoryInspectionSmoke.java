package test.kasuga.create.demo.preview;

import com.google.gson.GsonBuilder;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import test.kasuga.modelling.demo.preview.MinecraftPreviewDemo;

import java.nio.file.Files;
import java.util.LinkedHashMap;

/** Acceptance starts from the actual external-blueprint menu entry, rather than a gallery shortcut. */
@EventBusSubscriber(modid=KasugaLib.MODID,value=Dist.CLIENT)
public final class CreateFactoryInspectionSmoke {
    private static final boolean ENABLED=Boolean.getBoolean("kasuga.testFactoryInspection");
    private static final LinkedHashMap<String,Object> REPORT=new LinkedHashMap<>();
    private static final long START=System.nanoTime();
    private static boolean started,finished,returning;
    private static int warmup,stage,wait,returnWait;
    private static long fullPixels;
    private static String screenshot;
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> originalDimension;
    private static net.minecraft.world.phys.Vec3 originalPosition;
    private static int originalMode;
    private static lib.kasuga.scene.SceneModels.Entry factoryEntry;
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if(!ENABLED||finished)return;
        var mc=Minecraft.getInstance();
        if(System.nanoTime()-START>180_000_000_000L){finish(new IllegalStateException("Factory inspection timed out at "+stage));return;}
        if(mc.level==null||mc.player==null||mc.getOverlay()!=null)return;
        try {
            if(!started&&++warmup>=40) {
                originalDimension=mc.level.dimension();originalPosition=mc.player.position();originalMode=mc.gameMode.getPlayerMode().getId();
                mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);mc.options.pauseOnLostFocus=false;mc.options.hideGui=false;mc.setWindowActive(true);
                started=true;MinecraftPreviewDemo.open();
                Button inspect=null;
                for(var child:mc.screen.children())if(child instanceof Button button&&button.getMessage().getString().equals("Inspect"))inspect=button;
                check(inspect!=null,"External factory row has no Inspect entry");
                click(inspect.getX()+inspect.getWidth()/2d,inspect.getY()+10);
                REPORT.put("enteredFromFactoryMenu",true);
            }
            if(returning&&mc.level.dimension().equals(originalDimension)&&++returnWait>=20){
                check(mc.player.position().distanceTo(originalPosition)<1,"Factory inspection did not return the original player position");
                check(mc.gameMode.getPlayerMode().getId()==originalMode,"Factory inspection changed the original player mode");
                REPORT.put("returnedToOrigin",true);finish(null);
            }
        }catch(Exception failure){finish(failure);}
    }
    @SubscribeEvent public static void world(RenderLevelStageEvent event) {
        if(!ENABLED||finished||!started||returning||!CreateModelGallery.active()||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;
        if(++wait<20){if(wait==10)screenshot="factory-inspect-"+stage+".png";return;}
        var mc=Minecraft.getInstance();
        try{
            check(mc.screen!=null,"Factory inspection controls are not open");
            int width=mc.getMainRenderTarget().width,height=mc.getMainRenderTarget().height;
            var depth=MemoryUtil.memAllocFloat(width*height);long pixels=0;
            try(var scope=new GuiRenderScope()){
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,mc.getMainRenderTarget().frameBufferId);
                GL11.glReadPixels(0,0,width,height,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,depth);
                for(int i=0;i<depth.capacity();i++)if(depth.get(i)<.9999f)pixels++;
            }finally{MemoryUtil.memFree(depth);}
            REPORT.put("stage-"+stage,java.util.Map.of("depthPixels",pixels,"width",width,"height",height));
            check(pixels>100,"Factory inspection did not render geometry");
            if(stage==0){
                check(CreateModelGallery.factorySelected(),"Inspect entered the gallery with the wrong target");
                factoryEntry=CreateModelGallery.entry();
                check(CreateModelGallery.layout().separation()==0,"Initial factory inspection has stale layout");
                var hit=CreateModelGallery.inspect().orElseThrow(()->new IllegalStateException("Factory cannot be inspected by the world ray"));
                REPORT.put("inspectedBlock",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(hit.state().getBlock()).toString());
                fullPixels=pixels;click(140,mc.screen.height-20);
                check(CreateModelGallery.layout().firstLayer()==CreateModelGallery.layout().lastLayer(),"Factory slice button did not select a single layer");
            }else if(stage==1){
                check(pixels<fullPixels,"Factory single layer did not reduce displayed geometry");click(24,mc.screen.height-20);
            }else if(stage==2){click(80,mc.screen.height-20);double x=204+(mc.screen.width-220)*.1;click(x,mc.screen.height-20);
            }else if(stage==3){
                check(CreateModelGallery.layout().separation()>.2,"Factory explosion slider did not change the world layout");
                click(230,mc.screen.height-48);
            }else if(stage==4){
                check(Math.abs((double)pixels/fullPixels-1)<.04,"Factory reset did not restore the full model");
                click(16+172*.65,mc.screen.height-48);
            }else if(stage==5){
                check(pixels>fullPixels*1.1,"Factory scale control did not enlarge the model");
                click(230,mc.screen.height-48);click(430,mc.screen.height-48);
                check(!CreateModelGallery.factorySelected()&&CreateModelGallery.entry()!=factoryEntry,"Factory target could not switch to Windmill");
            }else if(stage==6){
                click(530,mc.screen.height-48);
                check(CreateModelGallery.factorySelected()&&CreateModelGallery.entry()==factoryEntry,"Factory target was recreated or not selected after switching back");
            }else if(stage==7){
                check(Math.abs((double)pixels/fullPixels-1)<.04,"Switching back to Factory did not restore the original model");
                click(320,mc.screen.height-48);check(mc.screen==null,"Factory Done did not restore world navigation");
                CreateModelGallery.stop();returning=true;return;
            }
            stage++;wait=0;
        }catch(Exception failure){finish(failure);}
    }
    private static void click(double x,double y){var screen=Minecraft.getInstance().screen;check(screen.mouseClicked(x,y,0),"Factory widget rejected click");screen.mouseReleased(x,y,0);}
    @SubscribeEvent public static void after(RenderFrameEvent.Post event){
        if(!ENABLED||finished||screenshot==null)return;
        try(var scope=new GuiRenderScope();var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())){
            var output=Minecraft.getInstance().gameDirectory.toPath().resolve("debug/factory-inspection");Files.createDirectories(output);image.writeToFile(output.resolve(screenshot));
        }catch(Exception failure){finish(failure);}screenshot=null;
    }
    private static void check(boolean value,String text){if(!value)throw new IllegalStateException(text);}
    private static void finish(Exception failure){
        if(finished)return;finished=true;
        try{
            CreateModelGallery.stop();REPORT.put("passed",failure==null);REPORT.put("failure",failure==null?"":failure.toString());
            var path=Minecraft.getInstance().gameDirectory.toPath().resolve("debug/factory-inspection/report.json");Files.createDirectories(path.getParent());
            Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if(failure==null)com.mojang.logging.LogUtils.getLogger().info("FACTORY_INSPECTION_SMOKE_PASS {}",path);
            else com.mojang.logging.LogUtils.getLogger().error("FACTORY_INSPECTION_SMOKE_FAIL",failure);
        }catch(Exception error){com.mojang.logging.LogUtils.getLogger().error("Cannot report factory inspection",error);}
        finally{Minecraft.getInstance().stop();}
    }
}
