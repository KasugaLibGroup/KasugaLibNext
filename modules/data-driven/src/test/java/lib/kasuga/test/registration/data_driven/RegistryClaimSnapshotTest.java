package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.RegistryGroup;
import lib.kasuga.registration.data_driven.pipeline.RegistryClaim;
import lib.kasuga.registration.data_driven.pipeline.RegistryClaimSnapshot;
import lib.kasuga.registration.minecraft.block.BlockReg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegistryClaimSnapshotTest {
    @Test
    void disabledRegistryGroupAndItsSubtreeDoNotReserveClaims() {
        RegistryGroup root = new RegistryGroup();
        RegistryGroup optional = new RegistryGroup().setParent(root);
        BlockReg<Block> hidden = new BlockReg<>(
                "hidden",
                ignored -> properties -> null
        ).setParent(optional);
        boolean[] enabled = {false};
        optional.dispatchWhen(() -> enabled[0]);

        assertEquals(Set.of(), RegistryClaimSnapshot.collect(root));

        enabled[0] = true;
        assertEquals(
                Set.of(new RegistryClaim.RegistryKey(
                        "minecraft:block",
                        ResourceLocation.fromNamespaceAndPath("minecraft", "hidden")
                )),
                RegistryClaimSnapshot.collect(root)
        );
    }
}
