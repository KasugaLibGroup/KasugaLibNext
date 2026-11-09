package lib.kasuga.rendering.models.mc.proxies;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * In-game diagnostics for the proxy layer: {@code /kasuga_proxy list}.
 *
 * <p>Prints a one-line summary and one line per live {@link ProxyInstance}. The fields answer the
 * questions that are otherwise invisible from the outside:
 *
 * <ul>
 *     <li>{@code stage} — {@code NEW} means the host never ticked the proxy, {@code FAILED} means
 *     {@code ElementProxy#configure} returned {@code null} (or threw), {@code LIVE} means it is
 *     running;</li>
 *     <li>{@code machine} / {@code model} — what the template actually resolved to, i.e. whether the
 *     model registry lookup worked;</li>
 *     <li>{@code mounted} — whether the client-side {@code McModelHandle} holds a bound instance; a
 *     {@code LIVE} proxy with {@code mounted=false} points at resource/loader problems, not at the
 *     proxy;</li>
 *     <li>{@code lastTick} — the game time of the last tick the proxy saw, which separates "not
 *     ticking" from "ticking but not rendering".</li>
 * </ul>
 *
 * <p>Both sides register the command; in singleplayer the integrated server and the client share a
 * JVM, so {@code side=client} and {@code side=server} entries appear in the same list. On a
 * dedicated server only server-side proxies exist (no models are bound there).
 *
 * <p>For per-tick tracing, launch with {@code -Dkasuga.proxy.debug=true}; see {@link ProxyDebug}.
 */
@EventBusSubscriber
public final class ProxyCommands {

    private static final int MAX_LINES = 64;

    private ProxyCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("kasuga_proxy")
                // Read-only and only meaningful to mod developers; permission 0 so it also works in a
                // singleplayer dev world without cheats enabled.
                .requires(source -> source.hasPermission(0))
                .then(Commands.literal("list").executes(ProxyCommands::list)));
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<ProxyInstance<?>> instances = Proxies.all();
        long live = instances.stream().filter(ProxyInstance::isLive).count();
        long mounted = instances.stream().filter(ProxyInstance::modelMounted).count();
        source.sendSuccess(() -> Component.literal(
                "[proxy] instances=" + instances.size() + " live=" + live + " mounted=" + mounted), false);

        List<ProxyInstance<?>> shown = instances.stream().limit(MAX_LINES).toList();
        for (ProxyInstance<?> instance : shown) {
            String line = instance.describe();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        if (instances.size() > shown.size()) {
            source.sendSuccess(() -> Component.literal(
                    "[proxy] " + (instances.size() - shown.size()) + " more instance(s) not shown"), false);
        }
        return instances.size();
    }
}
