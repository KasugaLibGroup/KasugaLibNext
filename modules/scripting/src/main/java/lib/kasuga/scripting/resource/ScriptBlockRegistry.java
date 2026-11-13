package lib.kasuga.scripting.resource;

import lib.kasuga.core.resource.identity.ResourceUri;
import lib.kasuga.core.resource.lifecycle.InvocationContext;
import lib.kasuga.core.resource.lifecycle.LogicalSide;
import lib.kasuga.core.resource.script.*;
import lib.kasuga.core.resource.routing.StrictResourceJson;
import lib.kasuga.scripting.*;
import lib.kasuga.scripting.discovery.ScriptPackage;
import lib.kasuga.scripting.discovery.ScriptThread;
import lib.kasuga.scripting.security.Api;
import lib.kasuga.scripting.value.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** One scope/generation. The registry never evaluates a module or touches engine values on bind/submit. */
public final class ScriptBlockRegistry implements ScriptBlockResolver, AutoCloseable {
    private final String scopeId;
    private final long generation;
    private final LogicalSide side;
    private final Predicate<ScriptBlockRef> authorized;
    private final Map<ResourceUri, PackageSession> packages = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final lib.kasuga.scripting.module.PackageRegistry modulePackages = new lib.kasuga.scripting.module.PackageRegistry();
    private java.util.function.Function<ResourceUri, lib.kasuga.core.resource.view.ResourceAccess> resourceAccess = ignored -> null;
    public synchronized void resourceAccess(java.util.function.Function<ResourceUri, lib.kasuga.core.resource.view.ResourceAccess> factory) {
        if (!packages.isEmpty() || closed) throw new IllegalStateException("Install access policy before preparing packages");
        resourceAccess = Objects.requireNonNull(factory);
    }
    public lib.kasuga.scripting.module.PackageRegistry modulePackages() { return modulePackages; }
    public CompletionStage<Void> preparation() {
        return CompletableFuture.allOf(packages.values().stream().map(session -> session.ready).toArray(CompletableFuture[]::new));
    }
    public ScriptBlockRegistry(String scopeId, long generation, LogicalSide side, Predicate<ScriptBlockRef> authorized) {
        if (side == LogicalSide.BOTH) throw new IllegalArgumentException("Concrete script side required");
        this.scopeId = Objects.requireNonNull(scopeId); this.generation = generation; this.side = side; this.authorized = Objects.requireNonNull(authorized);
    }
    public synchronized PackageSession attach(ScriptPackage owner, ScriptThread thread, ScriptEngine engine,
                                               ResourceUri packageUri, boolean explicitlyTrusted, int capacity) {
        if (closed) throw new IllegalStateException("STALE_REFERENCE: script registry closed");
        if (!explicitlyTrusted && !engine.safetyCapabilities().supportsUntrusted())
            throw new IllegalArgumentException("ENGINE_NOT_SANDBOX_READY: untrusted script blocks are disabled");
        if (!thread.owns(engine) || !packageUri.childKey().isEmpty()) throw new IllegalArgumentException("Script engine/package owner mismatch");
        if (packages.containsKey(packageUri)) throw new IllegalArgumentException("Duplicate script package identity");
        PackageSession session = new PackageSession(owner, thread, engine, packageUri, capacity);
        packages.put(packageUri, session); return session;
    }
    @Override public CompletionStage<ScriptBlockHandle> bind(ScriptBlockRef ref, InvocationContext context) {
        if (closed || !context.scopeId().equals(scopeId) || context.generation() != generation)
            return CompletableFuture.failedFuture(new IllegalStateException("STALE_REFERENCE at " + ref.jsonPointer()));
        if (context.side() != side || ref.side() != side || !authorized.test(ref))
            return CompletableFuture.failedFuture(new IllegalArgumentException("PERMISSION_DENIED at " + ref.jsonPointer()));
        PackageSession session = packages.get(ref.packageUri());
        if (session == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Script package unavailable at " + ref.jsonPointer()));
        return session.ready.thenApply(ignored -> {
            if (closed || session.stopped.get()) throw new IllegalStateException("STALE_REFERENCE at " + ref.jsonPointer());
            Registered block = session.blocks.get(ref.block());
            if (block == null) throw new IllegalArgumentException("SCRIPT_BLOCK_MISSING at " + ref.jsonPointer());
            if (!block.contract().equals(ref.contract())) throw new IllegalArgumentException("Script block contract mismatch at " + ref.jsonPointer());
            return (ScriptBlockHandle) args -> {
                if (closed || session.stopped.get()) {
                    var ticket = ScriptTicket.submission(scopeId, generation).ticket(); ticket.cancel(); return ticket;
                }
                return session.ring.submit(block.contract(), args, data -> session.invoke(block, data));
            };
        });
    }
    @Override public void close() {
        List<PackageSession> sessions;
        synchronized (this) { if (closed) return; closed = true; sessions = List.copyOf(packages.values()); packages.clear(); }
        sessions.forEach(PackageSession::close);
    }
    private record Registered(ScriptContract contract, ScriptFunction function) {}

    /** Script-visible surface is only register(). Ownership fields never escape as host objects. */
    public final class PackageSession implements AutoCloseable {
        private final ScriptPackage owner;
        private final ScriptThread thread;
        private final ScriptEngine engine;
        private final ResourceUri identity;
        private final ScriptSubmissionRing ring;
        private final Map<String, Registered> blocks = new ConcurrentHashMap<>();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final AtomicBoolean stopped = new AtomicBoolean(), cleaned = new AtomicBoolean();
        private final lib.kasuga.core.resource.view.ResourceAccess access;
        private PackageSession(ScriptPackage owner, ScriptThread thread, ScriptEngine engine, ResourceUri identity, int capacity) {
            this.owner = Objects.requireNonNull(owner); this.thread = thread; this.engine = engine; this.identity = identity;
            this.access = resourceAccess.apply(identity);
            this.ring = thread.createSubmissionRing(scopeId, generation, capacity);
            thread.onTermination(() -> { close(); cleanup(); });
        }
        public void install() {
            requireOwner(); engine.registerGlobal("ScriptBlocks", new RegistrationApi(this));
            if (access != null) engine.registerGlobal("Resources", new ScriptVfsFacade(access));
        }
        public void markReady() { requireOwner(); if (closed || stopped.get()) ready.cancel(false); else ready.complete(null); }
        public void failPreparation(Throwable failure) { requireOwner(); ready.completeExceptionally(failure); close(); }
        public CompletionStage<Void> preparation() { return ready.minimalCompletionStage(); }
        public void register(String name, ScriptContract contract, ScriptFunction function) throws ScriptException {
            requireOwner();
            if (closed || stopped.get() || ready.isDone()) throw new IllegalStateException("Script block registration is closed");
            new ScriptBlockRef(identity, name, contract, side, ""); // Reuse the static identifier contract.
            ScriptFunction pinned = function.cloneValue();
            try {
                ScriptReference.pinFor(pinned);
                if (blocks.putIfAbsent(name, new Registered(contract, pinned)) != null) throw new IllegalArgumentException("Duplicate script block: " + name);
            } catch (RuntimeException | ScriptException e) { ScriptReference.removePinFor(pinned); pinned.close(); throw e; }
        }
        private Object invoke(Registered block, List<Object> data) throws ScriptException {
            requireOwner();
            if (closed || stopped.get()) throw new CancellationException("Script generation retired");
            ScriptValue[] args = new ScriptValue[data.size()]; ScriptValue result = null;
            try {
                for (int i = 0; i < args.length; i++) args[i] = engine.createValue(data.get(i));
                if (block.contract().result() == ScriptContract.Type.VOID) { block.function().executeVoid(args); return null; }
                result = block.function().execute(args);
                return fromEngine(result, 0, new int[]{16_384});
            } finally {
                if (result != null) result.close();
                for (ScriptValue arg : args) if (arg != null) arg.close();
            }
        }
        private void requireOwner() { if (Thread.currentThread() != thread) throw new IllegalStateException("Script engine access requires its ScriptThread"); }
        @Override public void close() {
            if (!stopped.compareAndSet(false, true)) return;
            if (access != null) access.close();
            ring.close(); ready.cancel(false);
            if (Thread.currentThread() == thread) cleanup();
            else try { thread.recordCall(this::cleanup); } catch (RejectedExecutionException ignored) { /* onTermination owns final release */ }
        }
        private void cleanup() {
            requireOwner(); if (!cleaned.compareAndSet(false, true)) return;
            RuntimeException failure = null;
            for (Registered block : blocks.values()) {
                try { ScriptReference.removePinFor(block.function()); block.function().close(); }
                catch (ScriptException e) { if (failure == null) failure = new IllegalStateException("Failed to release script block", e); else failure.addSuppressed(e); }
            }
            blocks.clear(); if (failure != null) throw failure;
        }
    }

    public static final class RegistrationApi {
        private final PackageSession session;
        private RegistrationApi(PackageSession session) { this.session = session; }
        @Api public void register(String name, String contractJson, ScriptFunction callback) throws ScriptException {
            session.register(name, ScriptReferenceCodec.contract(StrictResourceJson.parse(contractJson)), callback);
        }
    }
    private static Object fromEngine(ScriptValue value, int depth, int[] remaining) throws ScriptException {
        if (depth > 32 || --remaining[0] < 0) throw new IllegalArgumentException("LIMIT_EXCEEDED: script result");
        if (value == null) return null;
        if (value instanceof ScriptPrimitive primitive) return primitive.getValue();
        if (value instanceof ScriptArray array) {
            ScriptValue[] values = array.asArray(); List<Object> result = new ArrayList<>();
            try { for (ScriptValue item : values) result.add(fromEngine(item, depth + 1, remaining)); }
            finally { for (ScriptValue item : values) if (item != null) item.close(); }
            return result;
        }
        if (value instanceof ScriptObject object && !(value instanceof ScriptFunction)) {
            ScriptValue[] keys = object.getObjectKeys(); Map<String, Object> result = new LinkedHashMap<>();
            try {
                for (ScriptValue key : keys) {
                    if (!(key instanceof ScriptPrimitive p) || !(p.getValue() instanceof String name)) throw new IllegalArgumentException("Non-string result key");
                    ScriptValue child = object.getMember(key);
                    try { result.put(name, fromEngine(child, depth + 1, remaining)); } finally { if (child != null) child.close(); }
                }
            } finally { for (ScriptValue key : keys) if (key != null) key.close(); }
            return result;
        }
        throw new IllegalArgumentException("Engine functions/host objects cannot escape script tickets");
    }
}
