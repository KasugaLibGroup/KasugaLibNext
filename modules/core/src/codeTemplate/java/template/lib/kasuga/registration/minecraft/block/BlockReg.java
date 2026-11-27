package template.lib.kasuga.registration.minecraft.block;

import lib.kasuga.internal.generator.annotations.CodeTemplate;
import lib.kasuga.internal.generator.annotations.RegGenerator;
import lib.kasuga.internal.generator.facades.Inline;
import lib.kasuga.internal.generator.facades.RegFacade;
import lib.kasuga.registration.core.ChildrenUtils;
import lib.kasuga.registration.core.Modifier;
import lib.kasuga.registration.core.ModifierType;
import lib.kasuga.registration.core.ScopeHelper;
import lib.kasuga.registration.minecraft.common.MinecraftDeferRegistryReg;
import lib.kasuga.registration.minecraft.block.shape.BlockShapeBindings;
import lib.kasuga.registration.minecraft.block.shape.BlockShapePlan;
import template.lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import template.lib.kasuga.registration.minecraft.item.ItemReg;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

@CodeTemplate(generator = "Reg")
@RegGenerator(
        modifiers = {
                @RegGenerator.Modifier(
                        type="BlockProperties",
                        target = BlockBehaviour.Properties.class,
                        enumeration = {
                                "mapColor",
                                "noCollission",
                                "noOcclusion",
                                "friction",
                                "speedFactor",
                                "jumpFactor",
                                "sound",
                                "lightLevel",
                                "strength",
                                "randomTicks",
                                "dynamicShape",
                                "noLootTable",
                                "dropsLike",
                                "lootFrom",
                                "ignitedByLava",
                                "liquid",
                                "forceSolidOn",
                                "pushReaction",
                                "air",
                                "isValidSpawn",
                                "isRedstoneConductor",
                                "isSuffocating",
                                "isViewBlocking",
                                "hasPostProcess",
                                "emissiveRendering",
                                "requiresCorrectToolForDrops",
                                "destroyTime",
                                "explosionResistance",
                                "offsetType",
                                "noTerrainParticles",
                                "requiredFeatures",
                                "instrument",
                                "replaceable"
                        }
                )
        }
)
public class BlockReg<T extends Block> extends MinecraftDeferRegistryReg<BlockReg<T>, Block, T> {
    public static final ModifierType<Block> SCOPE = new ModifierType<>(true);

    private final Function<BlockReg<T>, Function<BlockBehaviour.Properties, T>> supplier;


    public BlockReg(String name, @RegGenerator.SelfReference() Function<BlockReg<T>, Function<BlockBehaviour.Properties, T>> supplier) {
        super(name, Registries.BLOCK);
        this.supplier = supplier;
        this.configure(ScopeHelper.effect(SCOPE, this::getEntry));
    }

    @Override
    protected T createObject(ResourceLocation id) {
        BlockBehaviour.Properties properties = RegFacade.transformObject("BlockProperties", BlockBehaviour.Properties.of());
        properties = this.applyProperties(BlockBehaviour.Properties.class, properties);
        BlockShapePlan shapePlan = this.applyProperties(BlockShapePlan.class, BlockShapePlan.empty());
        T block = supplier.apply(this).apply(properties);
        BlockShapeBindings.install(block, shapePlan);
        return block;
    }

    /**
     * Supplies the already compiled plan for this registration and its inherited group properties.
     * Installation happens immediately after construction and before state cache initialization.
     */
    public BlockReg<T> withShapePlan(BlockShapePlan plan) {
        return withProperty(BlockShapePlan.class, ignored -> plan);
    }

//    @RegGenerator.ChildrenConfiguration()
//    public <O extends BlockEntity> BlockEntityReg<O> withBlockEntity(String name, BlockEntityType.BlockEntitySupplier<O> supplier) {
//        BlockEntityReg<O> blockEntityReg = new BlockEntityReg<>(name, i -> supplier);
//        blockEntityReg.configure((Modifier<?>) RegFacade.modifier(BlockEntityReg.class,"validBlocks", (Supplier<Collection<Block>>)()-> {
//            ArrayList<BlockReg<?>> l = new ArrayList<>();
//            ChildrenUtils.traverse(this, (r)->{
//                if(r instanceof BlockReg<?> br) {
//                    l.add(br);
//                }
//            });
//            return l.stream().map(i->(Block) i.getEntry()).toList();
//        }));
//        return blockEntityReg;
//    }

    @RegGenerator.ChildrenConfiguration()
    public ItemReg<BlockItem> withDefaultBlockItem(String name) {
        return new ItemReg<>(name, i-> p->new BlockItem(i.transform(BlockReg.SCOPE, null), p));
    }

    @RegGenerator.ChildrenConfiguration()
    public ItemReg<BlockItem> withBlockItem(String name, Supplier<BiFunction<Block, Item.Properties, BlockItem>> supplier) {
        return new ItemReg<>(name, i->p->supplier.get().apply(i.transform(BlockReg.SCOPE, null), p));
    }
}
