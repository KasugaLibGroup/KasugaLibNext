package lib.kasuga.registration.data_driven.context;

import lib.kasuga.registration.Reg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;

public class RegBuildContext extends BuildContext {

    private final Map<String, JsonRegistryGroup> registryGroups = new LinkedHashMap<>();
    private final Map<String, ResourceLocation> registryGroupCreativeTabs = new LinkedHashMap<>();
    private final Map<String, Map<String, Reg<?, ?>>> regStore = new LinkedHashMap<>();

    public RegBuildContext(String modId, JsonRegistryGroup rootGroup) {
        super(modId, rootGroup);
    }

    public RegBuildContext(String modId, JsonRegistryGroup rootGroup, boolean strict) {
        super(modId, rootGroup, strict);
    }

    // --- RegistryGroup ---

    public void putRegistryGroup(String id, JsonRegistryGroup group) {
        registryGroups.put(id, group);
    }

    public JsonRegistryGroup getRegistryGroup(String id) {
        return registryGroups.get(id);
    }

    public void setRegistryGroupCreativeTab(String groupId, ResourceLocation tab) {
        registryGroupCreativeTabs.put(groupId, tab);
    }

    public ResourceLocation getRegistryGroupCreativeTab(String groupId) {
        return registryGroupCreativeTabs.get(groupId);
    }

    // --- Reg ---

    public void putReg(String typeName, String id, Reg<?, ?> reg) {
        regStore.computeIfAbsent(typeName, k -> new LinkedHashMap<>()).put(id, reg);
    }

    public Reg<?, ?> getReg(String typeName, String id) {
        Map<String, Reg<?, ?>> map = regStore.get(typeName);
        return map != null ? map.get(id) : null;
    }

    @SuppressWarnings("unchecked")
    public Reg<?, Block> getBlockReg(String id) {
        return (Reg<?, Block>) getReg("blocks", id);
    }
}
