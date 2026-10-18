package test.kasuga.modelling;

import io.micronaut.context.annotation.Context;
import lib.kasuga.registration.minecraft.creative_tab.CreativeTabReg;
import lib.kasuga.registration.minecraft.creative_tab.CreativeTabRegModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

@Context
public class ModellingTestCreativeTab {

    public static CreativeTabReg MODELLING_TEST_TAB = new CreativeTabReg("modelling_test")
            .configure(CreativeTabRegModifiers.TabBuilder.of("title",
                builder -> builder.title(Component.translatable("itemGroup.kasuga_lib.modelling_test"))))
            .configure(CreativeTabRegModifiers.TabBuilder.of("icon",
                builder -> builder.icon(() -> new ItemStack(Items.COMMAND_BLOCK))))
            .displayItems((params, output) -> {
                var block = ModellingContentTest.TEST_FAN_BE_BLOCK.getEntry();
                if (block != null) {
                    output.accept(block);
                }
                var fanFormulaBlock = ModellingContentTest.TEST_FAN_FORMULA_BLOCK.getEntry();
                if (fanFormulaBlock != null) {
                    output.accept(fanFormulaBlock);
                }
            })
            .setParent(ModellingTestApplication.registry);
}
