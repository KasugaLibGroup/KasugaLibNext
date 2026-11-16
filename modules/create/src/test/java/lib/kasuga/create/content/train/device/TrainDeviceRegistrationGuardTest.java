package lib.kasuga.create.content.train.device;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-04：未注册类型 fail fast，类型持有稳定 id，写档路径不 NPE。
 */
class TrainDeviceRegistrationGuardTest {

    static class DummyDevice extends TrainDeviceSystem {
        DummyDevice(TrainDeviceManager manager) {
            super(manager);
        }
    }

    private static int counter;

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_tds_registry", name + "_" + (counter++));
    }

    private static TrainDeviceSystemType<DummyDevice> unregistered(String name) {
        return TrainDeviceSystemType.of(DummyDevice::new);
    }

    @Test
    void unregisteredTypeFailsFastWithTheCallSite() {
        TrainDeviceManager manager = new TrainDeviceManager(null);
        TrainDeviceSystemType<DummyDevice> type = unregistered("never_registered");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> manager.getOrCreateSystem(type));
        assertTrue(error.getMessage().contains("未注册"), error.getMessage());
        assertTrue(error.getMessage().contains("TrainDeviceRegistrationGuardTest"),
                "异常信息里要带上调用点，方便定位：" + error.getMessage());
    }

    @Test
    void registrationAssignsAStableIdToTheType() {
        ResourceLocation typeId = id("bound");
        TrainDeviceSystemType<DummyDevice> type = TrainDeviceSystemType.of(typeId, DummyDevice::new);
        TrainDeviceRegistry.register(typeId, type);

        assertEquals(typeId, type.id());
        assertEquals(typeId, TrainDeviceRegistry.getKey(type));
        assertEquals(type, TrainDeviceRegistry.get(typeId));
    }

    @Test
    void registrationBindsTheIdEvenWithTheLegacyFactoryOnlyConstructor() {
        ResourceLocation typeId = id("legacy_binding");
        TrainDeviceSystemType<DummyDevice> type = TrainDeviceSystemType.of(DummyDevice::new);
        assertNull(type.id(), "注册前没有 id");

        TrainDeviceRegistry.register(typeId, type);

        assertEquals(typeId, type.id(), "register 时自动绑定稳定 id");
        assertEquals(typeId, TrainDeviceRegistry.getKey(type));
    }

    @Test
    void bindingAnAlreadyBoundTypeToADifferentIdIsRejected() {
        ResourceLocation first = id("first");
        ResourceLocation second = id("second");
        TrainDeviceSystemType<DummyDevice> type = TrainDeviceSystemType.of(first, DummyDevice::new);

        assertThrows(IllegalStateException.class, () -> TrainDeviceRegistry.register(second, type),
                "类型自带 id 时不能再注册到另一个 id");
    }

    @Test
    void writeFailsFastOnUnregisteredSystemsInsteadOfSilentlyDroppingThem() {
        TrainDeviceManager manager = new TrainDeviceManager(null);
        TrainDeviceSystemType<DummyDevice> unregistered = unregistered("smuggled");
        // 下游理论上可以绕过 getOrCreateSystem 直接塞进容器（字段是 protected）。
        manager.systems.put(unregistered, unregistered.create(manager));

        CompoundTag tag = new CompoundTag();
        assertThrows(IllegalStateException.class, () -> manager.write(tag),
                "写下去必然丢数据时要中止写档，不能静默跳过");
        assertTrue(tag.getList("TrainDeviceSystems", Tag.TAG_COMPOUND).isEmpty(),
                "抛异常时不能留下半份存档");
    }

    @Test
    void getSystemReturnsNullForUnregisteredType() {
        TrainDeviceManager manager = new TrainDeviceManager(null);
        assertNull(manager.getSystem(unregistered("absent")));
    }
}
