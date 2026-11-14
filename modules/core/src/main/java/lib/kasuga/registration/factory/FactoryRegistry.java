package lib.kasuga.registration.factory;

import com.google.gson.JsonObject;
import lib.kasuga.registration.Reg;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class FactoryRegistry {

    @FunctionalInterface
    public interface BlockFactory {
        Reg<?, Block> create(String id, @Nullable JsonObject params);
    }

    @FunctionalInterface
    public interface ItemFactory {
        Reg<?, Item> create(String id, @Nullable JsonObject params);
    }

    @FunctionalInterface
    public interface BlockEntityFactory {
        Reg<?, ?> create(String id, Supplier<Block[]> validBlocks, @Nullable JsonObject params);
    }

    @FunctionalInterface
    public interface GenericFactory {
        Reg<?, ?> create(String id, JsonObject params);
    }

    private static final Map<String, BlockFactory> BLOCK_REGISTRY = new ConcurrentHashMap<>();
    private static final Set<String> BLOCK_ITEM_FACTORIES = ConcurrentHashMap.newKeySet();
    private static final Map<String, ItemFactory> ITEM_REGISTRY = new ConcurrentHashMap<>();
    private static final Map<String, BlockEntityFactory> BLOCK_ENTITY_REGISTRY = new ConcurrentHashMap<>();
    private static final Map<String, GenericFactory> GENERIC_REGISTRY = new ConcurrentHashMap<>();

    // --- Block ---

    public static void register(String type, BlockFactory factory) {
        register(type, factory, false);
    }

    /**
     * Registers a block factory and records whether it creates a same-id block item.
     */
    public static void register(
            String type,
            BlockFactory factory,
            boolean createsBlockItem
    ) {
        BLOCK_REGISTRY.put(type, factory);
        if (createsBlockItem) {
            BLOCK_ITEM_FACTORIES.add(type);
        } else {
            BLOCK_ITEM_FACTORIES.remove(type);
        }
    }

    public static BlockFactory get(String type) {
        return BLOCK_REGISTRY.get(type);
    }

    public static boolean contains(String type) {
        return BLOCK_REGISTRY.containsKey(type);
    }

    public static boolean createsBlockItem(String type) {
        return BLOCK_ITEM_FACTORIES.contains(type);
    }

    // --- Item ---

    public static void registerItem(String type, ItemFactory factory) {
        ITEM_REGISTRY.put(type, factory);
    }

    public static ItemFactory getItemFactory(String type) {
        return ITEM_REGISTRY.get(type);
    }

    public static boolean containsItem(String type) {
        return ITEM_REGISTRY.containsKey(type);
    }

    // --- Block Entity ---

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("FactoryRegistry");

    public static void registerBlockEntity(String type, BlockEntityFactory factory) {
        LOGGER.info("[registerBlockEntity] type='{}', factory={}", type, factory);
        BLOCK_ENTITY_REGISTRY.put(type, factory);
    }

    public static BlockEntityFactory getBlockEntityFactory(String type) {
        return BLOCK_ENTITY_REGISTRY.get(type);
    }

    public static boolean containsBlockEntity(String type) {
        return BLOCK_ENTITY_REGISTRY.containsKey(type);
    }

    public static Set<String> getBlockEntityTypes() {
        return BLOCK_ENTITY_REGISTRY.keySet();
    }

    // --- Generic ---

    public static void registerGeneric(String type, GenericFactory factory) { GENERIC_REGISTRY.put(type, factory); }

    public static GenericFactory getGeneric(String type) { return GENERIC_REGISTRY.get(type); }

    public static boolean containsGeneric(String type) { return GENERIC_REGISTRY.containsKey(type); }

    private FactoryRegistry() {}
}
