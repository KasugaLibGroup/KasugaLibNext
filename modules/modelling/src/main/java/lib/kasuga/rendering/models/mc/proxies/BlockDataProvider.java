package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.mc.proxies.reflect.FieldHolder;
import lib.kasuga.rendering.models.mc.proxies.reflect.InstanceProbe;
import lib.kasuga.rendering.models.uml.dynamic.data.DataProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

public class BlockDataProvider implements DataProvider {

    protected final HashMap<String, Property<?>> properties;

    protected final BlockState state;

    protected final @Nullable InstanceProbe.Result<BlockEntity> blockEntityData;

    protected final HashMap<String, Object> data;

    public BlockDataProvider(Level level, BlockPos pos, BlockState state, boolean removeFinal) {
        this.properties = new HashMap<>();
        this.data = new HashMap<>();
        this.state = state;
        state.getProperties().forEach(property ->
                this.properties.put(property.getName(), property)
        );
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) {
            this.blockEntityData = null;
            return;
        }
        InstanceProbe<BlockEntity> probe = new InstanceProbe<>(BlockEntity.class, removeFinal);
        this.blockEntityData = probe.probe(entity);
        this.data.put("x", pos.getX());
        this.data.put("y", pos.getY());
        this.data.put("z", pos.getZ());
    }

    protected void collectData() {

    }

    @Override
    public @Nullable Object getValue(String name) {
        Object value = getBranchHolderValue(name, null);
        if (value instanceof Property<?> property) {
            return state.getValue(property);
        } else if (value instanceof FieldHolder holder) {
            if (holder.cantAccess(name)) return null;
            try {
                return holder.get(blockEntityData.instance);
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return value;
    }

    @Override
    public boolean setValue(String name, Object value) {
        Object valueHolder = getBranchHolderValue(name, null);
        return switch (valueHolder) {
            case null -> false;
            case Property property -> {
                if (!state.getProperties().contains(property)) yield false;
                try {
                    state.setValue(property, (Comparable) value);
                    yield true;
                } catch (Exception e) {
                    yield false;
                }
            }
            case FieldHolder holder -> {
                if (holder.cantAccess(name)) yield false;
                try {
                    holder.getField().set(blockEntityData.instance, value);
                    yield true;
                } catch (IllegalAccessException e) {
                    yield false;
                }
            }
            default -> {
                Class holderClass = valueHolder.getClass();
                if (holderClass.isAssignableFrom(value.getClass())) {
                    data.put(name, value);
                    yield true;
                }
                yield false;
            }
        };
    }

    @Override
    public boolean canGet(String name) {
        return getBranchHolderValue(name, null) != null;
    }

    @Override
    public boolean canSet(String name, Object value) {
        Object valueHolder = getBranchHolderValue(name, null);
        return switch (valueHolder) {
            case null -> false;
            case Property<?> property ->
                    state.getProperties().contains(property) && property.getValueClass().isAssignableFrom(value.getClass());
            case FieldHolder holder -> !holder.isFinal() && holder.getType().isAssignableFrom(value.getClass());
            default -> valueHolder.getClass().isAssignableFrom(value.getClass());
        };
    }

    @Override
    public boolean has(String name) {
        return getBranchHolderValue(name, null) != null;
    }

    @Override
    public Class<?> getType(String name) {
        Object value = getValue(name);
        return value == null ? null : value.getClass();
    }

    protected @Nullable HashMap getBranch(String name) {
        if (name.startsWith("prop.")) {
            return properties;
        } else if (name.startsWith("be.")) {
            return blockEntityData != null ? blockEntityData.fieldMap : null;
        }
        return data;
    }

    protected String getBranchKey(String name) {
        if (name.startsWith("prop.")) {
            return name.substring(5);
        } else if (name.startsWith("be.")) {
            return name.substring(3);
        }
        return name;
    }

    protected Object getBranchHolderValue(String name, @Nullable Object defaultValue) {
        HashMap dataBranch = getBranch(name);
        if (dataBranch == null) return defaultValue;

        String branchKey = getBranchKey(name);
        return dataBranch.getOrDefault(branchKey, defaultValue);
    }
}
