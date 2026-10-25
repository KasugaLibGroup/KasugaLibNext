package lib.kasuga.uip.ldlib;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4fc;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Objects;

/** Shows an already-open server menu in the world while retaining its real synchronization channel. */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = LdLibUiProvider.MOD_ID, value = Dist.CLIENT)
public final class LdMenuElement implements AutoCloseable {
    private static final Set<LdMenuElement> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ModularUIContainerMenu menu;
    private final LdElement panel;
    private boolean closed;

    private LdMenuElement(ModularUIContainerMenu menu, LdElement panel) {
        this.menu = menu;
        this.panel = panel;
    }

    /** Call after the normal menu-open packet has installed the menu and its initial snapshot. */
    public static LdMenuElement attach(UIBackend backend, ModularUIContainerMenu menu,
                                       int width, int height, Matrix4fc localToWorld) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(backend);
        Objects.requireNonNull(localToWorld);
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("World UI dimensions must be positive");
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu != menu)
            throw new IllegalArgumentException("World menu must be the player's currently open menu");
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || screen.getMenu() != menu)
            throw new IllegalArgumentException("Move the menu from its own initialized container screen");
        // Screen removal tears down the old composition. initScreen below rebuilds it for this host.
        mc.setScreen(null);
        var ui = menu.getModularUI();
        ui.setScreen(null);
        try {
            var host = new LdMenuElement(menu, LdElement.attach(backend, ui, width, height, localToWorld));
            ACTIVE.add(host);
            return host;
        } catch (RuntimeException failure) {
            if (mc.player != null && mc.player.containerMenu == menu) mc.player.closeContainer();
            throw failure;
        }
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var player = Minecraft.getInstance().player;
        for (var host : Set.copyOf(ACTIVE)) {
            if (player == null || player.containerMenu != host.menu || host.panel.isClosed()) host.close();
            else host.menu.getModularUI().syncManager.tick();
        }
    }

    public LdElement panel() { return panel; }
    public boolean isClosed() { return closed; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ACTIVE.remove(this);
        try { panel.close(); }
        finally {
            var player = Minecraft.getInstance().player;
            if (player != null && player.containerMenu == menu) player.closeContainer();
        }
    }
}
