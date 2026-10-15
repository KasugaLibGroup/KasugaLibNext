package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.data.DataProvider;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

public class ItemDataProvider implements DataProvider {

    protected final ItemStack stack;

    protected final HashMap<String, DataComponentType> dataType;

    protected final DataComponentMap componentMap;

    protected final HashMap<String, Object> data;

    public ItemDataProvider(ItemStack stack) {
        this.stack = stack;
        this.dataType = new HashMap<>();
        this.data = new HashMap<>();
        this.componentMap = stack.getComponents();
        componentMap.keySet().forEach(k -> dataType.put(k.toString(), k));
    }

    protected void collectData() {
        data.put("count", stack.getCount());
        data.put("damage", stack.getDamageValue());
        data.put("maxDamage", stack.getMaxDamage());
        data.put("isDamaged", stack.isDamaged());
        data.put("isEnchanted", stack.isEnchanted());
        data.put("hasFoil", stack.hasFoil());
        data.put("isEmpty", stack.isEmpty());
        data.put("barColor", stack.getBarColor());
        data.put("barWidth", stack.getBarWidth());
        data.put("isBarVisible", stack.isBarVisible());
        data.put("hoverName", stack.getHoverName().getString());
        data.put("descriptionId", stack.getDescriptionId());
        data.put("popTime", stack.getPopTime());
    }

    public String getBranchName(String name) {
        if (name.startsWith("comp.")) {
            return name.substring(5);
        } else if (name.startsWith("component.")) {
            return name.substring(10);
        }
        return name;
    }

    public boolean isComponentSetPatchable() {
        return componentMap instanceof PatchedDataComponentMap;
    }

    @Override
    public @Nullable Object getValue(String name) {
        if (name.startsWith("comp.") || name.startsWith("component.")) {
            String branchName = getBranchName(name);
            if (!dataType.containsKey(branchName)) return null;
            DataComponentType<?> type = dataType.get(branchName);
            if (type == null) return null;
            return componentMap.get(type);
        }
        return data.get(name);
    }

    @Override
    public boolean setValue(String name, Object value) {
        if (name.startsWith("comp.") || name.startsWith("component.")) {
            if (!isComponentSetPatchable()) return false;
            String branchName = getBranchName(name);
            if (!dataType.containsKey(branchName)) return false;
            DataComponentType type = dataType.get(branchName);
            if (type == null) return false;
            try {
                ((PatchedDataComponentMap) componentMap).set(type, value);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean canGet(String name) {
        return data.containsKey(name);
    }

    @Override
    public boolean canSet(String name, Object value) {
        if (name.startsWith("comp.") || name.startsWith("component.")) {
            if (!isComponentSetPatchable()) return false;
            String branchName = getBranchName(name);
            if (!dataType.containsKey(branchName)) return false;
            DataComponentType type = dataType.get(branchName);
            return type != null;
        }
        return false;
    }

    @Override
    public boolean has(String name) {
        if (name.startsWith("comp.") || name.startsWith("component.")) {
            String branchName = getBranchName(name);
            return dataType.containsKey(branchName);
        }
        return data.containsKey(name);
    }

    @Override
    public Class<?> getType(String name) {
        Object value = getValue(name);
        return value == null ? null : value.getClass();
    }
}
