package lib.kasuga.slp.javet;

import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.loader.JavetLibLoader;
import lib.kasuga.KasugaLib;
import lib.kasuga.KasugaLibStartupEvent;
import lib.kasuga.scripting.ScriptEngineRegistry;
import lib.kasuga.scripting.ScriptEngineType;
import lib.kasuga.scripting.fsm.FsmApiRegistration;
import lib.kasuga.scripting.fsm.FsmAutoTickModule;
import lib.kasuga.scripting.module.BuiltinModuleRegistry;
import lib.kasuga.scripting.security.SecurityEngineFeatureType;
import lib.kasuga.scripting.timer.TimerModule;
import lib.kasuga.slp.javet.downloader.JavetDownloader;
import lib.kasuga.slp.javet.module.JsModuleResolver;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

@Mod(KasugaLibJavet.MODID)
public class KasugaLibJavet {

    public static ScriptEngineType<JavetScriptEngine> ENGINE_TYPE = null;
    public static final String MODID = "kasuga_lib_javet";

    public static final List<Throwable> ENGINE_RUNTIME_ISSUES = new ArrayList<>();

    protected static JavetDownloader javetDownloader = new JavetDownloader();
    public KasugaLibJavet(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::onKasugaLibStartup);

        JavetLibLoader.setLibLoadingListener(javetDownloader);

        try{
            if(!V8Host.getV8Instance().isLibraryLoaded()) {
                V8Host.getV8Instance().loadLibrary();
            }
            try(V8Runtime v8Runtime = V8Host.getV8Instance().createV8Runtime()) {
                v8Runtime.getExecutor("1 + 1").executeVoid();
            }
        } catch (Exception e) {
            e.printStackTrace();
            ENGINE_RUNTIME_ISSUES.add(e);
        }

        libraryLoaded = true;
        checkRegistration();

    }

    private void onKasugaLibStartup(KasugaLibStartupEvent event) {
        checkRegistration();
    }


    private boolean isRegistered = false;
    private boolean libraryLoaded = false;

    private synchronized void checkRegistration() {
        if(isRegistered)
            return;

        if(!KasugaLib.isRunning() || !libraryLoaded)
            return;

        isRegistered = true;

        KasugaLib.getBean(BuiltinModuleRegistry.class).registerFactory(TimerModule.FACTORY);
        KasugaLib.getBean(BuiltinModuleRegistry.class).registerFactory(FsmAutoTickModule.FACTORY);

        ENGINE_TYPE = FsmApiRegistration.install(
                ScriptEngineType.<JavetScriptEngine>builder(
                                JavetScriptEngine::new
                        ).scriptType("javascript")
                        .resolver(new JsModuleResolver())
                        .addFeature(SecurityEngineFeatureType.INSTANCE)
                        .addGlobalApi("ScriptingTestApi", ScriptingTestApi::new))
                .build();

        ENGINE_TYPE.loadingIssues.addAll(ENGINE_RUNTIME_ISSUES);

        KasugaLib.getBean(ScriptEngineRegistry.class)
                .register(ResourceLocation.tryBuild(MODID, "javet"), List.of("javascript"), ENGINE_TYPE, 0);
    }
}
