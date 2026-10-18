package lib.kasuga.slp.javet;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;
import lib.kasuga.scripting.ScriptConsole;
import lib.kasuga.scripting.ScriptEngine;
import lib.kasuga.scripting.ScriptEngineType;
import lib.kasuga.scripting.ScriptException;
import lib.kasuga.scripting.feature.EngineFeature;
import lib.kasuga.scripting.feature.EngineFeatureType;
import lib.kasuga.scripting.module.BuiltinModuleRegistry;
import lib.kasuga.scripting.module.PackageRegistry;
import lib.kasuga.scripting.module.ResolvedPackage;
import lib.kasuga.scripting.module.ResolvedScript;
import lib.kasuga.scripting.module.ScriptModule;
import lib.kasuga.scripting.module.ScriptModuleHandle;
import lib.kasuga.scripting.security.SecurityEngineFeature;
import lib.kasuga.scripting.security.SecurityEngineFeatureType;
import lib.kasuga.scripting.value.ScriptValue;
import lib.kasuga.slp.javet.converter.FastJavetClassConverter;
import lib.kasuga.slp.javet.module.JavetModuleHandle;
import lib.kasuga.slp.javet.module.JsModuleResolver;
import lib.kasuga.slp.javet.module.PackageAwareRequireResolver;
import lib.kasuga.slp.javet.module.RequireResolver;
import lib.kasuga.slp.javet.value.JavetValueBridge;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;


public class JavetScriptEngine implements ScriptEngine {
    @Getter V8Runtime runtime;
    private FastJavetClassConverter converter;
    @Setter
    private RequireResolver requireResolver;
    private final Map<String, ScriptModuleHandle> loadedModules = new HashMap<>();
    /** sourcePath (module filePath / entry name) → the owning ResolvedPackage, for relative-require scoping. */
    private final Map<String, ResolvedPackage> sourcePathToPackage = new HashMap<>();
    private final Map<String, ScriptModule> scriptModules = new HashMap<>();
    private Map<EngineFeatureType<?>, EngineFeature> features = Map.of();
    private int gcTicks = 0;

    public JavetScriptEngine() {}

    @Override
    public ScriptEngineType<?> getType() {
        return KasugaLibJavet.ENGINE_TYPE;
    }

    @Override
    public void init(ScriptConsole console) throws ScriptException {
        try {
            runtime = V8Host.getV8Instance().createV8Runtime();
            KasugaJavetConsoleInterceptor consoleInterceptor = new KasugaJavetConsoleInterceptor(runtime, console);
            consoleInterceptor.register(runtime.getGlobalObject());
            this.converter = new FastJavetClassConverter(runtime);
            converter.setEngine(this);
            SecurityEngineFeature securityFeature = getFeature(SecurityEngineFeatureType.INSTANCE);
            if (securityFeature != null) {
                converter.setSecurityFeature(securityFeature);
            }
            runtime.setConverter(converter);
            runtime.setPromiseRejectCallback((event, promise, value)->{
                if(event.getCode() == 0){
                    console.error("Unhandled Promise Rejection: " + com.caoccao.javet.utils.V8ValueUtils.asString(value));
                }
            });

            BuiltinModuleRegistry registry = lib.kasuga.KasugaLib.getBean(BuiltinModuleRegistry.class);
            Map<String, ScriptModule> modules = registry.createModules(this);
            scriptModules.putAll(modules);
            for (ScriptModule module : scriptModules.values()) {
                module.init();
            }

            if (requireResolver == null) {
                setupRequireResolver();
            }
        } catch (JavetException e) {
            throw new ScriptException(e);
        }
    }

