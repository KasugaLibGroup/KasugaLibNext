package lib.kasuga.rendering.models.mc.proxies;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.api.McModelHandle;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmAnimatedModel;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.function.Supplier;

public class ProxyInstance<T> implements AutoCloseable{

    private static final Logger LOGGER = LogUtils.getLogger();

    private enum Stage {
        NEW,
        LIVE,
        FAILED,
        RELEASED
    }

    private final Object owner;
    private final T element;
    private final ElementProxy<T> template;
    @Nullable private final ProxyIdentity fixedIdentity;
    @Nullable private final Supplier<Transform> fixedRoot;

    private Stage stage = Stage.NEW;
    @Nullable private ProxyIdentity identity;
    @Nullable private ProxyConfig config;
    @Nullable private FsmAnimatedModel fsm;
    @Nullable private McModelHandle handle;
    @Nullable private Level lastLevel;
    @Nullable private Boolean clientSide;

    private long lastTickGameTime = Long.MIN_VALUE;

    public ProxyInstance(Object owner, T element, ElementProxy<T> template, @Nullable ProxyIdentity fixedIdentity, @Nullable Supplier<Transform> fixedRoot) {
        this.owner = owner;
        this.element = element;
        this.template = template;
        this.fixedIdentity = fixedIdentity;
        this.fixedRoot = fixedRoot;
    }

    public @Nullable ProxyIdentity identity() {
        return this.identity;
    }

    public @Nullable StateMachine<?> machine() {
        return fsm == null || lastLevel == null ? null : fsm.machine(lastLevel);
    }

    public void tick(Level level, BlockPos pos) {
        if (stage == Stage.RELEASED || level == null) return;
        lastLevel = level;

        long now = level.getGameTime();
        if(now == lastTickGameTime) return;
        lastTickGameTime = now;

        if (stage == Stage.NEW) {
            ProxyDebug.log("materialize start: element={} pos={} side={}", elementName(), pos, sideName(level));
            stage = materialize(level, pos) ? Stage.LIVE : Stage.FAILED;
            ProxyDebug.log("materialize result: identity={} stage={} machine={} model={}",
                    identity, stage,
                    config == null ? null : config.machine(),
                    config == null ? null : config.model());
        }
        if (stage != Stage.LIVE) return;

        if (fsm != null) {
            StateMachine<?> m = fsm.machine(level);
            if (m == null) return;
            template.pull(element, m);
            fsm.tick(level, pos);
            template.push(element, m);
        } else if (handle != null && level.isClientSide()) {
            boolean mounted = handle.mount();
            if (!mounted && now % 100 == 0) {
                ProxyDebug.log("model still unmounted: model={} instance={} "
                                + "(resource not published, or the loader rejected it)",
                        config == null ? null : config.model(),
                        identity == null ? null : identity.key());
            }
        }

    }

    private boolean materialize(Level level, BlockPos pos) {
        try {
            this.clientSide = level.isClientSide();
            this.identity = fixedIdentity != null ? fixedIdentity : resolveIdentity(level, pos);
            this.config = template.configure(element);
            if (config == null) {
                LOGGER.warn("[proxy] {} refused to configure ({})", element.getClass().getName(), identity);
                return false;
            }
            ProxyDebug.log("configure: identity={} side={} machine={} model={} modelName={}",
                    identity, sideName(level), config.machine(), config.model(), config.modelName());
            if (config.machine() == null && config.model() == null) {
                LOGGER.warn("[proxy] {} produced an empty config (no machine, no model) at {}",
                        element.getClass().getName(), identity);
                return false;
            }
            Supplier<Transform> root = config.rootTransform() != null ? config.rootTransform() : fixedRoot != null ?
                    fixedRoot : () -> new Transform().translate(pos.getX() + 0.5f, pos.getY(), pos.getZ() + 0.5f);

            if (config.machine() != null) {
                this.fsm = new FsmAnimatedModel(owner, identity.discriminator(), identity.key(), root,
                        config.machine(), config.model(), config.modelName(), null, null);
                this.fsm.setVarProvider(template.varProvider(element));
            } else if (config.model() != null && level.isClientSide()) {
                this.handle = McModelHandle.of(config.model(), config.modelName(), identity.key(), null)
                        .setPose(root.get());
                boolean mounted = this.handle.mount();
                ProxyDebug.log("model bind: model={} instance={} mounted={}",
                        config.model(), identity.key(), mounted);
            }
            Proxies.remember(this);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[proxy] configure failed for {} ({})", element.getClass().getName(), identity, t);
            return false;
        }
    }

    private ProxyIdentity resolveIdentity(Level level, BlockPos pos) {
        if (element instanceof BlockEntity) return ProxyIdentity.ofBlock(level, pos);
        if (element instanceof Entity entity) return ProxyIdentity.ofEntity(entity);
        throw new IllegalStateException(element.getClass().getName()
                + " has no implicit identity; use Proxies.installExternal(...)");
    }

    public void release(@Nullable Level level) {
        if (stage == Stage.RELEASED) return;
        ProxyDebug.log("release: identity={} stage={}", identity, stage);
        this.stage = Stage.RELEASED;
        if (fsm != null) fsm.onRemoved(level);
        if (handle != null) handle.destroy();
        this.template.onDetach(element);
        Proxies.forget(this);
        this.fsm = null;
        this.handle = null;
    }

    @Override
    public void close() {
        release(this.lastLevel);
    }

    // ------------------------------------------------------------------
    // diagnostics (read-only; used by /kasuga_proxy and tests)
    // ------------------------------------------------------------------

    /** Whether the proxy finished materializing and is ticking. */
    public boolean isLive() {
        return stage == Stage.LIVE;
    }

    /** Stage name for diagnostics: {@code NEW}, {@code LIVE}, {@code FAILED} or {@code RELEASED}. */
    public String stageName() {
        return stage.name();
    }

    /** Model this proxy bound, or {@code null} before materialize / for machine-only proxies. */
    public @Nullable ResourceLocation modelLocation() {
        return config == null ? null : config.model();
    }

    /** Whether the client-side model handle currently holds a bound instance. */
    public boolean modelMounted() {
        return handle != null && handle.isMounted();
    }

    /** One-line description used by {@code /kasuga_proxy list}. */
    public String describe() {
        return (identity == null ? "<unresolved>" : identity.key().toString())
                + " side=" + (clientSide == null ? "?" : clientSide ? "client" : "server")
                + " element=" + elementName()
                + " stage=" + stage
                + " machine=" + (config == null ? null : config.machine())
                + " model=" + (config == null ? null : config.model())
                + " mounted=" + modelMounted()
                + " lastTick=" + (lastTickGameTime == Long.MIN_VALUE ? "never" : Long.toString(lastTickGameTime));
    }

    private String elementName() {
        return element.getClass().getSimpleName();
    }

    private static String sideName(Level level) {
        return level.isClientSide() ? "client" : "server";
    }
}
