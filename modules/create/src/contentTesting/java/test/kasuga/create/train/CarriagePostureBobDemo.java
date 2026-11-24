package test.kasuga.create.train;

import com.simibubi.create.content.trains.entity.Carriage;
import lib.kasuga.KasugaLib;
import lib.kasuga.create.content.train.event.ModifyCarriagePostureEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Reproduces the old built-in demo bob through {@link ModifyCarriagePostureEvent}: every carriage
 * oscillates ±0.5 blocks with a 100-tick period (0.01 phase per tick), the same curve the carriage
 * mixin used to compute itself. The listener now owns the phase, keyed weakly by carriage so it
 * disappears together with the train.
 *
 * <p>The event is only posted on the server while {@code enable_carriage_y_offset} is on, so this
 * demo needs no gating of its own.
 */
@EventBusSubscriber(modid = KasugaLib.MODID)
public final class CarriagePostureBobDemo {

    private static final float AMPLITUDE = .5f;
    private static final float PHASE_STEP = 0.01f;

    private static final Map<Carriage, Float> PHASES = new WeakHashMap<>();

    private CarriagePostureBobDemo() {
    }

    @SubscribeEvent
    public static void onModifyPosture(ModifyCarriagePostureEvent event) {
        if (event.getLevel().isClientSide())
            return;

        Carriage carriage = event.getCarriage();
        float phase = PHASES.getOrDefault(carriage, 0f);
        event.setYOffset(AMPLITUDE * (float) Math.sin(phase * 2 * Math.PI));

        phase += PHASE_STEP;
        if (phase > 1f)
            phase = 0f;
        PHASES.put(carriage, phase);
    }
}
