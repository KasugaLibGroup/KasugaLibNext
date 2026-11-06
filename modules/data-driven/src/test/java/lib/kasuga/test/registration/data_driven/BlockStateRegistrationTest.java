package lib.kasuga.test.registration.data_driven;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the shipped contentTesting index through the real NeoForge registry. */
class BlockStateRegistrationTest {
    private static Block block(String path) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("kasuga_lib", path));
        assertNotSame(Blocks.AIR, block, "the indexed fixture must actually register: " + path);
        return block;
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonBlockHasUsableDefaultStatesAndNbtRoundTrip() {
        Block block = block("stateful_fixture");
        BooleanProperty open = BlockStateProperties.OPEN;
        assertSame(open, block.getStateDefinition().getProperty("open"));
        BooleanProperty active = (BooleanProperty) block.getStateDefinition().getProperty("active");
        IntegerProperty level = (IntegerProperty) block.getStateDefinition().getProperty("level");
        Property<String> mode = (Property<String>) block.getStateDefinition().getProperty("mode");
        assertEquals(192, block.getStateDefinition().getPossibleStates().size());

        BlockState initial = block.defaultBlockState();
        assertFalse(initial.getValue(open));
        assertTrue(initial.getValue(active));
        assertEquals(2, initial.getValue(level));
        assertEquals("working", initial.getValue(mode));
        assertEquals(Direction.NORTH, initial.getValue(BlockStateProperties.HORIZONTAL_FACING));

        BlockState changed = initial.setValue(open, true).setValue(active, false).setValue(level, 3)
                .setValue(mode, "paused").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        CompoundTag tag = NbtUtils.writeBlockState(changed);
        assertEquals("paused", tag.getCompound("Properties").getString("mode"));
        assertEquals("true", tag.getCompound("Properties").getString("open"));
        assertSame(changed, NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag));
        assertSame(changed, NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag));
    }

    @Test
    void customFactoryKeepsNativePropertyAndAddsJsonProperty() {
        Block block = block("stateful_custom_fixture");
        BooleanProperty active = (BooleanProperty) block.getStateDefinition().getProperty("active");
        assertSame(BlockStateProperties.POWERED, block.getStateDefinition().getProperty("powered"));
        assertFalse(block.defaultBlockState().getValue(BlockStateProperties.POWERED));
        assertTrue(block.defaultBlockState().getValue(active));
        assertEquals(4, block.getStateDefinition().getPossibleStates().size());
    }
}
