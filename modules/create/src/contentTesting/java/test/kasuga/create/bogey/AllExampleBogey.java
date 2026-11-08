package test.kasuga.create.bogey;

import lib.kasuga.create.proxy.BogeyModels;
import lib.kasuga.create.proxy.BogeyRoots;
import lib.kasuga.create.proxy.BogeyProxy;
import lib.kasuga.create.proxy.ContraptionBogey;
import lib.kasuga.create.proxy.ContraptionBogeyProxy;
import lib.kasuga.create.registration.bogey.BogeyBlockReg;
import lib.kasuga.create.registration.bogey.BogeyGroupReg;
import lib.kasuga.create.registration.bogey.BogeyRendererHandle;
import lib.kasuga.create.registration.bogey.BogeySizeReg;
import lib.kasuga.registration.Registry;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import lib.kasuga.rendering.models.mc.proxies.ElementProxy;
import lib.kasuga.rendering.models.mc.proxies.ModelPart;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import test.kasuga.create.track.ExampleTrackMaterial;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AllExampleBogey {
    /**
     * Camouflage models drawn by the pipeline; OBJ routes to the obj pipeline by extension.
     * Offsets come from the exported bounds: pk209p_main is centred on its origin (y -0.37..0.41),
     * bogey_cw2_temple starts at y 0.5 (origin at its base), so each needs a different drop.
     */
    private static final ResourceLocation PK209P_MAIN = obj("pk209p/pk209p_main.obj");
    private static final ResourceLocation PK209P_WHEEL = obj("pk209p/pk209p_wheel.obj");
    private static final ResourceLocation PK209P_WHEEL2 = obj("pk209p/pk209p_wheel2.obj");
    private static final ResourceLocation PK209P_MOTOR_WHEEL = obj("pk209p/pk209_motorwheel.obj");
    private static final ResourceLocation CW2_MODEL = obj("cw2/bogey_cw2_temple.obj");

    /**
     * pk209p is authored as four separate OBJs whose origins each sit at their own axle/socket, so
     * the model group is four parts. Locals are the reference renderer's translates
     * ({@code PK209PVisual}: frame 0.91, wheels 0.8 at z ±1.2, motor wheel at (1.117, 0.82, 2.165))
     * rebased on the frame, whose offset the root already carries.
     *
     * <p>Spins are attached per element in {@link #bogeyProxy()}, not here: the angle lives on the
     * block entity, and this registry entry is shared by every placed bogey.
     */
    /**
     * Vertical offset of the model group relative to the block centre, derived from the reference
     * renderer rather than measured: {@code PK209PVisual} draws the frame at {@code +0.91} above
     * Create's model origin, and our placement frame sits {@code 1.5 + 1/128} above that origin, so
     * the frame wants {@code 0.91 + CREATE_Y_OFFSET}. The wheel locals below stay as they are —
     * they are relative to the frame (0.8 − 0.91), which this anchor does not affect.
     */
    private static final float PK209P_Y_OFFSET = 0.91f + BogeyRoots.CREATE_Y_OFFSET;

    /**
     * Arc length between the two wheel points of each bogey, taken from the model itself: the wheel
     * parts sit at {@code z = ±1.2} (pk209p) and {@code ±1.18} (cw2), so the spacing is twice that.
     * Create turns {@code spacing - distance(points)} into stress, and stress over 4 derails.
     */
    private static final double PK209P_WHEEL_SPACING = 2.4d;
    private static final double CW2_WHEEL_SPACING = 2.36d;

    private static final List<ModelPart> PK209P_PARTS = List.of(
            new ModelPart(PK209P_MAIN, null, new Transform(), null),
            new ModelPart(PK209P_WHEEL, null, new Transform().translate(0f, -0.11f, 1.2f), null),
            new ModelPart(PK209P_WHEEL2, null, new Transform().translate(0f, -0.11f, -1.2f), null),
            new ModelPart(PK209P_MOTOR_WHEEL, null, new Transform().translate(1.117f, -0.09f, 2.165f), null)
    );

    /**
     * Gear ratio per wheel model: Create's {@code getVirtualAngle} is the wheelset angle, so the
     * wheelsets follow it 1:1 and the motor wheel (a small cog driving them) spins 3.256× as fast —
     * same ratio the reference renderer uses.
     */
    private static final Map<ResourceLocation, Float> SPIN_SCALE = Map.of(
            PK209P_WHEEL, 1f,
            PK209P_WHEEL2, 1f,
            PK209P_MOTOR_WHEEL, 3.256f
    );

    /**
     * Proxy shared by both example bogeys: the registered model group plus, for every part whose model
     * is a wheel, a {@link ModelPart.Spin} fed by the block entity's own virtual angle.
     *
     * <p>The block entity exposes one angle per game tick, so placed bogeys use tick-domain motion.
     * A style whose parts are not in {@link #SPIN_SCALE} (cw2's frame) simply never gets a spin.
     */
    public static ElementProxy<ExampleBogeyBlockEntity> bogeyProxy() {
        return new BogeyProxy<>(null, null) {
            @Override
            protected List<ModelPart> partsFor(ExampleBogeyBlockEntity element, BogeyModels.Placement placement) {
                return spinningWheelsForTick(super.partsFor(element, placement), element::wheelAngle);
            }
        };
    }

    /**
     * The same proxy for a bogey mounted on a train: identical model group and spin policy, with the
     * angle coming from Create's contraption hook instead of a block entity.
     */
    public static ElementProxy<ContraptionBogey> contraptionBogeyProxy() {
        return new ContraptionBogeyProxy() {
            @Override
            protected List<ModelPart> partsFor(ContraptionBogey element, BogeyModels.Placement placement) {
                return spinningWheelsForFrame(super.partsFor(element, placement), element::wheelAngle);
            }
        };
    }

    /** Attaches a tick-domain spin to every part whose model appears in {@link #SPIN_SCALE}. */
    public static List<ModelPart> spinningWheelsForTick(
            List<ModelPart> parts,
            ModelPart.Spin.AngleSource angle
    ) {
        return parts.stream()
                .map(part -> {
                    Float scale = SPIN_SCALE.get(part.model());
                    return scale == null ? part : part.spinning(ModelPart.Spin.tick(angle, scale));
                })
                .toList();
    }

    /** Attaches a frame-domain spin to every part whose model appears in {@link #SPIN_SCALE}. */
    public static List<ModelPart> spinningWheelsForFrame(
            List<ModelPart> parts,
            ModelPart.Spin.AngleSource angle
    ) {
        return parts.stream()
                .map(part -> {
                    Float scale = SPIN_SCALE.get(part.model());
                    return scale == null ? part : part.spinning(ModelPart.Spin.frame(angle, scale));
                })
                .toList();
    }

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
                    ExampleTrackMaterial.STANDARD.getEntry().trackType,
                    PK209P_WHEEL_SPACING
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
                    ExampleTrackMaterial.STANDARD.getEntry().trackType,
                    CW2_WHEEL_SPACING
            )
    ).withBlockProperties(properties -> properties.strength(2.0f).noOcclusion());

    public static final BogeyGroupReg STANDARD_GROUP = new BogeyGroupReg("standard", "test_bogey")
            .displayName(Component.literal("Standard Bogey"))
            .bogey(PK209P, STANDARD_BOGEY, PK209P_RENDERER)
            .onCommitted(style -> BogeyModels.register(
                    style.id, PK209P.getEntry(), PK209P_PARTS, PK209P_Y_OFFSET
            ));

    public static final BogeyGroupReg CW2_GROUP = new BogeyGroupReg("test", "test_bogey")
            .displayName(Component.literal("Test Bogey"))
            .bogey(CW2, CW2_BOGEY, CW2_RENDERER)
            .onCommitted(style -> BogeyModels.register(
                    style.id, CW2.getEntry(), CW2_MODEL, BogeyRoots.CREATE_Y_OFFSET
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

    private static ResourceLocation obj(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/block/bogey/" + path);
    }
}
