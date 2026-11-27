package lib.kasuga.test;

import lib.kasuga.KasugaLib;
import lib.kasuga.test.registration.ConfigurationTest;
import lib.kasuga.test.registration.MinecraftRegistrationTest;
import lib.kasuga.test.registration.minecraft.RenderingRegistryTest;
import lib.kasuga.test.registration.minecraft.block.BlockRegistryTest;
import lib.kasuga.test.registration.minecraft.block.shape.ShapeBindingTestBlocks;
import lib.kasuga.test.registration.minecraft.block_entity.BlockEntityRegistryTest;
import lib.kasuga.test.registration.minecraft.effect.EffectRegistryTest;
import lib.kasuga.test.registration.minecraft.entity.EntityRegistryTest;
import lib.kasuga.test.registration.minecraft.item.ItemRegistryTest;
import lib.kasuga.test.registration.minecraft.payload.PayloadRegistryTest;
import lib.kasuga.test.registration.minecraft.sound.SoundRegistryTest;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(KasugaLib.MODID)
public class KasugaLibTest {
    public KasugaLibTest(IEventBus modEventBus, ModContainer modContainer) {
        // Register all test registrations with the mod event bus
        BlockRegistryTest.registry.register(modEventBus);
        ShapeBindingTestBlocks.REGISTRY.register(modEventBus);
        ItemRegistryTest.registry.register(modEventBus);
        EffectRegistryTest.registry.register(modEventBus);
        EntityRegistryTest.registry.register(modEventBus);
        SoundRegistryTest.registry.register(modEventBus);
        BlockEntityRegistryTest.registry.register(modEventBus);
        MinecraftRegistrationTest.registry.register(modEventBus);
        ConfigurationTest.registry.register(modEventBus);
        RenderingRegistryTest.registry.register(modEventBus);
        PayloadRegistryTest.registry.register(modEventBus);
    }
}
