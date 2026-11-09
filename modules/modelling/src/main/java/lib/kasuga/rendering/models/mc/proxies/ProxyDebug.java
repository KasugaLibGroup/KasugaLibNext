package lib.kasuga.rendering.models.mc.proxies;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Tracing for the proxy layer.
 *
 * <p>Lines are emitted at DEBUG, which means they land in {@code logs/debug.log} on every run
 * without any configuration — Minecraft writes that file at DEBUG level while {@code latest.log}
 * keeps INFO. Grep for {@code [proxy]} there.
 *
 * <p>Set {@code -Dkasuga.proxy.debug=true} to additionally promote the same lines to INFO (visible
 * in {@code latest.log} and in the console). Use it when you want to watch the trace live while the
 * game is running; leave it off otherwise so a world with many hosts does not flood the console.
 *
 * <p>For a zero-setup snapshot of what currently exists, run {@code /kasuga_proxy list} in game
 * (see {@link ProxyCommands}).
 */
public final class ProxyDebug {

    /** System property that promotes tracing from DEBUG to INFO. */
    public static final String PROPERTY = "kasuga.proxy.debug";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean VERBOSE = Boolean.getBoolean(PROPERTY);

    /** Whether tracing is promoted to INFO for this JVM. */
    public static boolean verbose() {
        return VERBOSE;
    }

    /**
     * Writes one trace line.
     *
     * @param format {@link org.slf4j.Logger} style format string; {@code {}} placeholders
     * @param args   arguments for the placeholders
     */
    public static void log(String format, Object... args) {
        if (VERBOSE) {
            LOGGER.info("[proxy] " + format, args);
        } else {
            LOGGER.debug("[proxy] " + format, args);
        }
    }

    private ProxyDebug() {
    }
}
