package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

class ProxyModelSelectionTest {

    // Vanilla block states need the registry bootstrap supplied by the NeoForge test task.
    @Test
    @Tag("minecraft-resource-integration")
    void selectsAWholeStableModelGroupFromBlockState() {
        CompiledModelGroup retracted = group(
                "retracted", "models/retracted");
        CompiledModelGroup extended = group(
                "extended", "models/extended");
        Map<String, CompiledModelGroup> groups = new LinkedHashMap<>();
        groups.put(retracted.id(), retracted);
        groups.put(extended.id(), extended);
        ProxyModelSelection selection = new ProxyModelSelection(
                retracted,
                groups,
                List.of(new ProxyModelSelection.Rule(
                        BlockStateProperties.POWERED.getName(),
                        Map.of("false", retracted, "true", extended)
                ))
        );
        BlockState retractedState = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, false);
        BlockState extendedState = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, true);

        selection.validate(retractedState);

        assertSame(retracted, selection.resolve(retractedState));
        assertSame(extended, selection.resolve(extendedState));
    }

    private static CompiledModelGroup group(String id, String path) {
        return CompiledModelGroup.single(
                id,
                new ModelPart(
                        ResourceLocation.fromNamespaceAndPath("test", path),
                        null,
                        new Transform(),
                        null
                )
        );
    }
}
