package test.kasuga.create.bogey;

import lib.kasuga.create.proxy.BogeyModels;
import lib.kasuga.create.registration.bogey.BogeyBlockReg;
import lib.kasuga.create.registration.bogey.BogeyGroupReg;
import lib.kasuga.create.registration.bogey.BogeyRendererHandle;
import lib.kasuga.create.registration.bogey.BogeySizeReg;
import lib.kasuga.registration.Registry;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import test.kasuga.create.track.ExampleTrackMaterial;

import java.util.Set;

public final class AllExampleBogey {
    /**
     * Camouflage model drawn by the pipeline; OBJ routes to the obj pipeline by extension.
     * Offsets come from the exported bounds: pk209p_main is centred on its origin (y -0.37..0.41),
     * bogey_cw2_temple starts at y 0.5 (origin at its base), so each needs a different drop.
     */
    private static final ResourceLocation PK209P_MODEL = ResourceLocation.fromNamespaceAndPath(
            "kasuga_lib", "models/block/bogey/pk209p/pk209p_main.obj");
    private static final ResourceLocation CW2_MODEL = ResourceLocation.fromNamespaceAndPath(
            "kasuga_lib", "models/block/bogey/cw2/bogey_cw2_temple.obj");

    public static final BogeySizeReg PK209P = new BogeySizeReg("pk209p", 0.915f / 2f);
    public static final BogeySizeReg CW2 = new BogeySizeReg("cw2", 0.915f / 2f);

    public static final BogeyRendererHandle PK209P_RENDERER = renderer("pk209p");
    public static final BogeyRendererHandle CW2_RENDERER = renderer("cw2");

    public static final BogeyBlockReg<ExampleBogeyBlock> STANDARD_BOGEY = new BogeyBlockReg<>(
            "pk209p_bogey",
            PK209P,
            (properties, size) -> new ExampleBogeyBlock(
                    properties,
                    size,
                    AllExampleBogey::standardStyle,
                    AllExampleBogey::standardBlockEntityType,
                    () -> Set.of(
                            ExampleTrackMaterial.STANDARD.getEntry().trackType,
                            ExampleTrackMaterial.TIELESS.getEntry().trackType
                    )
            )
    ).withBlockProperties(properties -> properties.strength(2.0f).noOcclusion());

    public static final BogeyBlockReg<ExampleBogeyBlock> CW2_BOGEY = new BogeyBlockReg<>(
            "cw2_bogey",
            CW2,
            (properties, size) -> new ExampleBogeyBlock(
                    properties,
                    size,
                    AllExampleBogey::cw2Style,
                    AllExampleBogey::cw2BlockEntityType,
                    () -> Set.of(
                            ExampleTrackMaterial.STANDARD.getEntry().trackType,
                            ExampleTrackMaterial.TIELESS.getEntry().trackType
                    )
            )
    ).withBlockProperties(properties -> properties.strength(2.0f).noOcclusion());

    public static final BogeyGroupReg STANDARD_GROUP = new BogeyGroupReg("standard", "test_bogey")
            .displayName(Component.literal("Standard Bogey"))
            .bogey(PK209P, STANDARD_BOGEY, PK209P_RENDERER)
            .onCommitted(style -> BogeyModels.register(
                    style.id, PK209P.getEntry(), PK209P_MODEL, 0f
            ));

    public static final BogeyGroupReg CW2_GROUP = new BogeyGroupReg("test", "test_bogey")
            .displayName(Component.literal("Test Bogey"))
            .bogey(CW2, CW2_BOGEY, CW2_RENDERER)
            .onCommitted(style -> BogeyModels.register(
                    style.id, CW2.getEntry(), CW2_MODEL, -1f
            ));

    public static final BlockEntityReg<ExampleBogeyBlockEntity> STANDARD_BOGEY_ENTITY =
            new BlockEntityReg<ExampleBogeyBlockEntity>("standard_bogey_entity", reg -> (pos, state) ->
                    new ExampleBogeyBlockEntity(reg.getEntry(), pos, state, STANDARD_GROUP::getEntry))
                    .validBlocks(STANDARD_BOGEY::getEntry);

    public static final BlockEntityReg<ExampleBogeyBlockEntity> CW2_BOGEY_ENTITY =
            new BlockEntityReg<ExampleBogeyBlockEntity>("test_bogey_entity", reg -> (pos, state) ->
                    new ExampleBogeyBlockEntity(reg.getEntry(), pos, state, CW2_GROUP::getEntry))
                    .validBlocks(CW2_BOGEY::getEntry);

    private static boolean registered;

    private AllExampleBogey() {
    }

    public static synchronized void register(Registry registry) {
        if (registered) {
            return;
        }
        registered = true;
        PK209P.setParent(registry);
        CW2.setParent(registry);
        STANDARD_BOGEY.setParent(registry);
        CW2_BOGEY.setParent(registry);
        STANDARD_GROUP.setParent(registry);
        CW2_GROUP.setParent(registry);
        STANDARD_BOGEY_ENTITY.setParent(registry);
        CW2_BOGEY_ENTITY.setParent(registry);
    }

    private static com.simibubi.create.content.trains.bogey.BogeyStyle standardStyle() {
        return STANDARD_GROUP.getEntry();
    }

    private static com.simibubi.create.content.trains.bogey.BogeyStyle cw2Style() {
        return CW2_GROUP.getEntry();
    }

    private static net.minecraft.world.level.block.entity.BlockEntityType<ExampleBogeyBlockEntity> standardBlockEntityType() {
        return STANDARD_BOGEY_ENTITY.getEntry();
    }

    private static net.minecraft.world.level.block.entity.BlockEntityType<ExampleBogeyBlockEntity> cw2BlockEntityType() {
        return CW2_BOGEY_ENTITY.getEntry();
    }

    private static BogeyRendererHandle renderer(String name) {
        return BogeyRendererHandle.of(ResourceLocation.fromNamespaceAndPath("kasuga_lib", name + "_renderer"));
    }
}
