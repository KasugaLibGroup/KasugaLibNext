package lib.kasuga.rendering.models.mc.backend.ui;

import lib.kasuga.rendering.models.mc.scene.*;
import lib.kasuga.rendering.models.mc.scene.display.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

/** Reusable pixel-local panel, embeddable in Screen or UIBackend; it owns no model/GPU resources. */
public final class ModelDisplayPanel extends AbstractContainerEventHandler implements Renderable, LayoutElement {
    private final ModelDisplayTarget target;
    private final int width,height;
    private int x,y,index,scroll;
    private final List<AbstractWidget> widgets=new ArrayList<>();
    public ModelDisplayPanel(ModelDisplayTarget target,int width,int height) {
        if(width<240||height<130)throw new IllegalArgumentException("Panel requires at least 240x130 pixels");
        this.target=Objects.requireNonNull(target);this.width=width;this.height=height;
        index=Math.max(0,target.coordinates().size()-1);
        button("Play / 重播",8,24,90,()->{
            target.layers(LayerLayout.ASSEMBLED.axis(target.layers().axis()).separation(target.layers().separation()));target.stacking().play();
        });
        button("Pause / 继续",102,24,width-110,()->{if(target.stacking().state()==LayerStackAnimation.State.PAUSED)target.stacking().resume();else target.stacking().pause();});
        button("<",8,48,30,()->select(-1));button(">",42,48,30,()->select(1));
        button("All",76,48,44,()->{target.stacking().showAll();target.layers(LayerLayout.ASSEMBLED.axis(target.layers().axis()).separation(target.layers().separation()));});
        button("Axis",124,48,50,()->{
            var axis=LayerLayout.Axis.values()[(target.layers().axis().ordinal()+1)%3];
            target.layers(LayerLayout.ASSEMBLED.axis(axis));index=Math.max(0,target.coordinates().size()-1);scroll=0;
        });
        button("Explode",178,48,width-186,()->target.layers(target.layers().separation(target.layers().separation()==0?1:0)));
        button("Speed",8,72,90,()->{double old=target.stacking().secondsPerLayer();target.stacking().secondsPerLayer(old>.3?.15:old>.1?.6:.35);});
        button("Stack / slice",102,72,width-110,()->{
            int layer=currentLayer();boolean slice=target.layers().firstLayer()==target.layers().lastLayer();
            target.stacking().showAll();target.layers(target.layers().range(slice?Integer.MIN_VALUE:layer,layer));
        });
    }
    private void button(String label,int px,int py,int w,Runnable action) {
        widgets.add(Button.builder(Component.literal(label),b->action.run()).bounds(px,py,w,20).build());
    }
    public int currentLayer() {
        var layers=target.coordinates();if(layers.isEmpty())return 0;
        if(target.stacking().state()!=LayerStackAnimation.State.COMPLETE) {
            int next=Math.min(layers.size()-1,(int)target.stacking().progress(1));return layers.get(next);
        }
        index=Math.clamp(index,0,layers.size()-1);return layers.get(index);
    }
    private void select(int step) {
        if(target.coordinates().isEmpty())return;
        index=Math.clamp(index+step,0,target.coordinates().size()-1);scroll=0;
        target.stacking().showAll();target.layers(target.layers().range(Integer.MIN_VALUE,currentLayer()));
    }
    @Override public List<? extends GuiEventListener> children() { return widgets; }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial) {
        var font=Minecraft.getInstance().font;
        graphics.fill(x,y,x+width,y+height,0xEF121B27);
        graphics.drawString(font,"Layers / 分层  "+target.layers().axis()+"  "+target.stacking().state(),x+8,y+8,0xFFE8F5FF);
        for(var widget:widgets)widget.render(graphics,mouseX,mouseY,partial);
        var counts=target.statistics().getOrDefault(currentLayer(),Map.of());
        int total=counts.values().stream().mapToInt(Integer::intValue).sum();
        graphics.drawString(font,"Layer "+currentLayer()+" | "+total+" blocks | "+counts.size()+" types",x+8,y+98,0xFFB8CBE0);
        var rows=counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        int visible=Math.max(0,(height-116)/12);scroll=Math.clamp(scroll,0,Math.max(0,rows.size()-visible));
        // Limit rows and text in panel-local space. A screen-space scissor would be wrong when
        // a host scales/translates this panel, and a world canvas already clips its whole surface.
        for(int i=scroll;i<Math.min(rows.size(),scroll+visible);i++) {
            var row=rows.get(i);String number=" x"+row.getValue();
            String name=font.plainSubstrByWidth(row.getKey().toString(),width-20-font.width(number));
            graphics.drawString(font,name+number,x+8,y+114+(i-scroll)*12,0xFFF2E5B8);
        }
        if(rows.isEmpty())graphics.drawString(font,"No block data / 无方块数据",x+8,y+114,0xFFB8CBE0);
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical) {
        if(mx<x||my<y||mx>=x+width||my>=y+height)return false;
        scroll=Math.max(0,scroll-(int)Math.signum(vertical));return true;
    }
    @Override public void setX(int next) { int delta=next-x;x=next;for(var w:widgets)w.setX(w.getX()+delta); }
    @Override public void setY(int next) { int delta=next-y;y=next;for(var w:widgets)w.setY(w.getY()+delta); }
    @Override public int getX(){return x;}
    @Override public int getY(){return y;}
    @Override public int getWidth(){return width;}
    @Override public int getHeight(){return height;}
    @Override public net.minecraft.client.gui.navigation.ScreenRectangle getRectangle() {
        return new net.minecraft.client.gui.navigation.ScreenRectangle(x,y,width,height);
    }
    @Override public void visitWidgets(Consumer<AbstractWidget> consumer){widgets.forEach(consumer);}
}
