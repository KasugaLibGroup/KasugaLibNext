package lib.kasuga.create.content.train.event;

import com.simibubi.create.content.trains.entity.Carriage;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;

/**
 * Fired on the server once per carriage per tick to compute the carriage body's vertical posture.
 *
 * <p>The offset starts at 0; listeners set it (for example from suspension or load state). After
 * the event returns, the value is clamped to {@code maxCarriageYOffset} and synced to clients,
 * which ease their rendered offset towards it. The client does not fire this event itself.
 */
public class ModifyCarriagePostureEvent extends Event {

    private final Level level;
    private final Carriage carriage;
    private float yOffset;

    public ModifyCarriagePostureEvent(Level level, Carriage carriage) {
        this.level = level;
        this.carriage = carriage;
    }

    /** The level the carriage is being ticked in. */
    public Level getLevel() {
        return level;
    }

    /** The carriage being posed. */
    public Carriage getCarriage() {
        return carriage;
    }

    /** Vertical offset of the carriage body for this tick, in blocks. Mutable by listeners. */
    public float getYOffset() {
        return yOffset;
    }

    public void setYOffset(float yOffset) {
        this.yOffset = yOffset;
    }
}
