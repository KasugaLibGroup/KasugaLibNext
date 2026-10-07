package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.registration.data_driven.structure.ContentStructure;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes data-driven state machine files. It is no longer a reload participant:
 * {@link ReloadOrchestrator} is the single reload orchestrator and owns the clear / read / register
 * cycle, so this class deliberately holds no listener, no clear and no bucket. It keeps the two things
 * the reload domain needs — the canonical directory name ({@value #PATH}) that
 * {@link FsmReloadHandler#globDirectories()} declares for directory-glob discovery, and the file-level
 * wrapper decode.
 *
 * <p>File shape: every file is a wrapper object that carries the top-level key
 * {@value #FIELD_STATE_MACHINES}, mapping to an array of definitions —
 * {@code {"state_machines": [ <definition>, ... ]}}. Array elements are decoded with
 * {@link StateMachineDefinition#CODEC}, which is unchanged; the wrapper exists only at the file
 * layer, so inline/script registration, content hashing and programmatic registration are never
 * affected. One file may hold several definitions, and within a file a later entry with the same id
 * wins (last-wins) — the last-wins resolution itself happens in the orchestrator, together with the
 * cross-entry order, so it is applied uniformly to both reload entries.
 *
 * <p>Failure isolation: a shape violation (non-object body, missing top-level key, non-array value)
 * rejects the file; a malformed array element only drops that element while its siblings still load.
 * An extra top-level key is not this decoder's concern — the reload orchestrator reports it through
 * its unknown-key path. The orchestrator turns the returned diagnostics into paired log + bucket
 * entries.
 */
public final class StateMachineDefinitionLoader {

    /** Directory under {@code data/<ns>/} scanned for state machine files. */
    public static final String PATH = "state_machines";

    /** The single top-level key every state machine file is allowed to carry. */
    public static final String FIELD_STATE_MACHINES = "state_machines";

    /** The expected file shape, quoted verbatim in decode diagnostics. */
    private static final String EXPECTED_SHAPE = "{\"state_machines\": [ <definition>, ... ]}";

    /**
     * Outcome of decoding one state machine file.
     *
     * @param definitions the definitions that decoded, in file order; empty when the whole file was
     *                    rejected
     * @param errors      human-readable diagnostics for a rejected file or a skipped array element;
     *                    empty when the file decoded cleanly
     */
    public record DecodedFile(List<StateMachineDefinition> definitions, List<String> errors) {}

    /**
     * Decodes one state machine file into its definitions. The top-level shape is strict, mirroring the
     * content-file top-level field check in the data-driven dispatcher: the body must be an object
     * carrying {@value #FIELD_STATE_MACHINES}, holding an array. A violation rejects the file.
     * Elements are decoded one by one with {@link StateMachineDefinition#CODEC} so a single malformed
     * element is skipped without dropping its siblings.
     *
     * @param json the parsed file body
     * @return the successfully decoded definitions plus any diagnostics
     */
    public static DecodedFile decodeFile(JsonElement json) {
        List<String> errors = new ArrayList<>();
        if (json == null || !json.isJsonObject()) {
            errors.add("expected an object of shape " + EXPECTED_SHAPE + ", got "
                    + ContentStructure.describe(json));
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        JsonObject root = json.getAsJsonObject();
        if (!root.has(FIELD_STATE_MACHINES)) {
            errors.add("missing top-level key '" + FIELD_STATE_MACHINES + "' (expected shape "
                    + EXPECTED_SHAPE + ")");
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        ContentStructure.Field field = ContentStructure.field(root, FIELD_STATE_MACHINES);
        for (ContentStructure.Issue issue : field.issues()) {
            if (issue.kind() == ContentStructure.Kind.VALUE_NOT_ARRAY) {
                errors.add("top-level key '" + FIELD_STATE_MACHINES + "' must be an array, got "
                        + ContentStructure.describe(issue.actual()));
                return new DecodedFile(List.of(), List.copyOf(errors));
            }
            if (issue.kind() == ContentStructure.Kind.ELEMENT_NOT_OBJECT) {
                errors.add(FIELD_STATE_MACHINES + "[" + issue.index() + "] must be an object, got "
                        + ContentStructure.describe(issue.actual()) + "; element skipped");
            }
        }
        List<StateMachineDefinition> decoded = new ArrayList<>();
        for (ContentStructure.Element element : field.elements()) {
            StateMachineDefinition.CODEC.parse(JsonOps.INSTANCE, element.body())
                    .resultOrPartial(error -> errors.add(FIELD_STATE_MACHINES + "[" + element.fileIndex()
                            + "] failed to decode: " + error + "; element skipped"))
                    .ifPresent(decoded::add);
        }
        return new DecodedFile(List.copyOf(decoded), List.copyOf(errors));
    }

    private StateMachineDefinitionLoader() {}
}
