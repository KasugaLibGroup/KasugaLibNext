package lib.kasuga.scripting.fsm;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmMachines;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.scripting.ScriptEngine;
import lib.kasuga.scripting.ScriptException;
import lib.kasuga.scripting.module.ScriptModule;
import lib.kasuga.scripting.module.ScriptModuleFactory;
import lib.kasuga.scripting.value.ScriptFunction;
import lib.kasuga.scripting.value.ScriptReference;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-engine driver for {@code Animator.autoTick}/{@code onTick}/{@code onStateChanged}: advances the
 * registered scripting machines once per server tick and fires their optional JS callbacks. Mirrors
 * {@code TimerModule} — it is a {@link ScriptModule} whose {@link #tick()} runs on the owning script thread
 * (driven by the engine's tick), so it honors the FSM's single-ticker invariant. Removes the "every author
 * hand-rolls {@code timer.setInterval(() => Animator.tick(h), 50)}" friction called out in the round-3
 * review.
 *
 * <p>One module per engine; {@link AnimatorApi} looks its engine's instance up via {@link #forEngine}.
 * Entries are keyed by int handle. A handle may carry an optional no-arg {@code onTick} callback (runs
 * after each advance) and/or an {@code onStateChanged} callback (runs only when the machine's version
 * bumps — i.e. an active state or cross-fade changed). Callbacks are cloned + pinned so they survive
 * across ticks; unpinned + closed on removal or engine close.
 */
public final class FsmAutoTickModule implements ScriptModule {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Per-engine registry so {@link AnimatorApi} can reach its own engine's hub. */
    private static final Map<ScriptEngine, FsmAutoTickModule> BY_ENGINE = new ConcurrentHashMap<>();

    public static final ScriptModuleFactory<FsmAutoTickModule> FACTORY = new ScriptModuleFactory<>() {
        @Override
        public String name() {
            return "kasuga:fsm_autotick";
        }

        @Override
        public FsmAutoTickModule create(ScriptEngine engine) {
            return new FsmAutoTickModule(engine);
        }
    };

    private final ScriptEngine engine;
    private final FsmMachines machines;
    private final Map<Integer, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    private FsmAutoTickModule(ScriptEngine engine) {
        this.engine = engine;
        this.machines = FsmRegistries.GLOBAL.machines();
        BY_ENGINE.put(engine, this);
    }

    /** The hub bound to {@code engine} (or {@code null} if the engine has none — e.g. a mock/unit-test engine). */
    public static FsmAutoTickModule forEngine(ScriptEngine engine) {
        return BY_ENGINE.get(engine);
    }

    /** Register ({@code on=true}) or unregister a handle for per-tick advancement with no callbacks. */
    public void setAutoTick(int handle, boolean on) {
        if (closed) {
            return;
        }
        if (on) {
            entries.computeIfAbsent(handle, Entry::new);
        } else {
            remove(handle);
        }
    }

    /** Attach a no-arg {@code onTick} callback (runs after each advance). Enables auto-tick for the handle. */
    public void setOnTick(int handle, ScriptFunction callback) {
        if (closed) {
            return;
        }
        Entry entry = entries.computeIfAbsent(handle, Entry::new);
        ScriptFunction previous = entry.onTick;
        entry.onTick = cloneAndPin(callback);
        unpinAndClose(previous);
    }

    /**
     * Attach a no-arg {@code onStateChanged} callback (runs after an advance that bumped the machine's
     * version — an active-state or cross-fade change). Enables auto-tick for the handle.
     */
    public void setOnStateChanged(int handle, ScriptFunction callback) {
        if (closed) {
            return;
        }
        Entry entry = entries.computeIfAbsent(handle, Entry::new);
        ScriptFunction previous = entry.onStateChanged;
        entry.onStateChanged = cloneAndPin(callback);
        unpinAndClose(previous);
    }

    /** Drop a handle's entry (releases its callbacks). Inert if not registered. */
    public void remove(int handle) {
        Entry removed = entries.remove(handle);
        if (removed != null) {
            unpinAndClose(removed.onTick);
            unpinAndClose(removed.onStateChanged);
        }
    }

    @Override
    public void init() {
        // no-op
    }

    @Override
    public void tick() {
        if (closed) {
            return;
        }
        for (Entry entry : entries.values()) {
            StateMachine<?> machine = machines.resolve(entry.handle);
            if (machine == null) {
                continue;
            }
            int versionBefore = machine.version();
            try {
                machine.tick();
            } catch (Exception e) {
                LOGGER.warn("FSM auto-tick for handle {} threw; one tick skipped", entry.handle, e);
            }
            if (entry.onTick != null) {
                invoke(entry.onTick, entry.handle, "onTick");
            }
            if (entry.onStateChanged != null && machine.version() != versionBefore) {
                invoke(entry.onStateChanged, entry.handle, "onStateChanged");
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        BY_ENGINE.remove(engine);
        for (Entry entry : entries.values()) {
            unpinAndClose(entry.onTick);
            unpinAndClose(entry.onStateChanged);
        }
        entries.clear();
    }

    private static void invoke(ScriptFunction callback, int handle, String label) {
        try {
            callback.executeVoid();
        } catch (Exception e) {
            LOGGER.warn("FSM {} callback for handle {} threw; swallowed", label, handle, e);
        }
    }

    private static ScriptFunction cloneAndPin(ScriptFunction callback) {
        try {
            ScriptFunction clone = (ScriptFunction) callback.cloneValue();
            ScriptReference.pinFor(clone);
            return clone;
        } catch (ScriptException e) {
            throw new IllegalArgumentException("Failed to pin FSM callback", e);
        }
    }

    private static void unpinAndClose(ScriptFunction callback) {
        if (callback == null) {
            return;
        }
        try {
            ScriptReference.removePinFor(callback);
        } catch (Exception ignored) {
            // ignore
        }
        try {
            callback.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    /** Per-handle state: optional no-arg onTick + onStateChanged JS callbacks. */
    private static final class Entry {
        final int handle;
        ScriptFunction onTick;
        ScriptFunction onStateChanged;

        Entry(int handle) {
            this.handle = handle;
        }
    }
}
