package lib.kasuga.rendering.models.mc.backend.ui;

import lib.kasuga.rendering.models.mc.scene.BlueprintImporters;
import lib.kasuga.rendering.models.mc.scene.display.*;
import lib.kasuga.scene.SceneModels;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;

/** Local file import UI. The host owns the returned world scene; failed imports leave it untouched. */
public final class BlueprintImportScreen extends Screen {
    @FunctionalInterface public interface WorldMount { void mounted(SceneModels scene,ModelDisplayTarget display); }
    private final WorldMount mount;
    private final Runnable returnTo;
    private EditBox path;
    private String error;
    public BlueprintImportScreen(WorldMount mount,Runnable returnTo) {
        super(Component.literal("Import blueprint / 导入蓝图"));this.mount=java.util.Objects.requireNonNull(mount);this.returnTo=returnTo;
    }
    @Override protected void init() {
        String old=path==null?"":path.getValue();
        path=addRenderableWidget(new EditBox(font,width/2-150,height/2-30,300,20,Component.literal("Local blueprint path")));
        path.setMaxLength(4096);path.setValue(old);setInitialFocus(path);
        addRenderableWidget(Button.builder(Component.literal("3D viewer"),b->load(false)).bounds(width/2-150,height/2,144,20).build());
        addRenderableWidget(Button.builder(Component.literal("World + panel"),b->load(true)).bounds(width/2+6,height/2,144,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(width/2-70,height/2+28,140,20).build());
    }
    private void load(boolean world) {
        ModelDisplayTarget target=null;SceneModels scene=null;
        try {
            if(minecraft.level==null)throw new IllegalStateException("Open a world first");
            Path file=Path.of(path.getValue().strip());
            target=new ModelDisplayTarget(BlueprintImporters.load(minecraft.level,file));
            if(world) {
                target.fit(3).autoPanel();var root=new WorldDisplayScene();root.add(target);
                var camera=minecraft.gameRenderer.getMainCamera();
                Vec3 position=camera.getPosition().add(new Vec3(camera.getLookVector()).scale(3.5));
                root.transform(new org.joml.Matrix4f().rotate(camera.rotation()));
                scene=SceneModels.open(minecraft.level);scene.add(root,position);
                mount.mounted(scene,target);
                minecraft.setScreen(new WorldDisplayScreen(Component.literal(file.getFileName().toString()),root,target,true,false,returnTo));
            }else minecraft.setScreen(new WorldDisplayScreen(Component.literal(file.getFileName().toString()),target,target,false,true,returnTo));
        } catch(Exception failure) {
            if(scene!=null)scene.close();else if(target!=null)target.close();
            error=failure.getClass().getSimpleName()+": "+failure.getMessage();
        }
    }
    @Override public void render(GuiGraphics graphics,int mx,int my,float partial) {
        graphics.fill(0,0,width,height,0xFF121B27);
        graphics.drawCenteredString(font,title,width/2,height/2-72,0xFFE8F5FF);
        graphics.drawCenteredString(font,"NBT / Create / Litematic / WorldEdit .schem",width/2,height/2-52,0xFFB8CBE0);
        if(error!=null)graphics.drawString(font,font.plainSubstrByWidth(error,width-24),12,height-22,0xFFFFA090);
        super.render(graphics,mx,my,partial);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics graphics,int x,int y,float partial){}
    @Override public void onClose(){if(returnTo!=null)returnTo.run();else super.onClose();}
}
