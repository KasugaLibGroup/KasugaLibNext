package test.kasuga.slp.javet.module;

import lib.kasuga.core.resource.lifecycle.*;
import lib.kasuga.core.resource.routing.*;
import lib.kasuga.core.resource.script.*;
import lib.kasuga.core.resource.source.*;
import lib.kasuga.core.resource.view.ResourceAccess;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import lib.kasuga.scripting.ScriptEngineType;
import lib.kasuga.scripting.resource.*;
import lib.kasuga.slp.javet.JavetScriptEngine;
import lib.kasuga.slp.javet.KasugaLibJavet;
import lib.kasuga.slp.javet.module.JsModuleResolver;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceScriptTicketIntegrationTest {
    @Test void routedTrustedPackageRegistersBindsAndRunsTicketsOnItsScriptThread() throws Exception {
        var saved = KasugaLibJavet.ENGINE_TYPE;
        CountDownLatch engineClosed = new CountDownLatch(1);
        ScriptEngineType<JavetScriptEngine> engineType = ScriptEngineType.<JavetScriptEngine>builder(() -> new JavetScriptEngine() {
            @Override public void close() { try { super.close(); } finally { engineClosed.countDown(); } }
        }).scriptType("javascript").resolver(new JsModuleResolver()).build();
        KasugaLibJavet.ENGINE_TYPE = engineType;
        try (var vfs = new InMemoryVfs("scripts"); var scope = new ResourceCoordinator("server-test")) {
            vfs.writeString(VfsPath.of("scripts/package.toml"), "name = 'ticket-test'\nengine = 'javascript'\n[entry]\nserver = ['index']\n");
            vfs.writeString(VfsPath.of("scripts/index.js"), """
                    ScriptBlocks.register('increment', '{"arguments":["number"],"result":"number"}', value => value + 1);
                    ScriptBlocks.register('explode', '{"arguments":[],"result":"void"}', () => { throw new Error('ticket-boom'); });
                    ScriptBlocks.register('read', '{"arguments":["string"],"result":"string"}', uri => Resources.readText(uri, 4096));
                    """
            );
            vfs.writeString(VfsPath.of("scripts/data.txt"), "owned");
            var descriptor = new ResourceSourceDescriptor("test", "demo", "main", 1, ResourceSourceDescriptor.Origin.EXTERNAL);
            try (var source = ResourceSources.vfs(descriptor, vfs, false, "", "", InputPolicy.DEFAULT)) {
                scope.register(new ManagedScriptEntryPoint(name -> name.equals("javascript") ? engineType : null, ignored -> true, ignored -> true,
                        (batch, invocation, identity) -> new ResourceAccess(batch.catalog(), List.of(new ResourceAccess.Grant("test", "demo", "main", "scripts",
                                Set.of(ResourceAccess.Operation.READ, ResourceAccess.Operation.QUERY))), Map.of(), 4096, 8192)));
                var routing = new ResourceRouting(List.of(new ResourceRouting.Binding("scripts", "demo", "scripts", List.of(
                        new ResourceRouting.Output("blocks", ManagedScriptEntryPoint.ID, ResourceEvent.RESOURCE_RELOAD, LogicalSide.SERVER, "scripts")))));
                var generation = scope.reload(List.of(source), routing, ResourceEvent.RESOURCE_RELOAD, LogicalSide.SERVER, Runnable::run, Runnable::run).get(15, TimeUnit.SECONDS);
                var context = new InvocationContext("server-test", generation.catalog().generation(), ResourceEvent.RESOURCE_RELOAD, LogicalSide.SERVER, Runnable::run, Runnable::run);
                var ref = ScriptReferenceCodec.decode(StrictResourceJson.parse("""
                        {"package":"ksg://demo/roots/main/scripts/package.toml","block":"increment","side":"server",
                         "contract":{"arguments":["number"],"result":"number"}}
                        """), "/on_update", LogicalSide.SERVER, ignored -> true);
                var registry = (ScriptBlockRegistry) generation.consumers().get(ManagedScriptEntryPoint.ID).value();
                ScriptBlockHandle handle = registry.bind(ref, context).toCompletableFuture().get(5, TimeUnit.SECONDS);
                ScriptTicket ticket = handle.submit(List.of(41));
                var outcome = ticket.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(ScriptTicket.Status.SUCCEEDED, outcome.status());
                assertEquals(42, ((Number) outcome.value()).intValue());
                var exploding = new ScriptBlockRef(ref.packageUri(), "explode", new ScriptContract(List.of(), ScriptContract.Type.VOID), LogicalSide.SERVER, "/on_error");
                var failed = registry.bind(exploding, context).toCompletableFuture().get(5, TimeUnit.SECONDS).submit(List.of());
                assertEquals(ScriptTicket.Status.FAILED, failed.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                var read = new ScriptBlockRef(ref.packageUri(), "read", new ScriptContract(List.of(ScriptContract.Type.STRING), ScriptContract.Type.STRING), LogicalSide.SERVER, "/read");
                var reader = registry.bind(read, context).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals("owned", reader.submit(List.of("ksg://demo/roots/main/scripts/data.txt")).completion().toCompletableFuture().get(5, TimeUnit.SECONDS).value());
                assertEquals(ScriptTicket.Status.FAILED, reader.submit(List.of("ksg://demo/roots/main/private.txt")).completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                registry.close();
                assertEquals(ScriptTicket.Status.CANCELLED, handle.submit(List.of(1)).status());
            }
        } finally {
            try { assertTrue(engineClosed.await(5, TimeUnit.SECONDS), "engine must be released on scope shutdown"); }
            finally { KasugaLibJavet.ENGINE_TYPE = saved; }
        }
    }
}
