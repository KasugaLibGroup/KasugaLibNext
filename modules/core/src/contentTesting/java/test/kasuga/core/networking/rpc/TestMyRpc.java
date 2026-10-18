package test.kasuga.core.networking.rpc;

import lib.kasuga.core.networking.rpc.Rpc;
import lib.kasuga.registration.Registry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class TestMyRpc {
    public static void test() {
        // on client
        myRpc.call(null, 42, true, "Hello, RPC!");
    }


    public static Registry r = new Registry("kasuga_lib");
    public static Rpc.RpcV3<Integer, Boolean, String> myRpc
            = Rpc.wrap(
            "test_rpc",
            TestMyRpc::rpcFunction,
            ByteBufCodecs.VAR_INT,
            ByteBufCodecs.BOOL,
            ByteBufCodecs.STRING_UTF8
    ).executeOnServer().runOnMainThread().register(r);
    public static void rpcFunction(IPayloadContext context, int i1, boolean i2, String i3) {
        System.out.println("Rpc function called with: " + i1 + ", " + i2 + ", " + i3);
        System.out.println("Thread is running on " + Thread.currentThread().getName());
        System.out.println("Side="+(context.player().level().isClientSide() ? "ClientSide" : "ServerSide"));
    }


    public static Rpc.RpcR1<Integer, Integer> myRpcWithResponse
            = Rpc.wrap(
            "test_rpc_with_response",
            TestMyRpc::rpcFunctionWithResponse,
            ByteBufCodecs.VAR_INT,
            ByteBufCodecs.VAR_INT
    ).executeOnServer().runOnMainThread().setTimeoutSeconds(10).register(r);

    public static int rpcFunctionWithResponse(IPayloadContext context, int i1) {
        return 42;
    }

    public static void testWithResponse() {
        myRpcWithResponse.call(null, 41).thenApplyAsync(result -> {
            System.out.println("Rpc with response returned: " + result);
            System.out.println("Thread is running on " + Thread.currentThread().getName());
            return result;
        }, Minecraft.getInstance()).exceptionallyAsync((e)->{
            System.out.println("Error callback called at: " + Thread.currentThread().getName());
            e.printStackTrace();
            return null;
        }, Minecraft.getInstance());
    }

    public static void tick(ClientTickEvent.Post event) {
        myRpcWithResponse.tick();
    }
}
