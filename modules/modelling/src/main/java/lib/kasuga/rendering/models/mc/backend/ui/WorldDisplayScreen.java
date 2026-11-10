package lib.kasuga.rendering.models.mc.backend.ui;

import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import lib.kasuga.rendering.models.mc.scene.display.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared viewer/inspection screen; model, statistics and controls all refer to one owned display. */
public final class WorldDisplayScreen extends Screen {
    private final WorldDisplayTarget root;
    private final ModelDisplayTarget selected;
    private final GuiModelPreview preview;
    private final boolean ownsTarget,world;
    private final Runnable returnTo;
    private ModelDisplayPanel panel;
    private float panelScale=1;
    private int panelLeft;
    private long tick;
    private float yaw=-.55f,pitch=.25f,zoom=1,panX,panY;
    private int dragging=-1;
    private double dragDistance;
    private lib.kasuga.rendering.models.mc.scene.MinecraftModelSource.BlockHit hit;
    public WorldDisplayScreen(Component title,WorldDisplayTarget root,ModelDisplayTarget selected,boolean world,boolean ownsTarget,Runnable returnTo) {
        super(title);this.root=root;this.selected=selected;this.world=world;this.ownsTarget=ownsTarget;this.returnTo=returnTo;
        preview=world?null:new GuiModelPreview(root.borrowedSource());
    }
    public GuiModelPreview preview(){return preview;}
    public ModelDisplayPanel panel(){return panel;}
    /** Logical panel pixels to this screen, including responsive panel scaling. */
    public org.joml.Vector2f panelScreenPosition(float x,float y){return new org.joml.Vector2f(panelLeft+x*panelScale,24+y*panelScale);}
    private int viewerWidth(){return Math.max(1,panelLeft-12);}
    @Override protected void init() {
        int reserved=Math.clamp((int)(width*.37),120,280);
        panelScale=Math.min(1,reserved/260f);panelLeft=width-reserved-8;
        panel=new ModelDisplayPanel(selected,260,Math.max(130,(int)((height-48)/panelScale)));
        addRenderableWidget(Button.builder(Component.literal("Back / 返回"),button->onClose()).bounds(width-98,height-22,90,20).build());
    }
    @Override public void tick(){if(!world&&!root.isHosted())root.tick(++tick,.05);if(root.isClosed())onClose();}
    @Override public void render(GuiGraphics graphics,int mx,int my,float partial) {
        if(!world) {
            graphics.fill(0,0,width,height,0xFF121B27);
            preview.render(graphics,4,28,viewerWidth(),Math.max(1,height-40),partial);
            if(preview.failure()!=null)graphics.drawString(font,preview.failure(),8,30,0xFFFFA090);
        }
        graphics.drawString(font,title,8,8,0xFFE8F5FF);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(panelLeft,24,0);graphics.pose().scale(panelScale,panelScale,1);
            panel.render(graphics,(int)((mx-panelLeft)/panelScale),(int)((my-24)/panelScale),partial);
        } finally { graphics.pose().popPose(); }
        if(hit!=null)ModelBlockInfo.draw(graphics,font,hit,8,38);
        super.render(graphics,mx,my,partial);
    }
    private boolean inViewer(double x,double y){return !world&&x>=4&&x<4+viewerWidth()&&y>=28&&y<height-12;}
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(panel.mouseClicked((x-panelLeft)/panelScale,(y-24)/panelScale,button)){setFocused(panel);return true;}
        if(super.mouseClicked(x,y,button))return true;
        if(inViewer(x,y)&&(button==0||button==2)){dragging=button;dragDistance=0;return true;}
        return false;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if(dragging==button) {
            dragDistance+=Math.hypot(dx,dy);
            if(button==0){yaw+=(float)dx*.01f;pitch=Math.clamp(pitch+(float)dy*.01f,-1.5f,1.5f);}
            else{panX+=(float)(dx/viewerWidth());panY+=(float)(dy/Math.max(1,height-40));}
            preview.setView(yaw,pitch,zoom,panX,panY);return true;
        }
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        panel.mouseReleased((x-panelLeft)/panelScale,(y-24)/panelScale,button);
        if(dragging==button){
            if(button==0&&dragDistance<=3&&inViewer(x,y))hit=preview.pick((x-4)/viewerWidth(),(y-28)/Math.max(1,height-40)).orElse(null);
            dragging=-1;return true;
        }
        return super.mouseReleased(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(panel.mouseScrolled((x-panelLeft)/panelScale,(y-24)/panelScale,horizontal,vertical))return true;
        if(inViewer(x,y)){zoom=Math.clamp(zoom*(float)Math.exp(vertical*.1),.1f,10);preview.setView(yaw,pitch,zoom,panX,panY);return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean keyPressed(int key,int scanCode,int modifiers){
        if(!world&&key==org.lwjgl.glfw.GLFW.GLFW_KEY_R){yaw=-.55f;pitch=.25f;zoom=1;panX=panY=0;preview.setView(yaw,pitch,zoom,panX,panY);return true;}
        return super.keyPressed(key,scanCode,modifiers);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics graphics,int x,int y,float partial){}
    @Override public void onClose(){if(returnTo!=null)returnTo.run();else super.onClose();}
    @Override public void removed(){try{if(preview!=null)preview.close();}finally{if(ownsTarget)root.close();}}
}
