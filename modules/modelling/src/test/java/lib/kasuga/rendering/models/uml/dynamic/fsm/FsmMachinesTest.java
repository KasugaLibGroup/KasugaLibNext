package lib.kasuga.rendering.models.uml.dynamic.fsm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Handle minting/reversal, latest-pointer semantics, release, and invalidation notifications. */
class FsmMachinesTest {

    private static Id rl(String path) {
        return Id.fromNamespaceAndPath("test", path);
    }

    private static StateMachine<Object> machine() {
        return StateMachine.builder(new Object()).build();
    }

    @Test
    void handlesIncrementAndResolve() {
        FsmMachines machines = new FsmMachines();
        StateMachine<?> a = machine();
        StateMachine<?> b = machine();
        long h1 = machines.register(rl("a"), a);
        long h2 = machines.register(rl("b"), b);
        assertTrue(h2 > h1);
        assertSame(a, machines.resolve(h1));
        assertSame(b, machines.resolve(h2));
        assertSame(a, machines.latest(rl("a")));
    }

    @Test
    void reRegisterKeepsBothHandlesLive() {
        FsmMachines machines = new FsmMachines();
        StateMachine<?> firstMachine = machine();
        long firstHandle = machines.register(rl("a"), firstMachine);
        StateMachine<?> secondMachine = machine();
        long secondHandle = machines.register(rl("a"), secondMachine);
        assertNotEquals(firstHandle, secondHandle);
        // multi-instance: both handles stay live, each resolving to its own machine
        assertSame(firstMachine, machines.resolve(firstHandle));
        assertSame(secondMachine, machines.resolve(secondHandle));
        assertSame(secondMachine, machines.latest(rl("a")), "latest(id) returns the most-recently registered machine");
    }

    @Test
    void removeAllClearsEveryHandleForTheId() {
        FsmMachines machines = new FsmMachines();
        StateMachine<?> first = machine();
        StateMachine<?> second = machine();
        long h1 = machines.register(rl("a"), first);
        long h2 = machines.register(rl("a"), second);
        machines.removeAll(rl("a"));
        assertNull(machines.resolve(h1));
        assertNull(machines.resolve(h2));
        assertNull(machines.latest(rl("a")));
    }

    @Test
    void releaseDropsHandleAndClearsLatest() {
        FsmMachines machines = new FsmMachines();
        StateMachine<?> m = machine();
        long handle = machines.register(rl("a"), m);
        machines.release(handle);
        assertNull(machines.resolve(handle));
        // tightened contract: releasing the latest handle also drops the id's latest pointer
        assertNull(machines.latest(rl("a")));
        // re-register after a release has no previous handle -> no notification
        AtomicInteger notifications = new AtomicInteger();
        machines.addListener(id -> notifications.incrementAndGet());
        long newHandle = machines.register(rl("a"), m);
        assertEquals(0, notifications.get());
        assertNotEquals(handle, newHandle);
        assertSame(m, machines.resolve(newHandle));
    }

    @Test
    void releaseOfNonLatestKeepsLatestPointer() {
        FsmMachines machines = new FsmMachines();
        StateMachine<?> first = machine();
        StateMachine<?> second = machine();
        long h1 = machines.register(rl("a"), first);
        machines.register(rl("a"), second);
        machines.release(h1);
        assertNull(machines.resolve(h1));
        assertSame(second, machines.latest(rl("a")), "releasing a non-latest handle leaves latest untouched");
    }

    @Test
    void removeAllNotifiesAndCleans() {
        FsmMachines machines = new FsmMachines();
        List<Id> notified = new ArrayList<>();
        machines.addListener(notified::add);
        StateMachine<?> m = machine();
        long handle = machines.register(rl("a"), m);

        machines.removeAll(rl("a"));
        assertNull(machines.latest(rl("a")));
        assertNull(machines.resolve(handle));
        assertEquals(List.of(rl("a")), notified);

        // removing an id with nothing registered does not notify
        machines.removeAll(rl("missing"));
        assertEquals(1, notified.size());
    }

    @Test
    void reRegisterNotifiesLatestChange() {
        FsmMachines machines = new FsmMachines();
        List<Id> notified = new ArrayList<>();
        machines.addListener(notified::add);
        machines.register(rl("a"), machine());
        assertTrue(notified.isEmpty(), "first registration does not notify");
        machines.register(rl("a"), machine());
        assertEquals(List.of(rl("a")), notified, "re-register notifies the latest change");
    }

    @Test
    void clearNotifiesEachId() {
        FsmMachines machines = new FsmMachines();
        AtomicInteger notifications = new AtomicInteger();
        machines.addListener(id -> notifications.incrementAndGet());
        machines.register(rl("a"), machine());
        machines.register(rl("b"), machine());
        long handle = machines.register(rl("c"), machine());
        machines.clear();
        assertEquals(3, notifications.get());
        assertNull(machines.latest(rl("a")));
        assertNull(machines.resolve(handle));
    }
}
