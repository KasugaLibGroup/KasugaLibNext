package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.mojang.logging.LogUtils;
import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import lib.kasuga.registration.data_driven.reload.Decoded;
import lib.kasuga.registration.data_driven.reload.ReloadHandler;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.registration.data_driven.reload.Reloaded;
import lib.kasuga.rendering.models.uml.dynamic.fsm.DefinitionStateMachineFactory;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The reload-domain handler of state machine definitions — the {@code state_machines} half of
 * {@link ReloadOrchestrator}, moved out of the orchestrator so the orchestrator no longer knows the type.
 *
 * <p>Responsibilities are the three type-specific pieces the orchestrator used to spell out:
 * <ul>
 *   <li><strong>discovery</strong> — {@link #globDirectories()} declares {@value StateMachineDefinitionLoader#PATH}
 *       so the orchestrator glob-discovers {@code data/<ns>/state_machines/*.json} in addition to the
 *       index's {@code on_reload} arrays. This is the reload domain's only globbed directory;</li>
 *   <li><strong>decoding</strong> — {@link #decode(JsonObject)} delegates to
 *       {@link StateMachineDefinitionLoader#decodeFile}, which stays the pure file decoder (it is not
 *       moved by this refactor);</li>
 *   <li><strong>registration</strong> — {@link #register(StateMachineDefinition)} writes the RESOURCE
 *       half of {@link FsmDefinitions} ("script wins" is the bucket's own guard), and
 *       {@link #afterReload(List)} runs the dangling clip reference check.</li>
 * </ul>
 *
 * <p>{@link #afterReload(List)} is the reason the orchestrator runs the after-phase over <em>all</em>
 * handlers only once every register phase is done: a definition's {@code states[].clip} can only be
 * judged after the clip handler has loaded the clips of this cycle.
 */
public final class FsmReloadHandler implements ReloadHandler<StateMachineDefinition> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final FsmDefinitions definitions;
    private final FsmAnimationClips clips;

    /**
     * @param definitions the definition bucket this handler populates (reload-sourced half)
     * @param clips       the clip bucket the post-load reference check resolves against; not written here
     */
    public FsmReloadHandler(FsmDefinitions definitions, FsmAnimationClips clips) {
        this.definitions = definitions;
        this.clips = clips;
    }

    @Override
    public String typeName() {
        return StateMachineDefinitionLoader.FIELD_STATE_MACHINES;
    }

    @Override
    public String describeFile() {
        return "state machine file";
    }

    @Override
    public Set<String> globDirectories() {
        return Set.of(StateMachineDefinitionLoader.PATH);
    }

    @Override
    public void clearBucket() {
        // Only the reload-sourced half: script definitions survive a reload ("script wins").
        definitions.clearResource();
    }

    @Override
    public Decoded<StateMachineDefinition> decode(JsonObject root) {
        StateMachineDefinitionLoader.DecodedFile decoded = StateMachineDefinitionLoader.decodeFile(root);
        List<Decoded.Entry<StateMachineDefinition>> entries = new ArrayList<>(decoded.definitions().size());
        for (StateMachineDefinition definition : decoded.definitions()) {
            entries.add(new Decoded.Entry<>(definition.id().toString(), definition));
        }
        return new Decoded<>(entries, decoded.errors());
    }

    @Override
    public void register(StateMachineDefinition payload) {
        definitions.registerResource(payload.id(), payload);
    }

    @Override
    public void afterReload(List<Reloaded<StateMachineDefinition>> registered) {
        for (Reloaded<StateMachineDefinition> reloaded : registered) {
            StateMachineDefinition definition = reloaded.payload();
            // Only resource-loaded definitions are checked; a script definition shadowing a same-id
            // resource entry is not ours to judge, so skip when it is no longer the live bucket entry.
            if (definitions.get(definition.id()) != definition) {
                continue;
            }
            try {
                List<String> missing = DefinitionStateMachineFactory.unknownClipReferences(definition, clips);
                if (!missing.isEmpty()) {
                    reportWarning(reloaded.modId(), "State machine definition '" + definition.id()
                            + "' has unresolved clip references after reload: " + missing
                            + "; the affected states degrade to their static pose");
                }
            } catch (RuntimeException e) {
                reportError(reloaded.modId(), "Failed to check the clip references of state machine definition '"
                        + definition.id() + "' after reload", e);
            }
        }
    }

    /** Logs and records one reload-domain diagnostic under the namespace that owns the file. */
    private static void reportError(String modId, String message, RuntimeException cause) {
        LOGGER.error(message, cause);
        Diagnostics.report(Diagnostics.Domain.RELOAD_DATA, modId, new IOException(message, cause));
    }

    /**
     * Logs (WARN, matching the build-time aggregate warning of the same check) and records one
     * reload-domain diagnostic under the namespace that owns the file.
     */
    private static void reportWarning(String modId, String message) {
        LOGGER.warn(message);
        Diagnostics.report(Diagnostics.Domain.RELOAD_DATA, modId, new IllegalStateException(message));
    }
}
