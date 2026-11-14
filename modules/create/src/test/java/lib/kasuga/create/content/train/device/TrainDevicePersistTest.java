package lib.kasuga.create.content.train.device;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-03：设备状态默认能存住；读档不清空上下文设备；缺键补建；未知类型保留；迁移表生效。
 */
class TrainDevicePersistTest {

    static class ValueDevice extends TrainDeviceSystem {
        int value;
        boolean failOnRead;

        ValueDevice(TrainDeviceManager manager) {
            super(manager);
        }

        @Override
        protected void writeData(CompoundTag data) {
            data.putInt("Value", value);
        }

        @Override
        protected void readData(CompoundTag data, int version) {
            if (failOnRead) {
                throw new IllegalStateException("bad data");
            }
            value = data.getInt("Value");
        }
    }

    private static int counter;

    private static TrainDeviceSystemType<ValueDevice> register(String name, boolean context) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("kasuga_tds_persist", name + "_" + (counter++));
        TrainDeviceSystemType<ValueDevice> type = TrainDeviceSystemType.of(ValueDevice::new);
        TrainDeviceRegistry.register(id, type, context);
        return type;
    }

    private static CompoundTag entry(ResourceLocation id, CompoundTag payload) {
        CompoundTag tag = payload.copy();
        tag.putString("Type", id.toString());
        return tag;
    }

    private static CompoundTag tagWith(CompoundTag... entries) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (CompoundTag e : entries) {
            list.add(e);
        }
        tag.put("TrainDeviceSystems", list);
        return tag;
    }

    @Test
    void writeThenReadKeepsDeviceStateAndStampsTheSchemaVersion() {
        TrainDeviceSystemType<ValueDevice> type = register("roundtrip", false);
        TrainDeviceManager source = new TrainDeviceManager(null);
        source.getOrCreateSystem(type).value = 7;

        CompoundTag tag = new CompoundTag();
        source.write(tag);

        assertEquals(TrainDeviceManager.CURRENT_FORMAT, tag.getInt("TrainDeviceSystemsFormat"));
        CompoundTag written = tag.getList("TrainDeviceSystems", Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(TrainDeviceSystem.CURRENT_SCHEMA_VERSION, written.getInt("SchemaVersion"),
                "即使设备没显式写版本，基类也要写 schemaVersion");
        assertTrue(written.contains("Data", Tag.TAG_COMPOUND), "设备字段应落在 Data 子 tag");

        TrainDeviceManager restored = new TrainDeviceManager(null);
        restored.read(tag);

        ValueDevice device = restored.getSystem(type);
        assertNotNull(device);
        assertEquals(7, device.value);
    }

    @Test
    void legacyFlatTagWithoutDataOrVersionIsReadable() {
        TrainDeviceSystemType<ValueDevice> type = register("legacy", false);
        CompoundTag legacyPayload = new CompoundTag();
        legacyPayload.putInt("Value", 3);

        CompoundTag tag = tagWith(entry(TrainDeviceRegistry.getKey(type), legacyPayload));

        TrainDeviceManager manager = new TrainDeviceManager(null);
        manager.read(tag);

        ValueDevice device = manager.getSystem(type);
        assertNotNull(device);
        assertEquals(3, device.value, "无 Data / 无 SchemaVersion 的旧档必须能读出来");
    }

    @Test
    void missingSystemsKeyRebuildsContextDevicesInsteadOfLosingThem() {
        TrainDeviceSystemType<ValueDevice> contextType = register("context", true);
        TrainDeviceManager manager = new TrainDeviceManager(null);
        ValueDevice contextDevice = manager.getSystem(contextType);
        assertNotNull(contextDevice, "构造期就应创建上下文设备");
        contextDevice.value = 42;

        manager.read(new CompoundTag());

        assertNotNull(manager.getSystem(contextType), "缺少 TrainDeviceSystems 键时上下文设备必须补建");
        assertEquals(42, manager.getSystem(contextType).value, "补建应复用构造期实例，不丢已有状态");
    }

    @Test
    void missingContextEntryInSaveIsRebuilt() {
        TrainDeviceSystemType<ValueDevice> contextType = register("context_missing", true);
        CompoundTag tag = tagWith();

        TrainDeviceManager manager = new TrainDeviceManager(null);
        manager.read(tag);

        assertNotNull(manager.getSystem(contextType), "存档里缺条的上下文设备应补建");
    }

    @Test
    void unknownTypeIsSkippedButPreservedOnWrite() {
        CompoundTag unknownPayload = new CompoundTag();
        unknownPayload.putString("Type", "kasuga_tds_unknown:not_installed");
        unknownPayload.putInt("Keep", 5);

        TrainDeviceManager manager = new TrainDeviceManager(null);
        assertDoesNotThrow(() -> manager.read(tagWith(unknownPayload)));

        CompoundTag out = new CompoundTag();
        manager.write(out);

        ListTag list = out.getList("TrainDeviceSystems", Tag.TAG_COMPOUND);
        boolean preserved = false;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if ("kasuga_tds_unknown:not_installed".equals(entry.getString("Type"))) {
                preserved = true;
                assertEquals(5, entry.getInt("Keep"), "未知类型条目应原样保留（卸模组后数据不丢）");
            }
        }
        assertTrue(preserved, "未知类型条目必须在写档时回写");
    }

    @Test
    void oneBrokenEntryDoesNotBreakTheOthers() {
        TrainDeviceSystemType<ValueDevice> brokenType = register("broken", false);
        TrainDeviceSystemType<ValueDevice> healthyType = register("healthy", false);

        TrainDeviceSystemType<ValueDevice> brokenFactory = TrainDeviceSystemType.of(manager -> {
            ValueDevice device = new ValueDevice(manager);
            device.failOnRead = true;
            return device;
        });
        TrainDeviceRegistry.register(ResourceLocation.fromNamespaceAndPath("kasuga_tds_persist", "broken_factory"), brokenFactory);

        CompoundTag brokenPayload = new CompoundTag();
        brokenPayload.putInt("Value", 1);
        CompoundTag healthyPayload = new CompoundTag();
        healthyPayload.putInt("Value", 9);

        CompoundTag tag = tagWith(
                entry(TrainDeviceRegistry.getKey(brokenFactory), brokenPayload),
                entry(TrainDeviceRegistry.getKey(healthyType), healthyPayload));

        TrainDeviceManager manager = new TrainDeviceManager(null);
        assertDoesNotThrow(() -> manager.read(tag));

        assertEquals(9, manager.getSystem(healthyType).value, "坏掉的那条不能影响其它设备");
        assertNotNull(manager.getSystem(brokenFactory), "读失败的条目应重置为默认实例而不是消失");
        assertEquals(0, manager.getSystem(brokenFactory).value);
        assertNull(manager.getSystem(brokenType));
    }

    @Test
    void migrationsRunFromTheStoredVersion() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("kasuga_tds_persist", "migrating");
        TrainDeviceSystemType<ValueDevice> type = TrainDeviceSystemType.<ValueDevice>of(ValueDevice::new)
                .addMigration(0, data -> data.putInt("Value", data.getInt("Value") + 100));
        TrainDeviceRegistry.register(id, type);

        CompoundTag legacyPayload = new CompoundTag();
        legacyPayload.putInt("Value", 3);

        TrainDeviceManager manager = new TrainDeviceManager(null);
        manager.read(tagWith(entry(id, legacyPayload)));

        assertEquals(103, manager.getSystem(type).value, "旧档（版本 0）要跑一遍迁移表");
    }

    @Test
    void newerStoredVersionStillReadsWithoutThrowing() {
        TrainDeviceSystemType<ValueDevice> type = register("future", false);
        CompoundTag payload = new CompoundTag();
        payload.putInt("Value", 11);
        payload.putInt("SchemaVersion", TrainDeviceSystem.CURRENT_SCHEMA_VERSION + 5);

        TrainDeviceManager manager = new TrainDeviceManager(null);
        assertDoesNotThrow(() -> manager.read(tagWith(entry(TrainDeviceRegistry.getKey(type), payload))));
        assertEquals(11, manager.getSystem(type).value, "更高版本的存档按当前实现尽力读取");
    }
}
