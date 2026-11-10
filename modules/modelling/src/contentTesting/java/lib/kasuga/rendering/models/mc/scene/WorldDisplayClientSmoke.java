package lib.kasuga.rendering.models.mc.scene;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.ui.*;
import lib.kasuga.rendering.models.mc.scene.display.*;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.scene.SceneModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import java.nio.file.*;
import java.util.*;

/** Final-frame acceptance for the public display API, not a second rendering implementation. */
@EventBusSubscriber(modid=KasugaLib.MODID,value=Dist.CLIENT)
public final class WorldDisplayClientSmoke {
    private static final boolean ENABLED=Boolean.getBoolean("kasuga.testWorldDisplay");
    private static final long START=System.nanoTime();
    private static final Map<String,Object> REPORT=new LinkedHashMap<>();
    private static final Path OUTPUT=Path.of(System.getProperty("kasuga.worldDisplay.output","ksg_debug/world-display"));
    private static boolean finished;
    private static boolean repositioned;
    private static int warmup,stage,wait;
    private static WorldDisplayScene root;
    private static ModelDisplayTarget target;
    private static ModelDisplayTarget importedTarget;
    private static SceneModels world;
    private static WorldDisplayScreen screen;
    private static byte[] baseline,paused;
    private static List<StructureBlockInfo> blocks;
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if(!ENABLED||finished)return;var mc=Minecraft.getInstance();
        if(System.nanoTime()-START>180_000_000_000L){finish(new IllegalStateException("World display timeout at "+stage));return;}
        if(mc.getOverlay()==null&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen confirm) {
            String proceed=Component.translatable("selectWorld.backupJoinConfirmButton").getString();
            for(var child:confirm.children())if(child instanceof net.minecraft.client.gui.components.Button button&&button.getMessage().getString().equals(proceed)){button.onPress();return;}
        }
        if(mc.level==null||mc.player==null||mc.getOverlay()!=null||++warmup<40)return;
        try {
            mc.setWindowActive(true);mc.options.pauseOnLostFocus=false;
            if(!repositioned) {
                repositioned=true;mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
                server.execute(()->{var player=server.getPlayerList().getPlayer(id);player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                    player.getAbilities().flying=true;player.onUpdateAbilities();player.teleportTo(player.getX(),200,player.getZ());});
                return;
            }
            if(mc.player.getY()<190)return;
            if(target!=null)return;
            Files.createDirectories(OUTPUT);
            verifyImportFormats(mc);
            // Import an arbitrary built-in blueprint through the file path API (misleading extension deliberately).
            try(var pack=net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource()) {
                var input=pack.getResource(net.minecraft.server.packs.PackType.SERVER_DATA,net.minecraft.resources.ResourceLocation.parse("minecraft:structure/end_city/base_floor.nbt"));
                try(var stream=input.get()) {
                    var nbt=NbtIo.readCompressed(stream,NbtAccounter.create(SchematicModelData.MAX_NBT_BYTES));
                    var path=OUTPUT.resolve("import-fixture.blueprint");NbtIo.writeCompressed(nbt,path);
                    blocks=BlueprintImporters.read(path,mc.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK));
                    var before=Files.readAllBytes(path);
                    target=new ModelDisplayTarget(BlueprintImporters.load(mc.level,path)).fit(3).autoPanel();
                    check(Arrays.equals(before,Files.readAllBytes(path)),"Import changed source file");
                }
            }
            // States of the same block must aggregate; negative layer/origin statistics use source coordinates.
            var variantBlocks=List.of(new StructureBlockInfo(new BlockPos(-4,-3,0),Blocks.OAK_STAIRS.defaultBlockState(),null),
                    new StructureBlockInfo(new BlockPos(-3,-3,0),Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF,Half.TOP),null),
                    new StructureBlockInfo(new BlockPos(-2,-3,0),Blocks.AIR.defaultBlockState(),null));
            try(var variants=new BlockModelSource(mc.level,variantBlocks,new BlockPos(-4,-4,0))) {
                var counts=variants.blockStatistics(LayerLayout.Axis.Y);
                check(counts.size()==1&&counts.get(1).get(net.minecraft.resources.ResourceLocation.parse("minecraft:oak_stairs"))==2,"Statistics split block states or included air/origin error");
            }
            int total=target.statistics().values().stream().flatMap(m->m.values().stream()).mapToInt(Integer::intValue).sum();
            check(total==blocks.stream().filter(b->!b.state().isAir()).count(),"Layer totals differ from imported blocks");
            REPORT.put("blockCount",total);REPORT.put("layers",target.coordinates());REPORT.put("variantAggregation",true);
            root=new WorldDisplayScene();var inner=root.add(new WorldDisplayScene());inner.transform(new Matrix4f().rotateY(.15f));inner.add(target);
            screen=new WorldDisplayScreen(Component.literal("Blueprint / 蓝图渐进堆叠"),root,target,false,false,null);mc.setScreen(screen);
        }catch(Exception e){finish(e);}
    }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if(!ENABLED||finished||target==null||++wait<10)return;
        try {
            var mc=Minecraft.getInstance();
            if(stage<5) {
                check(screen.preview().failure()==null,"Viewer failed: "+screen.preview().failure());
                var frame=screen.preview().currentFrame();check(frame!=null,"No preview framebuffer");
                var pixels=RgbaReadback.copy(frame);long covered=0,clear=0;
                for(int i=3;i<pixels.length;i+=4){if((pixels[i]&255)>32)covered++;if(pixels[i]==0)clear++;}
                check(clear>100,"Viewer lost transparent background");
                if(stage==0){
                    check(covered>100,"No blueprint geometry");verifyVisibleStatistics();baseline=pixels;save("viewer-full");click(20,34);check(target.stacking().state()==LayerStackAnimation.State.PLAYING,"Play widget did not trigger proxy animation");
                }else if(stage==1){
                    check(!Arrays.equals(baseline,pixels),"Progressive stacking did not change final GPU pixels");save("viewer-stacking");click(115,34);
                    check(target.stacking().state()==LayerStackAnimation.State.PAUSED,"Pause widget did not stop animation");
                }else if(stage==2){paused=pixels;}
                else if(stage==3){
                    check(Arrays.equals(paused,pixels),"Paused animation kept moving in GPU output");click(115,34);check(target.stacking().state()==LayerStackAnimation.State.PLAYING,"Resume widget failed");
                }else if(stage==4){
                    if(target.stacking().state()!=LayerStackAnimation.State.COMPLETE){wait=8;return;}
                    check(Arrays.equals(baseline,pixels),"Completed stack differs from assembled blueprint framebuffer");
                    save("viewer-complete");REPORT.put("viewerPixelParity",true);REPORT.put("pausePixelParity",true);
                    var camera=mc.gameRenderer.getMainCamera();var position=camera.getPosition().add(new Vec3(camera.getLookVector()).scale(3.5));
                    // Align the complete scene, including its world panel, to the camera for pointer testing.
                    root.transform(new Matrix4f().rotate(camera.rotation()));
                    world=SceneModels.open(mc.level);world.add(root,position);
                    mc.setScreen(null);
                }
            }else if(stage==5){
                check(Constants.UI_BACKEND.containsRenderable(target.panel()),"Nested scene did not mount world UI");
                check(Constants.UI_BACKEND.containsListener(target.panel()),"World panel has no input surface");
                save("world-nested-panel");REPORT.put("worldPanelMounted",true);
                // Use UIBackend's actual render projection (including FOV changes), not an assumed camera matrix.
                var router=Constants.UI_BACKEND.getInputRouter();
                var point=router.screenPosition(target.panel(),35,34);check(point!=null,"World panel published no screen projection");
                double x=point.x,y=point.y;
                router.move(x,y);
                REPORT.put("worldClick",Map.of("screenX",x,"screenY",y,"local",String.valueOf(router.positionOn(target.panel())),
                        "panelX",target.panel().getX(),"panelY",target.panel().getY()));
                check(router.press(x,y,0),"Projected world panel click was rejected");router.release(x,y,0);
                check(target.stacking().state()==LayerStackAnimation.State.PLAYING,"World panel did not trigger stacking");
                REPORT.put("worldPanelInteraction",true);
            }else if(stage==6){
                save("world-stacking");world.close();world=null;
                check(!Constants.UI_BACKEND.containsRenderable(target.panel()),"World close left an orphan panel");
                check(target.isClosed()&&root.isClosed()&&target.proxySession().isClosed(),"Display close did not close proxy subtree");
                REPORT.put("nestedClose",true);
                openImport();setImportPath(OUTPUT.resolve("import-fixture.blueprint").toAbsolutePath().toString());importClick(false);
                check(mc.screen instanceof WorldDisplayScreen,"Import UI did not open viewer");screen=(WorldDisplayScreen)mc.screen;
            }else if(stage==7){
                check(screen.preview().failure()==null&&screen.preview().currentFrame()!=null,"Imported viewer has no framebuffer");
                save("import-ui-viewer");var preview=screen.preview();mc.setScreen(null);check(preview.isClosed(),"Imported viewer left a canvas open");
                openImport();setImportPath(OUTPUT.resolve("not-a-blueprint.missing").toString());importClick(true);
                check(mc.screen instanceof BlueprintImportScreen&&world==null,"Invalid import replaced the current display");
                setImportPath(OUTPUT.resolve("import-fixture.blueprint").toAbsolutePath().toString());importClick(true);
                check(world!=null&&mc.screen instanceof WorldDisplayScreen,"Import UI did not mount world+panel");
                REPORT.put("importUiViewerAndError",true);
            }else if(stage==8){
                check(Constants.UI_BACKEND.containsRenderable(importedTarget.panel()),"Imported world target did not auto-mount its panel");
                save("import-ui-world");world.close();world=null;mc.setScreen(null);
                check(importedTarget.isClosed()&&!Constants.UI_BACKEND.containsRenderable(importedTarget.panel()),"Imported scene did not clean up");
                REPORT.put("importUiWorld",true);finish(null);return;
            }
            stage++;wait=0;
        }catch(Exception e){finish(e);}
    }
    private static void click(int localX,int localY){var point=screen.panelScreenPosition(localX,localY);check(screen.mouseClicked(point.x,point.y,0),"Panel widget rejected click");screen.mouseReleased(point.x,point.y,0);}
    private static void openImport(){Minecraft.getInstance().setScreen(new BlueprintImportScreen((next,display)->{world=next;importedTarget=display;},null));}
    private static void setImportPath(String value){for(var child:Minecraft.getInstance().screen.children())if(child instanceof net.minecraft.client.gui.components.EditBox box){box.setValue(value);return;}throw new IllegalStateException("Import has no path input");}
    private static void importClick(boolean world){var screen=Minecraft.getInstance().screen;double x=screen.width/2+(world?70:-70),y=screen.height/2+10;check(screen.mouseClicked(x,y,0),"Import button rejected click");screen.mouseReleased(x,y,0);}
    private static void verifyVisibleStatistics()throws Exception {
        var mc=Minecraft.getInstance();var a=screen.panelScreenPosition(8,113);var b=screen.panelScreenPosition(252,149);
        long yellow=0;
        try(var scope=new GuiRenderScope();var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            double sx=(double)image.getWidth()/screen.width,sy=(double)image.getHeight()/screen.height;
            for(int y=(int)(a.y*sy);y<Math.min(image.getHeight(),(int)(b.y*sy));y++)for(int x=(int)(a.x*sx);x<Math.min(image.getWidth(),(int)(b.x*sx));x++) {
                int c=image.getPixelRGBA(x,y);int red=c&255,green=c>>>8&255,blue=c>>>16&255;
                if(red>210&&green>190&&blue<210)yellow++;
            }
        }
        check(yellow>40,"Responsive panel clipped the actual statistics text");REPORT.put("statisticsTextPixels",yellow);
    }
    private static void verifyImportFormats(Minecraft mc)throws Exception {
        for(int version:new int[]{2,3}) {
            var root=new CompoundTag();root.putInt("Version",version);
            root.putInt("DataVersion",net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            root.putShort("Width",(short)2);root.putShort("Height",(short)2);root.putShort("Length",(short)1);
            root.putIntArray("Offset",new int[]{-7,-3,4});
            var container=version==3?new CompoundTag():root;
            var palette=new CompoundTag();palette.putInt("minecraft:oak_stairs[half=bottom]",130);palette.putInt("minecraft:oak_stairs[half=top]",131);
            palette.putInt("minecraft:chest",2);palette.putInt("minecraft:air",0);container.put("Palette",palette);
            container.putByteArray(version==3?"Data":"BlockData",new byte[]{(byte)130,1,(byte)131,1,2,0});
            var chest=new CompoundTag();chest.putString("Id","minecraft:chest");chest.putIntArray("Pos",new int[]{0,1,0});
            var data=version==3?new CompoundTag():chest;data.putString("CustomName","\"Imported chest\"");if(version==3)chest.put("Data",data);
            var entities=new ListTag();entities.add(chest);container.put("BlockEntities",entities);if(version==3)root.put("Blocks",container);
            var input=root;
            if(version==3){input=new CompoundTag();input.put("Schematic",root);}
            var before=input.copy();
            var decoded=BlueprintImporters.decode(input,mc.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK));
            check(before.equals(input),"Sponge import modified input NBT");
            check(decoded.size()==4&&decoded.get(0).pos().equals(new BlockPos(-7,-3,4)),"Sponge offsets/volume are wrong");
            check(decoded.get(1).state().getValue(StairBlock.HALF)==Half.TOP,"Sponge block-state properties were lost");
            check(decoded.get(2).nbt().getString("CustomName").equals("\"Imported chest\""),"Sponge block entity NBT lost");
            var path=OUTPUT.resolve("sponge-v"+version+".schem");
            if(version==3)NbtIo.writeCompressed(input,path);
            else try(var stream=new java.io.DataOutputStream(Files.newOutputStream(path))){NbtIo.write(input,stream);}
            check(decoded.equals(BlueprintImporters.read(path,mc.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK))),"Sponge gzip/raw file differs from decoded blueprint");
            try(var source=BlueprintImporters.load(mc.level,path)) {
                check(source.blockStatistics(LayerLayout.Axis.Y).get(-3).get(net.minecraft.resources.ResourceLocation.parse("minecraft:oak_stairs"))==2,"Sponge variant aggregation wrong");
            }
            REPORT.put("sponge-v"+version,true);
        }
        var example=test.kasuga.modelling.demo.preview.MinecraftPreviewDemo.examples().stream().filter(e->e.name().equals("litematic")).findFirst().orElseThrow();
        try(var original=example.factory().create();var imported=BlueprintImporters.load(mc.level,mc.gameDirectory.toPath().resolve("ksg_debug/minecraft-preview/fixture.litematic"))) {
            check(original.blockStatistics(LayerLayout.Axis.Y).equals(imported.blockStatistics(LayerLayout.Axis.Y)),"Auto-detected Litematic differs from explicit importer");
            REPORT.put("litematic",true);
        }
        boolean rejected=false;try{BlueprintImporters.decode(new CompoundTag(),mc.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK));}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Unknown NBT accepted as a blueprint");
    }
    private static void check(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    private static void save(String name)throws Exception{try(var scope=new GuiRenderScope();var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())){image.writeToFile(OUTPUT.resolve(name+".png"));}}
    private static void finish(Exception failure){
        if(finished)return;finished=true;
        try {
            if(world!=null)world.close();if(root!=null)root.close();
            REPORT.put("passed",failure==null);REPORT.put("failure",failure==null?"":failure.toString());
            Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if(failure==null)LogUtils.getLogger().info("WORLD_DISPLAY_SMOKE_PASS {}",OUTPUT);else LogUtils.getLogger().error("WORLD_DISPLAY_SMOKE_FAIL",failure);
        }catch(Exception e){LogUtils.getLogger().error("Cannot report world display smoke",e);}
        finally{Minecraft.getInstance().stop();}
    }
}
