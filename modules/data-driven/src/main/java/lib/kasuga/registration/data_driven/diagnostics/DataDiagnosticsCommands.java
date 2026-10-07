package lib.kasuga.registration.data_driven.diagnostics;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game face of the {@link Diagnostics} outlet: {@code /kasuga_data errors [mod]}.
 *
 * <p>Without an argument it prints the same one-line-per-bucket summary {@link Diagnostics#summarize()}
 * produces; with a mod id it lists that mod's individual failures across both dimensions — the
 * registration failures addressed by mod id and the reload failures addressed by namespace, each
 * labelled with the same {@code Diagnostics.bucketLabel} the summary line uses. The command only reads,
 * so it is safe to run at any time, and it is registered on the common side because the outlet itself
 * is common (the loader runs at mod construction, before any client exists).
 *
 * <p>Registration goes through {@link RegisterCommandsEvent}, a game-bus event fired when the
 * server's {@code ReloadableServerResources} is (re)built — the standard hook for commands. The
 * class is discovered by FML's automatic subscriber because it is part of the mod's own classes.
 */
@EventBusSubscriber
public final class DataDiagnosticsCommands {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DataDiagnosticsCommands() {}

    /**
     * Builds the {@code /kasuga_data} command tree. Kept free of any dispatcher or event so a plain
     * test can register it and assert the shape.
     *
     * <p>Requires permission level 2: the command dumps internal load diagnostics, which is operator
     * information on a dedicated server (the console, at level 4, is unaffected).
     */
    public static LiteralArgumentBuilder<CommandSourceStack> kasugaDataCommand() {
        return Commands.literal("kasuga_data")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("errors")
                        .executes(context -> printSummary(context.getSource()))
                        .then(Commands.argument("mod", StringArgumentType.word())
                                .executes(context -> printMod(context.getSource(),
                                        StringArgumentType.getString(context, "mod")))));
    }

    /** Registers the command tree on the server's dispatcher. */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(kasugaDataCommand());
        LOGGER.debug("Registered /kasuga_data diagnostics command");
    }

    /** No argument: one line per non-empty bucket across both dimensions. */
    private static int printSummary(CommandSourceStack source) {
        List<String> lines = Diagnostics.summarize();
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Kasuga data: no loading errors recorded"), false);
            return 0;
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

    /**
     * With a mod: that mod's individual failures across both dimensions, one line each. The
     * registration dimension keeps its {@code "[<mod>] <error>"} detail verbatim; the reload dimension
     * is added under the same bucket label the summary uses, so a reload failure of that namespace is
     * no longer silently omitted.
     */
    private static int printMod(CommandSourceStack source, String modId) {
        List<String> lines = new ArrayList<>();
        for (Throwable error : Diagnostics.errors(modId)) {
            lines.add("[" + modId + "] " + error);
        }
        String reloadLabel = Diagnostics.bucketLabel(Diagnostics.Domain.RELOAD_DATA, modId);
        for (Throwable error : Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, modId)) {
            lines.add("[" + reloadLabel + "] " + error);
        }
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Kasuga data: no loading errors for '" + modId + "'"), false);
            return 0;
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }
}
