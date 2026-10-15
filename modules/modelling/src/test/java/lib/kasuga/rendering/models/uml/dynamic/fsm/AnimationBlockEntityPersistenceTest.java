package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/fsm-design-review.md block 4.2: the {@link AnimationBlockEntity}'s three programmatic ids
 * ({@code stateMachineId} / {@code modelLoc} / {@code modelName}) round-trip through NBT — the pure
 * serialization logic extracted into {@link AnimationBlockEntity.PersistedIds} /
 * {@link AnimationBlockEntity#writePersistedIds} so it is testable without a real block entity.
 */
class AnimationBlockEntityPersistenceTest {

    private static ResourceLocation rl(String s) {
        return ResourceLocation.parse(s);
    }

    private static Id id(String s) {
        return Id.parse(s);
    }

    @Test
    void roundTripsAllThreeFields() {
        CompoundTag tag = new CompoundTag();
        AnimationBlockEntity.writePersistedIds(tag, id("kasuga_lib:typed"), rl("kasuga_lib:cube.obj"), "cube");
        AnimationBlockEntity.PersistedIds ids = AnimationBlockEntity.PersistedIds.read(tag);
        assertEquals(id("kasuga_lib:typed"), ids.stateMachineId());
        assertEquals(rl("kasuga_lib:cube.obj"), ids.modelLoc());
        assertEquals("cube", ids.modelName());
    }

    @Test
    void nullsAreOmittedAndReadBackNull() {
        CompoundTag tag = new CompoundTag();
        AnimationBlockEntity.writePersistedIds(tag, null, null, null);
        assertTrue(tag.isEmpty(), "null fields must be omitted from the tag");
        AnimationBlockEntity.PersistedIds ids = AnimationBlockEntity.PersistedIds.read(tag);
        assertNull(ids.stateMachineId());
        assertNull(ids.modelLoc());
        assertNull(ids.modelName());
    }

    @Test
    void mixedPresentAndAbsentFields() {
        CompoundTag tag = new CompoundTag();
        AnimationBlockEntity.writePersistedIds(tag, id("kasuga_lib:typed"), null, null);
        AnimationBlockEntity.PersistedIds ids = AnimationBlockEntity.PersistedIds.read(tag);
        assertEquals(id("kasuga_lib:typed"), ids.stateMachineId());
        assertNull(ids.modelLoc());
        assertNull(ids.modelName());
        assertEquals(1, tag.size(), "only the present field is written");
    }
}
