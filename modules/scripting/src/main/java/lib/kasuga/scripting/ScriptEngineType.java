package lib.kasuga.scripting;

import lib.kasuga.scripting.feature.EngineFeature;
import lib.kasuga.scripting.feature.EngineFeatureType;
import lib.kasuga.scripting.module.ModuleResolver;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

public class ScriptEngineType<T extends ScriptEngine> {
    public final String scriptType;
    public final Supplier<T> engineSupplier;
    public final boolean multiThreadSupporting;
    public final int priority;
    public final List<Throwable> loadingIssues;
    public final Set<EngineFeatureType<?>> featureTypes;
    public final ModuleResolver resolver;
    public final List<GlobalApiEntry> globalApis;

    /**
     * The supplier receives the fully-initialized {@link ScriptEngine} so an API object can capture a
     * back-reference (e.g. to wrap Java objects into {@link lib.kasuga.scripting.value.ScriptValue}s when
     * invoking JS callbacks). APIs that don't need the back-reference can use the {@code Supplier} overload
     * of {@link Builder#addGlobalApi(String, Supplier)}.
     */
    public record GlobalApiEntry(String name, Function<ScriptEngine, Object> supplier) {}

    public ScriptEngineType(
            String scriptType,
            Supplier<T> engineSupplier,
            ModuleResolver resolver,
            boolean multiThreadSupporting,
            int priority,
            Set<EngineFeatureType<?>> featureTypes,
            List<GlobalApiEntry> globalApis
    ) {
        this.scriptType = scriptType;
        this.engineSupplier = engineSupplier;
        this.resolver = resolver;
        this.multiThreadSupporting = multiThreadSupporting;
        this.priority = priority;
        this.featureTypes = Set.copyOf(featureTypes);
        this.loadingIssues = new ArrayList<>();
        this.globalApis = List.copyOf(globalApis);
    }

    public void addLoadingIssue(Throwable issue) {
        this.loadingIssues.add(issue);
    }

    public static <T extends ScriptEngine> Builder<T> builder(Supplier<T> engineSupplier) {
        return new Builder<T>().engineSupplier(engineSupplier);
    }

    public boolean isAvailable() {
        return loadingIssues.isEmpty();
    }

    public T create(ScriptConsole console) throws ScriptException {
        T engine = engineSupplier.get();
        Map<EngineFeatureType<?>, EngineFeature> features = new HashMap<>();
        for (EngineFeatureType<?> featureType : featureTypes) {
            features.put(featureType, featureType.build());
        }
        engine.setFeatures(features);
        engine.init(console);
        for (GlobalApiEntry entry : globalApis) {
            engine.registerGlobal(entry.name(), entry.supplier().apply(engine));
        }
        return engine;
    }

    public static class Builder<T extends ScriptEngine> {

        protected String scriptType;
        protected Supplier<T> engineSupplier;
        protected ModuleResolver resolver;
        protected boolean multiThreadSupporting = false;
        protected int priority = 0;
        protected final Set<EngineFeatureType<?>> featureTypes = new HashSet<>();
        protected final List<GlobalApiEntry> globalApis = new ArrayList<>();

        public Builder<T> scriptType(String scriptType) {
            this.scriptType = scriptType;
            return this;
        }

        public Builder<T> resolver(ModuleResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        public Builder<T> engineSupplier(Supplier<T> engineSupplier) {
            this.engineSupplier = engineSupplier;
            return this;
        }

        public Builder<T> multiThreadSupporting(boolean multiThreadSupporting) {
            this.multiThreadSupporting = multiThreadSupporting;
            return this;
        }

        public Builder<T> priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder<T> addFeature(EngineFeatureType<?> featureType) {
            this.featureTypes.add(featureType);
            return this;
        }

        public Builder<T> addGlobalApi(String name, Function<ScriptEngine, Object> supplier) {
            this.globalApis.add(new GlobalApiEntry(name, supplier));
            return this;
        }

        /** Convenience for APIs that don't need an engine back-reference (wrapped to ignore the engine). */
        public Builder<T> addGlobalApi(String name, Supplier<Object> supplier) {
            this.globalApis.add(new GlobalApiEntry(name, engine -> supplier.get()));
            return this;
        }

        public ScriptEngineType<T> build() {
            return new ScriptEngineType<>(scriptType, engineSupplier, resolver, multiThreadSupporting, priority, featureTypes, globalApis);
        }
    }
}