    private void setupRequireResolver() {
        final PackageAwareRequireResolver packageAware = buildPackageAwareResolver();
        this.requireResolver = (moduleName, fromSourcePath) -> {
            // 1. Builtin ScriptModules (e.g. kasuga:timer).
            ScriptModule module = scriptModules.get(moduleName);
            if (module != null) {
                try {
                    return wrapAsModuleHandle(moduleName, module);
                } catch (JavetException e) {
                    throw new ScriptException(e);
                }
            }
            // 2. Builtin Supplier modules.
            try {
                BuiltinModuleRegistry builtinRegistry = lib.kasuga.KasugaLib.getBean(BuiltinModuleRegistry.class);
                Object builtin = builtinRegistry.resolve(moduleName);
                if (builtin != null) {
                    try {
                        return wrapAsModuleHandle(moduleName, builtin);
                    } catch (JavetException e) {
                        throw new ScriptException(e);
                    }
                }
            } catch (ScriptException e) {
                throw e;
            } catch (Exception ignored) {
                // KasugaLib not available (e.g. unit test) — skip builtins.
            }
            // 3. Package-by-name and in-package relative require("./...") — the production path that
            //    was previously only wired in unit tests.
            if (packageAware != null) {
                ScriptModuleHandle handle = packageAware.resolve(moduleName, fromSourcePath);
                if (handle != null) {
                    return handle;
                }
            }
            return null;
        };
    }

    /**
     * Build the package-aware resolver if a {@link PackageRegistry} bean and a {@link JsModuleResolver}
     * are available; otherwise null (unit-test engines without the scripting runtime fall back to builtins).
     */
    private PackageAwareRequireResolver buildPackageAwareResolver() {
        try {
            PackageRegistry packageRegistry = lib.kasuga.KasugaLib.getBean(PackageRegistry.class);
            if (packageRegistry == null) {
                return null;
            }
            if (getType().resolver instanceof JsModuleResolver jsResolver) {
                return new PackageAwareRequireResolver(this, packageRegistry, jsResolver);
            }
        } catch (Exception ignored) {
            // Bean context unavailable — builtins-only resolver.
        }
        return null;
    }

    private ScriptModuleHandle wrapAsModuleHandle(String name, Object object) throws JavetException {
        V8Value v8Value = converter.toV8Value(runtime, object);
        Map<String, ScriptValue> exportMap = new HashMap<>();
        exportMap.put("default", JavetValueBridge.wrap(v8Value));
        return new JavetModuleHandle(exportMap, name, getType());
    }

