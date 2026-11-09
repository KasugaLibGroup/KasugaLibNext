package test.kasuga.modelling;

import io.micronaut.context.annotation.Context;
import lib.kasuga.registration.minecraft.creative_tab.CreativeTabReg;
import lib.kasuga.registration.minecraft.creative_tab.CreativeTabRegModifiers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

@Context
public class ModellingTestCreativeTab {

    public static CreativeTabReg MODELLING_TEST_TAB = new CreativeTabReg("modelling_test")
            .configure(CreativeTabRegModifiers.TabBuilder.of("title",
                builder -> builder.title(Component.translatable("itemGroup.kasuga_lib.modelling_test"))))
            .configure(CreativeTabRegModifiers.TabBuilder.of("icon",
                builder -> builder.icon(() -> new ItemStack(Items.COMMAND_BLOCK))))
            .displayItems((params, output) -> {
                // code-registered fan
                var exampleFan = ModellingContentTest.EXAMPLE_FAN_BLOCK.getEntry();
                if (exampleFan != null) {
                    output.accept(exampleFan);
                }
                // data-driven fan: registered from JSON, so look it up by id
                var dataDrivenFan = BuiltInRegistries.BLOCK.get(FanDataDrivenFactories.BLOCK_ID);
                if (dataDrivenFan != Blocks.AIR) {
                    output.accept(dataDrivenFan);
                }
            })
            .setParent(ModellingTestApplication.registry);
}
