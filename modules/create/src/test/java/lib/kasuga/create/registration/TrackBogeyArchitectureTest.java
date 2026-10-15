package lib.kasuga.create.registration;

import lib.kasuga.create.registration.bogey.BogeyBlockReg;
import lib.kasuga.create.registration.bogey.BogeyGroupReg;
import lib.kasuga.create.registration.bogey.BogeyMetadataIndex;
import lib.kasuga.create.registration.bogey.BogeySizeReg;
import lib.kasuga.create.registration.track.TrackMaterialMetadataIndex;
import lib.kasuga.create.registration.track.TrackMaterialReg;
import lib.kasuga.create.registration.track.TrackReg;
import org.junit.jupiter.api.Test;
import com.simibubi.create.content.trains.track.TrackBlock;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.WeakHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackBogeyArchitectureTest {

    @Test
    void metadataIndexUsesWeakKeys() {
        assertEquals(WeakHashMap.class, TrackMaterialMetadataIndex.backingMapType());
        Class<?> holder = Arrays.stream(BogeyMetadataIndex.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("Holder"))
                .findFirst()
                .orElseThrow();
        assertTrue(Arrays.stream(holder.getDeclaredFields())
                .allMatch(field -> field.getType() == WeakHashMap.class));
    }

    @Test
    void trackOwnsBlockAndItemAsChildrenWithoutInheritingBlockReg() {
        TrackMaterialReg material = new TrackMaterialReg("composition_material")
                .particle(ResourceLocation.withDefaultNamespace("block/stone"));
        TrackReg<TrackBlock> track = new TrackReg<>("composition_track", material, TrackBlock::new);

        assertFalse(lib.kasuga.registration.minecraft.block.BlockReg.class.isAssignableFrom(TrackReg.class));
        assertEquals(2, track.getChildren().size());
        assertTrue(track.getChildren().contains(track.getBlockReg()));
        assertTrue(track.getChildren().contains(track.getItemReg()));
    }

    @Test
    void commonRegistrationClassesDoNotLinkClientPackages() throws IOException {
        for (Class<?> type : List.of(
                TrackMaterialReg.class,
                TrackReg.class,
                BogeySizeReg.class,
                BogeyBlockReg.class,
                BogeyGroupReg.class,
                CreateRegistrationEvent.class,
                CreateRegistrationState.class
        )) {
            String bytecode = readBytecode(type);
            assertFalse(bytecode.contains("net/minecraft/client/"), type.getName());
            assertFalse(bytecode.contains("net/neoforged/neoforge/client/"), type.getName());
            assertFalse(bytecode.contains("com/mojang/blaze3d/"), type.getName());
            assertFalse(bytecode.contains("dev/engine_room/flywheel/api/"), type.getName());
        }
    }

    private static String readBytecode(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = type.getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IOException("Missing class resource " + resourceName);
            }
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
