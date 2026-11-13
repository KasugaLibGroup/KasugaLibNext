package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class VfsPerformanceRegressionTest {
    @TempDir Path root;
    private static final int MIB = 1024 * 1024;
    private static VfsPath p(String value) { return VfsPath.of(value); }
    @FunctionalInterface interface Checked { void run() throws Exception; }

    private static long allocated(Checked action) throws Exception {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 3; i++) action.run();
        long id = Thread.currentThread().threadId(), start = bean.getThreadAllocatedBytes(id);
        action.run();
        return bean.getThreadAllocatedBytes(id) - start;
    }

    @ParameterizedTest
    @ValueSource(strings = {"directory", "mount", "composite"})
    void metadataQueriesDoNotAllocateThePayload(String wrapper) throws Exception {
        Files.write(root.resolve("large"), new byte[8 * MIB]);
        try (var disk = new DirectoryVfs("disk", root); var mount = new MountTableVfs("mount");
             var composite = new CompositeVfs("composite", List.of(disk), VfsConflictPolicy.FIRST_WINS)) {
            mount.mount(p("disk"), disk);
            VirtualFileSystem vfs = wrapper.equals("mount") ? mount : wrapper.equals("composite") ? composite : disk;
            VfsPath file = p(wrapper.equals("mount") ? "disk/large" : "large");
            long bytes = allocated(() -> {
                assertTrue(vfs.isRegularFile(file));
                assertEquals(8 * MIB, vfs.stat(file).size());
            });
            assertTrue(bytes < MIB, "Metadata allocated payload bytes: " + bytes);
        }
    }

    @Test
    void memoryReadStreamKeepsImmutableVersionWithoutCopyingPayload() throws Exception {
        try (var memory = new InMemoryVfs("memory")) {
            memory.writeBytes(p("large"), new byte[8 * MIB]);
            try (var old = memory.openRead(p("large"))) {
                memory.writeString(p("large"), "replacement");
                assertEquals(0, old.read());
                assertEquals(8 * MIB - 1, old.readAllBytes().length);
            }
            byte[] callerCopy = memory.readBytes(p("large")); callerCopy[0] = 0;
            assertEquals("replacement", memory.readString(p("large")));
            memory.writeBytes(p("large"), new byte[8 * MIB]);
            long bytes = allocated(() -> { try (var in = memory.openRead(p("large"))) { assertEquals(0, in.read()); } });
            assertTrue(bytes < 128 * 1024, "Opening a read stream copied the payload: " + bytes);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"directory", "memory"})
    void chunkedOutputDoesNotGrowAndCloneWholePayloads(String backend) throws Exception {
        try (VirtualFileSystem vfs = backend.equals("directory") ? new DirectoryVfs("disk", root) : new InMemoryVfs("memory")) {
            byte[] chunk = new byte[8192]; Arrays.fill(chunk, (byte) 7);
            long bytes = allocated(() -> {
                try (var out = vfs.openWrite(p("large"))) {
                    for (int i = 0; i < 8 * MIB; i += chunk.length) out.write(chunk);
                }
            });
            long limit = backend.equals("directory") ? MIB : 10L * MIB;
            assertTrue(bytes < limit, "Output staging allocated " + bytes + " bytes");
            assertEquals(8 * MIB, vfs.stat(p("large")).size());
            try (var in = vfs.openRead(p("large"))) { assertEquals(7, in.read()); }
        }
    }

    @Test
    void unchangedPollingUsesBoundedHashBuffers() throws Exception {
        Files.write(root.resolve("large"), new byte[8 * MIB]);
        try (var disk = new DirectoryVfs("disk", root)) {
            long bytes = allocated(() -> assertTrue(disk.pollExternalChanges().isEmpty()));
            assertTrue(bytes < MIB, "Polling allocated the full payload: " + bytes);
        }
    }

    @Test
    void smallDiskTransactionDoesNotInspectUnrelatedDirectories() throws Exception {
        try (var disk = new DirectoryVfs("disk", root)) {
            Path unrelated = Files.createDirectory(root.resolve("unrelated"));
            assumeTrue(Files.getFileStore(unrelated).supportsFileAttributeView("posix"));
            var permissions = Files.getPosixFilePermissions(unrelated);
            Files.setPosixFilePermissions(unrelated, Set.of());
            try {
                try (var tx = disk.beginTransaction()) { tx.putString(p("hot"), "value").commit(); }
                assertEquals("value", disk.readString(p("hot")));
            } finally { Files.setPosixFilePermissions(unrelated, permissions); }
        }
    }

    @Test
    void smallMemoryTransactionDoesNotCopyTheWholeIndex() throws Exception {
        try (var memory = new InMemoryVfs("memory")) {
            for (int i = 0; i < 20000; i++) memory.writeString(p("unrelated/f" + i), "x");
            long bytes = allocated(() -> { try (var tx = memory.beginTransaction()) { tx.putString(p("hot"), "value").commit(); } });
            assertTrue(bytes < 128 * 1024, "A one-path transaction copied the complete tree: " + bytes);
            assertEquals("x", memory.readString(p("unrelated/f19999")));
        }
    }

    @Test
    void crossMountFileMoveDoesNotEnumerateUnrelatedTrees() throws Exception {
        AtomicInteger enumerations = new AtomicInteger();
        try (var a = new InMemoryVfs("a"); var b = new InMemoryVfs("b"); var table = new MountTableVfs("table")) {
            for (int i = 0; i < 100; i++) { a.writeString(p("unrelated" + i + "/f"), "a"); b.writeString(p("unrelated" + i + "/f"), "b"); }
            a.writeString(p("hot"), "moved");
            table.mount(p("a"), counting(a, enumerations)); table.mount(p("b"), counting(b, enumerations));
            table.move(p("a/hot"), p("b/hot"));
            assertEquals("moved", b.readString(p("hot")));
            assertEquals(0, enumerations.get(), "Moving one file listed unrelated directories");
        }
    }

    private static VirtualFileSystem counting(VirtualFileSystem delegate, AtomicInteger enumerations) {
        return (VirtualFileSystem) Proxy.newProxyInstance(VirtualFileSystem.class.getClassLoader(), new Class<?>[]{VirtualFileSystem.class}, (proxy, method, args) -> {
            if (method.getName().equals("list") || method.getName().equals("walk")) enumerations.incrementAndGet();
            try { return method.invoke(delegate, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }

    @Test
    void independentTransactionsDoNotShareAGlobalPreparationLock() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try (var a = new InMemoryVfs("a"); var b = new InMemoryVfs("b"); var tx = a.beginTransaction()) {
            tx.putString(p("file"), "a");
            VfsWriteTransaction blocked = (VfsWriteTransaction) Proxy.newProxyInstance(VfsWriteTransaction.class.getClassLoader(), new Class<?>[]{VfsWriteTransaction.class}, (proxy, method, args) -> {
                if (method.getName().equals("prepare")) { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test gate timed out"); }
                try { return method.invoke(tx, args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            Future<?> first = workers.submit(() -> { TransactionSupport.commit(List.of(blocked)); return null; });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                Future<?> independent = workers.submit(() -> { try (var other = b.beginTransaction()) { other.putString(p("file"), "b").commit(); } return null; });
                independent.get(1, TimeUnit.SECONDS);
                assertEquals("b", b.readString(p("file")));
            } finally { release.countDown(); first.get(5, TimeUnit.SECONDS); }
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    private record Gate(CountDownLatch entered, CountDownLatch release, AtomicBoolean once) {}
    private static final AtomicReference<Gate> HASH_GATE = new AtomicReference<>();
    private static final Provider ORIGINAL_SHA = sha().getProvider();
    private static MessageDigest sha() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }

    public static final class BlockingDigest extends MessageDigestSpi {
        private final MessageDigest delegate;
        public BlockingDigest() {
            try { delegate = MessageDigest.getInstance("SHA-256", ORIGINAL_SHA); }
            catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        }
        @Override protected void engineUpdate(byte input) { delegate.update(input); }
        @Override protected void engineUpdate(byte[] input, int offset, int len) {
            Gate gate = HASH_GATE.get();
            if (gate != null && Thread.currentThread().getName().equals("vfs-test-poll") && gate.once().compareAndSet(false, true)) {
                gate.entered().countDown();
                try { if (!gate.release().await(5, TimeUnit.SECONDS)) throw new AssertionError("Hash gate timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
            delegate.update(input, offset, len);
        }
        @Override protected byte[] engineDigest() { return delegate.digest(); }
        @Override protected void engineReset() { delegate.reset(); }
    }

    @Test
    void scanDoesNotBlockQueriesAndConcurrentInternalWriteIsNotMisreported() throws Exception {
        Files.writeString(root.resolve("file"), "old");
        try (var disk = new DirectoryVfs("disk", root)) {
            var gate = new Gate(new CountDownLatch(1), new CountDownLatch(1), new AtomicBoolean());
            Provider provider = new Provider("KasugaVfsBlockingHash", "1", "Test hash scheduling") {};
            provider.put("MessageDigest.SHA-256", BlockingDigest.class.getName());
            var workers = Executors.newFixedThreadPool(2);
            Security.insertProviderAt(provider, 1); HASH_GATE.set(gate);
            try {
                Future<Optional<VfsChangeEvent>> poll = workers.submit(() -> {
                    Thread.currentThread().setName("vfs-test-poll"); return disk.pollExternalChanges();
                });
                assertTrue(gate.entered().await(3, TimeUnit.SECONDS));
                Future<Boolean> query = workers.submit(() -> disk.exists(p("file")));
                assertTrue(query.get(1, TimeUnit.SECONDS));
                disk.writeString(p("file"), "new");
                gate.release().countDown();
                assertTrue(poll.get(5, TimeUnit.SECONDS).isEmpty());
                assertEquals("new", disk.readString(p("file")));
            } finally {
                gate.release().countDown(); HASH_GATE.set(null); workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); Security.removeProvider(provider.getName());
            }
        }
    }

    @Test
    void externalSameSizeSameTimestampChangesStillHaveFreshHashes() throws Exception {
        Path file = root.resolve("file"); Files.writeString(file, "old");
        try (var disk = new DirectoryVfs("disk", root)) {
            FileTime time = Files.getLastModifiedTime(file);
            Files.writeString(file, "new"); Files.setLastModifiedTime(file, time);
            var event = disk.pollExternalChanges().orElseThrow();
            assertEquals("file", event.changes().getFirst().path().asString());
            assertEquals(HexFormat.of().formatHex(sha().digest("new".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    event.changes().getFirst().stat().sha256().orElseThrow());
        }
    }

    @Test
    void reusingAnOpenZipReusesItsIndexAndKeepsPrefixBoundaries() throws Exception {
        Path archive = root.resolve("pack.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            for (int i = 0; i < 1000; i++) {
                out.putNextEntry(new java.util.zip.ZipEntry("a/f" + i)); out.write(i); out.closeEntry();
            }
            for (String other : List.of("a-b/file", "a.b/file", "a0/file")) {
                out.putNextEntry(new java.util.zip.ZipEntry(other)); out.write(1); out.closeEntry();
            }
        }
        try (var file = new java.util.zip.ZipFile(archive.toFile())) {
            long bytes = allocated(() -> {
                try (var vfs = new ZipVfs("zip", file, false)) {
                    assertEquals(1000, vfs.walk(p("a")).size());
                }
            });
            assertTrue(bytes < 128 * 1024, "An unchanged open archive rebuilt its index: " + bytes);
        }
    }

    @Test
    void preparingStreamContentDoesNotHoldTheTargetStateLock() throws Exception {
        try (var disk = new DirectoryVfs("disk", root); var tx = disk.beginTransaction()) {
            disk.writeString(p("keep"), "original");
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            tx.putStream(p("new"), () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Source gate timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                return new ByteArrayInputStream(new byte[]{1});
            });
            var workers = Executors.newFixedThreadPool(2);
            Future<?> commit = workers.submit(() -> { tx.commit(); return null; });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals("original", workers.submit(() -> disk.readString(p("keep"))).get(1, TimeUnit.SECONDS));
            } finally { release.countDown(); commit.get(5, TimeUnit.SECONDS); workers.shutdownNow(); }
        }
    }

    @Test
    void failingDeferredSourceClosesAndLeavesNoWritesOrStaging() throws Exception {
        try (var disk = new DirectoryVfs("disk", root); var memory = new InMemoryVfs("memory"); var table = new MountTableVfs("table")) {
            table.mount(p("disk"), disk); table.mount(p("memory"), memory);
            disk.writeString(p("keep"), "disk-old"); memory.writeString(p("keep"), "memory-old");
            List<VfsChangeEvent> events = new ArrayList<>(); table.watchAll(events::add);
            AtomicBoolean closed = new AtomicBoolean();
            try (var tx = table.beginTransaction()) {
                tx.putString(p("disk/keep"), "disk-new").putString(p("memory/keep"), "memory-new");
                tx.putStream(p("disk/failed"), () -> new InputStream() {
                    int count;
                    @Override public int read() throws IOException { if (count++ < 10) return 1; throw new IOException("Injected source failure"); }
                    @Override public void close() { closed.set(true); }
                });
                assertThrows(IOException.class, tx::commit);
            }
            assertTrue(closed.get()); assertTrue(events.isEmpty());
            assertEquals("disk-old", disk.readString(p("keep"))); assertEquals("memory-old", memory.readString(p("keep")));
            assertFalse(disk.exists(p("failed"))); assertTrue(disk.pollExternalChanges().isEmpty());
            try (var files = Files.walk(root)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".ksgvfs-"))); }
        }
    }

    @Test
    void openWriteRevalidatesSymlinksBeforePublishing() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path mounted = Files.createDirectory(root.resolve("mounted"));
        try (var disk = new DirectoryVfs("disk", mounted)) {
            Files.createDirectory(mounted.resolve("parent"));
            var out = disk.openWrite(p("parent/file")); out.write(1);
            try {
                Files.delete(mounted.resolve("parent"));
                try { Files.createSymbolicLink(mounted.resolve("parent"), outside); }
                catch (IOException | UnsupportedOperationException failure) { assumeTrue(false, "Host cannot create symlinks"); }
                assertThrows(VfsInvalidPathException.class, out::close);
                assertFalse(Files.exists(outside.resolve("file")));
            } finally { try { out.close(); } catch (IllegalArgumentException ignored) {} }
            try (var files = Files.list(mounted)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".ksgvfs-"))); }
        }
    }

    @Test
    void oppositeMountOrdersRemainDeadlockFreeAndPublishCompleteResults() throws Exception {
        try (var a = new InMemoryVfs("a"); var b = new InMemoryVfs("b"); var table = new MountTableVfs("table")) {
            table.mount(p("a"), a); table.mount(p("b"), b);
            var workers = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int worker = 0; worker < 2; worker++) {
                    final boolean reverse = worker == 1;
                    futures.add(workers.submit(() -> {
                        assertTrue(start.await(3, TimeUnit.SECONDS));
                        for (int i = 0; i < 30; i++) try (var tx = table.beginTransaction()) {
                            String value = reverse + ":" + i;
                            tx.putString(p(reverse ? "b/value" : "a/value"), value)
                                    .putString(p(reverse ? "a/value" : "b/value"), value).commit();
                        }
                        return null;
                    }));
                }
                start.countDown(); for (var future : futures) future.get(10, TimeUnit.SECONDS);
                assertEquals(a.readString(p("value")), b.readString(p("value")));
            } finally { start.countDown(); workers.shutdownNow(); }
        }
    }

    @Test
    void explicitFingerprintRemainsFreshAcrossSameMetadataReplacement() throws Exception {
        Files.writeString(root.resolve("file"), "old");
        try (var disk = new DirectoryVfs("disk", root)) {
            String old = disk.statWithHash(p("file")).sha256().orElseThrow();
            FileTime time = Files.getLastModifiedTime(root.resolve("file"));
            Files.writeString(root.resolve("file"), "new"); Files.setLastModifiedTime(root.resolve("file"), time);
            assertNotEquals(old, disk.statWithHash(p("file")).sha256().orElseThrow());
        }
    }

    @Test
    void scanCachePreservesCaseOnlyDirectoryRenames() throws Exception {
        Files.createDirectory(root.resolve("lower")); Files.writeString(root.resolve("lower/file"), "same");
        try (var disk = new DirectoryVfs("disk", root)) {
            Files.move(root.resolve("lower"), root.resolve("intermediate"));
            Files.move(root.resolve("intermediate"), root.resolve("UPPER"));
            var changes = disk.pollExternalChanges().orElseThrow().changes();
            assertTrue(changes.stream().anyMatch(c -> c.path().equals(p("lower/file")) && c.kind() == VfsChangeEvent.ChangeKind.DELETED));
            assertTrue(changes.stream().anyMatch(c -> c.path().equals(p("UPPER/file")) && c.kind() == VfsChangeEvent.ChangeKind.CREATED));
            assertEquals(List.of(p("UPPER/file")), disk.walk(VfsPath.ROOT));
            assertTrue(disk.pollExternalChanges().isEmpty());
        }
    }
}
