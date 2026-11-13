package test.kasuga.modelling;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.api.McModelHandle;
import lib.kasuga.rendering.models.mc.component.LiveModelSync;
import lib.kasuga.rendering.models.mc.dynamic.ModelParticleRuntime;
import lib.kasuga.rendering.models.uml.dynamic.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ModelInstancePoseSink;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Opt-in private resource-pack demonstration; no private model is packaged in the mod. */
@EventBusSubscriber(modid=KasugaLib.MODID,value=Dist.CLIENT)
public final class QjModelClientDemo {
 private static final boolean ENABLED=Boolean.getBoolean("kasuga.qjDemo");
 private static final long START=System.nanoTime();
 private static final Map<String,Object> REPORT=new LinkedHashMap<>();
 private static McModelHandle handle;
 private static ModelInstance owner,oldInstance;
 private static ModelParticleRuntime.EmitterModule oldParticles;
 private static Path output;
 private static int warmup,frame,reloadedFrames;
 private static float time;
 private static long reloadBaseline;
 private static boolean waiting,reloaded,finished;
 private static float maxClosure,maxParity;
 private static JsonArray expected;
 private static boolean stageRequested;
 private static volatile boolean stageReady;
 private QjModelClientDemo(){}
 @SubscribeEvent public static void before(RenderFrameEvent.Pre event){
  if(!ENABLED||finished)return;
  try{
   Minecraft mc=Minecraft.getInstance();
   check(System.nanoTime()-START<600_000_000_000L,"QJ client timeout");
   if(mc.getOverlay()==null&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen){
    String proceed=net.minecraft.network.chat.Component.translatable("selectWorld.backupJoinConfirmButton").getString();
    for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals(proceed)){b.onPress();return;}
   }
   if(mc.level==null||mc.player==null||mc.getOverlay()!=null)return;
   if(!stageRequested){
    stageRequested=true;
    int x=mc.player.blockPosition().getX(),z=mc.player.blockPosition().getZ();
    mc.getSingleplayerServer().execute(()->{
     var server=mc.getSingleplayerServer();var source=server.createCommandSourceStack().withSuppressedOutput();
     for(String command:List.of(
       "fill "+(x-18)+" 201 "+(z-10)+" "+(x+18)+" 218 "+(z+26)+" minecraft:air",
       "fill "+(x-18)+" 200 "+(z-10)+" "+(x+18)+" 200 "+(z+26)+" minecraft:light_gray_concrete",
       "fill "+(x-18)+" 201 "+(z+21)+" "+(x+18)+" 218 "+(z+21)+" minecraft:light_blue_concrete",
       "time set noon","weather clear","gamemode spectator @a",
       "tp @a "+(x+0.5)+" 204 "+(z+0.5)+" 0 20"))
       server.getCommands().performPrefixedCommand(source,command);
     stageReady=true;
    });return;
   }
   if(!stageReady||mc.player.getY()<203)return;
   if(handle==null){
    mc.setScreen(null);mc.options.hideGui=true;mc.options.fov().set(55);mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);mc.player.setYRot(0);mc.player.setXRot(20);
    Vec3 origin=new Vec3(mc.player.getX(),201.05,mc.player.getZ()+10);
    handle=McModelHandle.of(ResourceLocation.parse("qj_demo:models/qj_bogey_main.bbmodel"),null,ResourceLocation.parse("qj_demo:demo"),origin).setRotationDegrees(0,-105,0).setAmbientLightEnhancement(1.5f);
    output=mc.gameDirectory.toPath().resolve("debug/qj-demo");Files.createDirectories(output.resolve("frames"));
    Path reference=mc.gameDirectory.toPath().resolve("blockbench-poses.json");
    if(Files.isRegularFile(reference))expected=JsonParser.parseString(Files.readString(reference)).getAsJsonArray();
    LiveModelSync.start();reloadBaseline=LiveModelSync.completedReloads();
   }
   if(!handle.mount())return;
   ModelInstance instance=handle.instance();
   if(instance!=owner){
    owner=instance;
    var clip=instance.getModel().getAnimations().get("qj.running");check(clip!=null,"missing QJ animation");
    var sink=new ModelInstancePoseSink(instance);
    handle.setAnimationDriver(new RebindablePoseDriver(){
     private ModelInstancePoseSink outputSink=sink;
     private lib.kasuga.rendering.models.uml.dynamic.animation.AnimationLibrary.Clip<?> current=clip;
     @Override public void sample(float partial){outputSink.applyPose(current.sample(time));}
     @Override public void rebind(ModelInstance fresh){
      outputSink=new ModelInstancePoseSink(fresh);current=fresh.getModel().getAnimations().get("qj.running");
     }
    });
   }
   if(waiting&&LiveModelSync.completedReloads()>reloadBaseline&&instance!=oldInstance){
    check(oldInstance.isClosed()&&oldParticles.closed()&&oldParticles.particleCount()==0,"reload leaked old model/particles");
    reloaded=true;waiting=false;REPORT.put("automaticReload",true);REPORT.put("oldInstanceDisposed",true);
   }
   time=(frame%240)/30f;
  }catch(Throwable failure){finish(failure);}
 }
 @SubscribeEvent public static void after(RenderFrameEvent.Post event){
  if(!ENABLED||finished||handle==null||owner==null)return;
  try{
   Minecraft mc=Minecraft.getInstance();
   if(mc.getOverlay()!=null){if(waiting)capture("frames/reload-"+System.nanoTime()+".png");return;}
   if(++warmup<60)return;
   var particles=ModelParticleRuntime.module(owner);check(particles!=null,"steam sources missing");
   for(var result:owner.getSkeletonInstance().ikSolveResults().values())maxClosure=Math.max(maxClosure,result.residual()*16);
   if(frame%15==0&&expected!=null&&!waiting){
    var sample=expected.get((frame%240)/15*2).getAsJsonObject();
    var inverse=owner.getSkeletonInstance().getWorldTransform().invertTransform();
    for(var entry:sample.getAsJsonObject("positions").entrySet()){
     var bone=owner.getModel().getSkeleton().getBoneMap().get(entry.getKey());
     Vector3f p=owner.getSkeletonInstance().getAbsoluteTransforms().get(bone).getPosition();
     inverse.transformPosition(p).mul(16);
     var e=entry.getValue().getAsJsonArray();float error=p.distance(new Vector3f(e.get(0).getAsFloat(),e.get(1).getAsFloat(),e.get(2).getAsFloat()));
     maxParity=Math.max(maxParity,error);
    }
   }
   if(!waiting){
    capture(String.format(Locale.ROOT,"frames/frame-%05d.png",frame));frame++;
    if(!reloaded&&frame==240){
     capture("before-reload.png");oldInstance=owner;oldParticles=particles;waiting=true;reloadBaseline=LiveModelSync.completedReloads();
     REPORT.put("particlesBeforeReload",particles.particleCount());
     Files.writeString(output.resolve("ready-for-reload"),"Blockbench can now save the updated model\n");
     LogUtils.getLogger().info("QJ_READY_FOR_BLOCKBENCH_SAVE {}",output);
    }
    if(reloaded&&++reloadedFrames==240){
     capture("after-reload.png");REPORT.put("particlesAfterReload",particles.particleCount());
     check(maxClosure<0.1f,"QJ closure error: "+maxClosure);check(maxParity<0.15f,"QJ pose parity error: "+maxParity);
     finish(null);
    }
   }
  }catch(Throwable failure){finish(failure);}
 }
 private static void capture(String name)throws Exception{
  var t=Minecraft.getInstance().getMainRenderTarget();byte[] rgba=RgbaReadback.copy(new FrameTexture(t.frameBufferId,t.getColorTextureId(),t.width,t.height));
  BufferedImage image=new BufferedImage(t.width,t.height,BufferedImage.TYPE_INT_ARGB);
  int[] pixels=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
  for(int i=0;i<pixels.length;i++){int p=i*4;pixels[i]=(rgba[p+3]&255)<<24|(rgba[p]&255)<<16|(rgba[p+1]&255)<<8|(rgba[p+2]&255);}
  if(image.getWidth()>1280){var resized=new BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB);var graphics=resized.createGraphics();graphics.drawImage(image,0,0,1280,720,null);graphics.dispose();image=resized;}
  ImageIO.write(image,"PNG",output.resolve(name).toFile());
 }
 private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
 private static void finish(Throwable failure){
  if(finished)return;finished=true;
  try{
   if(handle!=null)handle.destroy();LiveModelSync.stop();
   REPORT.put("passed",failure==null);REPORT.put("failure",failure==null?"":failure.toString());REPORT.put("maxClosurePixels",maxClosure);REPORT.put("maxPoseDifferencePixels",maxParity);REPORT.put("renderer",GL11.glGetString(GL11.GL_RENDERER));
   if(output==null)output=Minecraft.getInstance().gameDirectory.toPath().resolve("debug/qj-demo");Files.createDirectories(output);
   Files.writeString(output.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
   if(failure==null)LogUtils.getLogger().info("QJ_CLIENT_DEMO_PASS {}",output);else LogUtils.getLogger().error("QJ_CLIENT_DEMO_FAIL",failure);
  }catch(Exception e){LogUtils.getLogger().error("QJ report failed",e);}
  Minecraft.getInstance().stop();
 }
}