    @Override
    public void setFeatures(Map<EngineFeatureType<?>, EngineFeature> features) {
        this.features = features;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <F extends EngineFeature> F getFeature(EngineFeatureType<F> type) {
        return (F) features.get(type);
    }

    public ScriptValue execute(String evaluateString) throws ScriptException {
        try{
            return JavetValueBridge.wrap(this.runtime.getExecutor(evaluateString).execute());
        }catch (JavetException e){
            throw new ScriptException(e);
        }
    }

    @Override
    public ScriptValue createValue(Object object) throws ScriptException {
        try {
            return JavetValueBridge.wrap(runtime.getConverter().toV8Value(runtime, object));
        }catch (JavetException e){
            throw new ScriptException(e);
        }
    }

    @Override
    public ScriptModuleHandle loadModule(ResolvedScript script) throws ScriptException {
        String sourcePath = script.filePath();

        ScriptModuleHandle cached = loadedModules.get(sourcePath);
        if (cached != null) return cached;

        // Track owner so relative requires from this module scope to its own package.
        if (script.owner() != null) {
            sourcePathToPackage.put(sourcePath, script.owner());
        }

        // Register partial handle to support circular requires
        // (not yet populated, but present in cache to break cycles)
        loadedModules.put(sourcePath, null);

        String source = readSource(script);
        try {
            V8ValueFunction moduleFunc = runtime.getExecutor(source)
                .setResourceName(sourcePath)
                .compileV8ValueFunction(new String[]{"require", "exports", "module"});

            V8ValueObject moduleObj = runtime.createV8ValueObject();
            V8ValueObject exportsObj = runtime.createV8ValueObject();
            moduleObj.set("exports", exportsObj);

            V8Value requireFunc = createRequireFunc(sourcePath);

            try {
                moduleFunc.callVoid(null, requireFunc, exportsObj, moduleObj);

                V8Value resultExports = moduleObj.get("exports");

                Map<String, ScriptValue> exports = new HashMap<>();
                try {
                    if (resultExports instanceof V8ValueObject resultObj) {
                        var propNames = resultObj.getOwnPropertyNames();
                        try {
                            int len = propNames.getLength();
                            for (int i = 0; i < len; i++) {
                                String key = propNames.getString(i);
                                V8Value val = resultObj.get(key);
                                exports.put(key, JavetValueBridge.wrap(val));
                            }
                        } finally {
                            propNames.close();
                        }
                    }
                } finally {
                    resultExports.close();
                }

                JavetModuleHandle handle = new JavetModuleHandle(exports, sourcePath, getType());
                loadedModules.put(sourcePath, handle);
                return handle;
            } finally {
                requireFunc.close();
                exportsObj.close();
                moduleFunc.close();
                moduleObj.close();
            }
        } catch (JavetException e) {
            loadedModules.remove(sourcePath);
            throw new ScriptException(e);
        }
    }

    @Override
    public ScriptModuleHandle getLoadedModule(String sourcePath) {
        return loadedModules.get(sourcePath);
    }

    @Override
    public void executeEntry(String entryName, InputStream source) throws ScriptException {
        executeEntry(entryName, source, null);
    }

    @Override
    public void executeEntry(String entryName, InputStream source, ResolvedPackage owner) throws ScriptException {
        // Seed the owner so the entry's first-frame relative requires scope to its package.
        if (owner != null) {
            sourcePathToPackage.put(entryName, owner);
        }
        String code = readSource(source);
        try {
            V8ValueFunction moduleFunc = runtime.getExecutor(code)
                .setResourceName(entryName)
                .compileV8ValueFunction(new String[]{"require", "exports", "module"});

            V8ValueObject moduleObj = runtime.createV8ValueObject();
            V8ValueObject exportsObj = runtime.createV8ValueObject();
            moduleObj.set("exports", exportsObj);

            V8Value requireFunc = createRequireFunc(entryName);

            try {
                moduleFunc.callVoid(null, requireFunc, exportsObj, moduleObj);
            } finally {
                requireFunc.close();
                exportsObj.close();
                moduleFunc.close();
                moduleObj.close();
            }
        } catch (JavetException e) {
            throw new ScriptException(e);
        }
    }

    /** The package that owns {@code sourcePath} (a loaded module's filePath or an entry name), or null. */
    public ResolvedPackage getOwningPackage(String sourcePath) {
        return sourcePath == null ? null : sourcePathToPackage.get(sourcePath);
    }

    @Override
    public void tick() {
        for (ScriptModule module : scriptModules.values()) {
            module.tick();
        }
        if (gcTicks++ > 20) {
            runtime.lowMemoryNotification();
            gcTicks = 0;
        }
    }

    @Override
    public void registerGlobal(String name, Object api) {
        try {
            V8ValueObject v8Obj = (V8ValueObject) converter.toV8Value(runtime, api);
            runtime.getGlobalObject().set(name, v8Obj);
        } catch (JavetException e) {
            throw new RuntimeException("Failed to register global API: " + name, e);
        }
    }

    public void close() {
        loadedModules.clear();
        for (ScriptModule module : scriptModules.values()) {
            try { module.close(); } catch (Exception ignored) {}
        }
        scriptModules.clear();

        if(runtime != null){
            try {
                runtime.close();
            } catch (JavetException e) {
                // ignore
            }
            runtime = null;
        }
    }

    public FastJavetClassConverter getConverter() {
        return converter;
    }

    private V8Value createRequireFunc(String fromSourcePath) throws JavetException {
        return runtime.getConverter().toV8Value(runtime,
            (java.util.function.Function<String, V8Value>) moduleName -> {
                if (requireResolver == null) {
                    throw new RuntimeException("No require resolver set");
                }
                try {
                    ScriptModuleHandle handle = requireResolver.resolve(moduleName, fromSourcePath);
                    if (handle == null) {
                        throw new RuntimeException("Module not found: " + moduleName);
                    }
                    try {
                        V8ValueObject exports = runtime.createV8ValueObject();
                        for (String key : handle.getExportNames()) {
                            ScriptValue val = handle.getExport(key);
                            if (val != null) {
                                exports.set(key, converter.toV8Value(runtime, val));
                            }
                        }
                        return exports;
                    } catch (JavetException e) {
                        throw new RuntimeException(e);
                    }
                } catch (ScriptException e) {
                    throw new RuntimeException(e);
                }
            });
    }

    private String readSource(ResolvedScript script) throws ScriptException {
        try (InputStream is = script.open()) {
            return readSource(is);
        } catch (IOException e) {
            throw new ScriptException(e);
        }
    }

    private String readSource(InputStream is) throws ScriptException {
        try {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ScriptException(e);
        }
    }
}
