package lib.kasuga.scripting;

import jakarta.annotation.Nullable;
import lib.kasuga.scripting.feature.EngineFeature;
import lib.kasuga.scripting.feature.EngineFeatureType;
import lib.kasuga.scripting.module.ResolvedPackage;
import lib.kasuga.scripting.module.ResolvedScript;
import lib.kasuga.scripting.module.ScriptModuleHandle;
import lib.kasuga.scripting.value.ScriptValue;

import java.io.InputStream;
import java.util.Map;

public interface ScriptEngine {
    void init(ScriptConsole console) throws ScriptException;

    void setFeatures(Map<EngineFeatureType<?>, EngineFeature> features);

    @Nullable
    <F extends EngineFeature> F getFeature(EngineFeatureType<F> type);

    ScriptValue createValue(Object object) throws ScriptException;

    ScriptEngineType<?> getType();

    ScriptModuleHandle loadModule(ResolvedScript script) throws ScriptException;

    @Nullable
    ScriptModuleHandle getLoadedModule(String sourcePath);

    void executeEntry(String entryName, InputStream source) throws ScriptException;

    /**
     * Execute an entry script with its owning {@link ResolvedPackage} known, so a package-aware require
     * resolver can resolve in-package relative requires ({@code ./utils}) from the entry's first frame.
     * The default delegates to {@link #executeEntry(String, InputStream)} for engines that don't track
     * ownership.
     */
    default void executeEntry(String entryName, InputStream source, @Nullable ResolvedPackage owner) throws ScriptException {
        executeEntry(entryName, source);
    }

    void tick();

    void close();

    default void registerGlobal(String name, Object api) {}
}
