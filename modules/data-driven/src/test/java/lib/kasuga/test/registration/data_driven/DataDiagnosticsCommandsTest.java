package lib.kasuga.test.registration.data_driven;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import lib.kasuga.registration.data_driven.diagnostics.DataDiagnosticsCommands;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code /kasuga_data errors [mod]}: the handler must really register the tree into a
 * dispatcher (not merely build one), and the two forms must print the summary and the per-mod detail.
 *
 * <p>Runs against a fresh {@link CommandDispatcher} and a recording {@link CommandSource}, so no
 * server is needed — the event itself carries the dispatcher.
 */
class DataDiagnosticsCommandsTest {

    private static final String MOD = "command_mod";

    @AfterEach
    void clearBuckets() {
        Diagnostics.clearAll();
    }

    @Test
    void onRegisterCommandsRegistersKasugaDataErrorsIntoTheDispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = newDispatcher();

        assertNotNull(dispatcher.getRoot().getChild("kasuga_data"),
                "the handler must register /kasuga_data on the dispatcher it is given");
        assertNotNull(dispatcher.getRoot().getChild("kasuga_data").getChild("errors"),
                "/kasuga_data must carry the errors subcommand");
        assertNotNull(dispatcher.getRoot().getChild("kasuga_data").getChild("errors").getChild("mod"),
                "the errors subcommand must take an optional mod id");
    }

    @Test
    void errorsWithoutModPrintsThePerBucketSummary() throws CommandSyntaxException {
        Diagnostics.report(MOD, new IllegalStateException("boom"));
        CommandDispatcher<CommandSourceStack> dispatcher = newDispatcher();
        RecordingSource source = new RecordingSource();

        int result = dispatcher.execute("kasuga_data errors", stack(source));

        assertEquals(List.of(MOD + ": 1 error(s)"), source.messages);
        assertEquals(1, result, "the command reports how many summary lines it printed");
    }

    @Test
    void errorsWithModPrintsThatModsFailures() throws CommandSyntaxException {
        Diagnostics.report(MOD, new IllegalStateException("boom-one"));
        Diagnostics.report(MOD, new IllegalStateException("boom-two"));
        Diagnostics.report("command_other_mod", new IllegalStateException("other"));
        CommandDispatcher<CommandSourceStack> dispatcher = newDispatcher();
        RecordingSource source = new RecordingSource();

        int result = dispatcher.execute("kasuga_data errors " + MOD, stack(source));

        assertEquals(List.of(
                "[" + MOD + "] java.lang.IllegalStateException: boom-one",
                "[" + MOD + "] java.lang.IllegalStateException: boom-two"
        ), source.messages, "only the requested mod's failures are listed");
        assertEquals(2, result);
    }

    /**
     * {@code errors <mod>} reads both dimensions: the registration failures stay exactly as they were,
     * and the reload failures of that namespace are now listed too (they used to be silently omitted).
     * A reload failure of another namespace is not shown.
     */
    @Test
    void errorsWithModAlsoPrintsThatModsReloadFailures() throws CommandSyntaxException {
        Diagnostics.report(MOD, new IllegalStateException("mod-boom"));
        Diagnostics.report(Diagnostics.Domain.RELOAD_DATA, MOD, new IllegalStateException("reload-boom"));
        Diagnostics.report(Diagnostics.Domain.RELOAD_DATA, "command_other_mod",
                new IllegalStateException("other-reload"));
        CommandDispatcher<CommandSourceStack> dispatcher = newDispatcher();
        RecordingSource source = new RecordingSource();

        int result = dispatcher.execute("kasuga_data errors " + MOD, stack(source));

        assertEquals(List.of(
                "[" + MOD + "] java.lang.IllegalStateException: mod-boom",
                "[RELOAD_DATA[" + MOD + "]] java.lang.IllegalStateException: reload-boom"
        ), source.messages,
                "the registration detail stays and the reload failure of this namespace is added");
        assertEquals(2, result);
    }

    /** A mod whose only failures are reload failures must not be reported as clean. */
    @Test
    void errorsWithModFindsReloadFailuresWithoutRegistrationFailures() throws CommandSyntaxException {
        Diagnostics.report(Diagnostics.Domain.RELOAD_DATA, MOD, new IllegalStateException("reload-only-boom"));
        CommandDispatcher<CommandSourceStack> dispatcher = newDispatcher();
        RecordingSource source = new RecordingSource();

        int result = dispatcher.execute("kasuga_data errors " + MOD, stack(source));

        assertEquals(List.of(
                "[RELOAD_DATA[" + MOD + "]] java.lang.IllegalStateException: reload-only-boom"
        ), source.messages, "the reload bucket must be found even with no registration failure");
        assertEquals(1, result);
    }

    @Test
    void errorsOnACleanLoadSaysSoInsteadOfPrintingNothing() throws CommandSyntaxException {
        CommandDispatcher<CommandSourceStack> dispatcher = newDispatcher();
        RecordingSource source = new RecordingSource();

        int result = dispatcher.execute("kasuga_data errors", stack(source));

        assertEquals(List.of("Kasuga data: no loading errors recorded"), source.messages);
        assertEquals(0, result);
    }

    @Test
    void theCommandIsOperatorGated() {
        CommandDispatcher<CommandSourceStack> dispatcher = newDispatcher();
        RecordingSource source = new RecordingSource();

        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("kasuga_data errors", stack(source, 0)),
                "a source below permission level 2 must not match the command");
        assertTrue(source.messages.isEmpty(), "…and must not receive diagnostics: " + source.messages);
    }

    private static CommandDispatcher<CommandSourceStack> newDispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        DataDiagnosticsCommands.onRegisterCommands(
                new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.ALL, null));
        return dispatcher;
    }

    /** A source everything prints to; {@code server}, {@code level} and {@code entity} stay null. */
    private static CommandSourceStack stack(RecordingSource source) {
        return stack(source, 2);
    }

    private static CommandSourceStack stack(RecordingSource source, int permissionLevel) {
        return new CommandSourceStack(source, Vec3.ZERO, Vec2.ZERO, null, permissionLevel,
                "diagnostics_test", Component.literal("diagnostics_test"), null, null);
    }

    /** Collects what the command prints instead of sending it anywhere. */
    private static final class RecordingSource implements CommandSource {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
