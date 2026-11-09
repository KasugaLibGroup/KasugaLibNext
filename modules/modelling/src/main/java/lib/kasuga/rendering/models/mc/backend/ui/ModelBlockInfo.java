package lib.kasuga.rendering.models.mc.backend.ui;

import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource.BlockHit;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;

/** Small reusable inspection card for native model viewers and world-space examples. */
public final class ModelBlockInfo {
    private ModelBlockInfo() {}
    public static void draw(GuiGraphics graphics, Font font, BlockHit hit, int x, int y) {
        var name = hit.state().getBlock().getName();
        String id = BuiltInRegistries.BLOCK.getKey(hit.state().getBlock()).toString();
        String coordinates = "Source: " + hit.position().getX() + ", " + hit.position().getY() + ", " + hit.position().getZ();
        int width = Math.max(font.width(name), Math.max(font.width(id), font.width(coordinates))) + 16;
        graphics.fill(x, y, x + width, y + 42, 0xE6101926);
        graphics.drawString(font, name, x + 8, y + 6, 0xFFF0F8FF);
        graphics.drawString(font, id, x + 8, y + 18, 0xFF8DD3E8);
        graphics.drawString(font, coordinates, x + 8, y + 30, 0xFFB8CBE0);
    }
}
