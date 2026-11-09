package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.state.StateProperty;
import lib.kasuga.registration.data_driven.state.StatePropertyContext;
import lib.kasuga.registration.data_driven.state.StatePropertySet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StatePropertyApiTest {
    private static Block fixture() {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kasuga_lib:stateful_fixture"));
        assertNotSame(Blocks.AIR, block);
        return block;
    }

    @Test
    void typedPropertyReferenceReadsAndChangesTheJsonBlockState() {
        Block block = fixture();
        StateProperty<Boolean> active = StateProperty.of(block, "active", Boolean.class);
        StateProperty<Integer> level = StateProperty.of(block, "level", Integer.class);
        BlockState initial = block.defaultBlockState();
        assertTrue(active.get(initial));
        BlockState changed = active.with(initial, false);
        assertFalse(active.get(changed));
        assertTrue(active.get(initial), "BlockState must remain immutable");
        assertEquals(2, level.get(changed));
        assertSame(block.getStateDefinition().getProperty("active"), active.property());
    }

    @Test
    void typedPropertyRejectsMismatchedBlockTypeAndValues() {
        Block block = fixture();
        assertThrows(IllegalArgumentException.class, () -> StateProperty.of(block, "missing", Boolean.class));
        assertThrows(IllegalArgumentException.class, () -> StateProperty.of(block, "level", Boolean.class));
        StateProperty<Integer> level = StateProperty.of(block, "level", Integer.class);
        assertThrows(IllegalArgumentException.class, () -> level.get(Blocks.STONE.defaultBlockState()));
        assertThrows(IllegalArgumentException.class, () -> level.with(block.defaultBlockState(), 4));
        StateProperty<String> mode = StateProperty.of(block, "mode", String.class);
        assertThrows(IllegalArgumentException.class, () -> mode.with(block.defaultBlockState(), "missing"));
    }

    @Test
    void javaBuilderMatchesJsonDefaultsAndKeepsVanillaPropertyIdentity() {
        StatePropertySet fromJava = StatePropertySet.builder()
                .booleanProperty("active", true)
                .integerProperty("stage", 0, 3, 2)
                .stringProperty("mode", List.of("idle", "running"), "running")
                .add(BlockStateProperties.OPEN, false)
                .build();
        StatePropertySet fromJson = StatePropertySet.parse(JsonParser.parseString("""
                {"active":{"type":"boolean","default":true},
                 "stage":{"type":"integer","min":0,"max":3,"default":2},
                 "mode":{"type":"enum","values":["idle","running"],"default":"running"},
                 "open":{"type":"vanilla","property":"OPEN","default":false}}
                """));
        BlockState javaState = makeState(fromJava);
        BlockState jsonState = makeState(fromJson);
        assertEquals(32, fromJava.properties().stream().mapToInt(p -> p.getPossibleValues().size()).reduce(1, (a, b) -> a * b));
        assertSame(BlockStateProperties.OPEN, javaState.getProperties().stream()
                .filter(p -> p.getName().equals("open")).findFirst().orElseThrow());
        for (String name : List.of("active", "stage", "mode", "open")) {
            assertEquals(serializedValue(jsonState, name), serializedValue(javaState, name), name);
        }
    }

    @Test
    void builderRejectsInvalidDefinitionsAndSnapshotsDoNotChange() {
        StatePropertySet.Builder builder = StatePropertySet.builder().booleanProperty("active", false);
        StatePropertySet first = builder.build();
        builder.integerProperty("stage", 0, 3, 1);
        assertEquals(1, first.properties().size());
        assertEquals(2, builder.build().properties().size());
        assertThrows(IllegalArgumentException.class, () -> builder.booleanProperty("active", true));
        assertThrows(IllegalArgumentException.class, () -> StatePropertySet.builder().integerProperty("x", 0, 99, 0));
        assertThrows(IllegalArgumentException.class,
                () -> StatePropertySet.builder().stringProperty("mode", List.of("same", "same"), "same"));
        assertThrows(IllegalArgumentException.class,
                () -> StatePropertySet.builder().stringProperty("mode", List.of("on", "off"), "missing"));
        assertThrows(IllegalArgumentException.class,
                () -> StatePropertySet.builder().add(BlockStateProperties.OPEN, null));
    }

    @Test
    void builderRejectsAPropertyWhoseValuesCannotBeReadBack() {
        Property<String> broken = new Property<>("broken", String.class) {
            @Override public List<String> getPossibleValues() { return List.of("one", "two"); }
            @Override public String getName(String value) { return value; }
            @Override public Optional<String> getValue(String value) { return Optional.empty(); }
        };
        assertThrows(IllegalArgumentException.class, () -> StatePropertySet.builder().add(broken, "one"));
    }

    private static BlockState makeState(StatePropertySet properties) {
        return StatePropertyContext.construct(properties, () -> {
            StateDefinition.Builder<Block, BlockState> builder = new StateDefinition.Builder<>(Blocks.STONE);
            StatePropertyContext.addTo(builder);
            return StatePropertyContext.applyDefaults(builder.create(Block::defaultBlockState, BlockState::new).any());
        });
    }

    private static String serializedValue(BlockState state, String name) {
        Property<?> property = state.getProperties().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
        return serializedValue(state, property);
    }

    private static <T extends Comparable<T>> String serializedValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
