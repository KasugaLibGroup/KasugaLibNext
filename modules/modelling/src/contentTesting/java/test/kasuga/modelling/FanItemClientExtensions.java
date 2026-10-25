package test.kasuga.modelling;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.api.item.ItemModelRenderers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Client wiring of the fan example items: both fan blocks render their item as a pipeline model
 * (GUI, hand, ground) instead of a baked JSON model.
 *
 * <p>The content owns this registration because it owns the item ids; the library only provides the
 * mapping holder and the shared BEWLR. The matching item model JSON is
 * {@code {"parent": "minecraft:builtin/entity"}} — without this event handler vanilla would call the
 * default BEWLR and draw nothing.
 */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class FanItemClientExtensions {

    private FanItemClientExtensions() {
    }

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        bind(event, ModellingContentTest.EXAMPLE_FAN_BLOCK.getEntry());
        bind(event, BuiltInRegistries.BLOCK.get(FanDataDrivenFactories.BLOCK_ID));
    }

    private static void bind(RegisterClientExtensionsEvent event, Block block) {
        if (block == null || block == Blocks.AIR) return;
        Item item = block.asItem();
        if (item == Items.AIR) return;
        ItemModelRenderers.register(item, FanFixtures.MODEL, null);
        event.registerItem(ItemModelRenderers.clientExtensions(), item);
    }
}
