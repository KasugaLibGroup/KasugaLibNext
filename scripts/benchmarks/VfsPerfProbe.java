import lib.kasuga.core.vfs.*;
import lib.kasuga.core.vfs.backend.*;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Local diagnostic benchmark, not JMH. All datasets are synthetic and temporary. */
public class VfsPerfProbe {
    interface Trial { long run() throws Exception; }
    static volatile long sink;
    static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static PrintWriter output;
    static VfsPath p(String value) { return VfsPath.of(value); }

    static void measure(String label, int nodes, int bytes, int loops, Trial action) throws Exception {
        for (int warm = 0; warm < 3; warm++) for (int j = 0; j < loops; j++) sink = action.run();
        double[] elapsed = new double[9], cpu = new double[9], allocated = new double[9];
        long thread = Thread.currentThread().threadId();
        for (int sample = 0; sample < elapsed.length; sample++) {
            long a = MX.getThreadAllocatedBytes(thread), c = MX.getCurrentThreadCpuTime(), t = System.nanoTime();
            for (int j = 0; j < loops; j++) sink = action.run();
            elapsed[sample] = (System.nanoTime() - t) / 1e6 / loops;
            cpu[sample] = (MX.getCurrentThreadCpuTime() - c) / 1e6 / loops;
            allocated[sample] = (MX.getThreadAllocatedBytes(thread) - a) / (double) loops;
        }
        Arrays.sort(elapsed); Arrays.sort(cpu); Arrays.sort(allocated);
        output.printf(Locale.ROOT, "{\"case\":\"%s\",\"nodes\":%d,\"file_bytes\":%d,\"loops_per_sample\":%d,\"samples\":9,\"median_ms\":%.6f,\"min_ms\":%.6f,\"max_ms\":%.6f,\"cpu_ms\":%.6f,\"allocated_bytes\":%.1f}%n",
                label, nodes, bytes, loops, elapsed[4], elapsed[0], elapsed[8], cpu[4], allocated[4]);
        System.out.printf(Locale.ROOT, "%s n=%d bytes=%d: %.4f ms/op, %.2f MiB allocated/op%n", label, nodes, bytes, elapsed[4], allocated[4] / 1048576);
    }

    static void diskFiles(Path root, int count, int bytes) throws Exception {
        Files.createDirectories(root);
        byte[] content = new byte[bytes]; new Random(20261113).nextBytes(content);
        for (int i = 0; i < count; i++) {
            Path dir = root.resolve("d" + i / 100); Files.createDirectories(dir);
            Files.write(dir.resolve("f" + i), content);
        }
    }

    static void memoryFiles(InMemoryVfs memory, int count, int perDirectory) throws Exception {
        byte[] content = new byte[32];
        for (int i = 0; i < count; i++) memory.writeBytes(p("d" + i / perDirectory + "/f" + i), content);
    }

    static void directoryCases(Path workspace) throws Exception {
        for (int count : new int[]{100, 1000, 10000}) {
            Path root = workspace.resolve("small-" + count); diskFiles(root, count, 4096);
            try (DirectoryVfs vfs = new DirectoryVfs("disk", root)) {
                VfsPath leaf = p("d0/f0"), hot = p("hot"); byte[] value = new byte[1024];
                measure("directory.exists", count, 4096, 100, () -> vfs.exists(leaf) ? 1 : 0);
                measure("directory.poll_unchanged", count, 4096, 1, () -> vfs.pollExternalChanges().isPresent() ? 1 : 0);
                measure("directory.write_1k", count, 1024, 5, () -> { vfs.writeBytes(hot, value); return 1; });
                measure("directory.transaction_empty", count, 0, 1, () -> { try (var tx = vfs.beginTransaction()) { return tx.commit().changes().size(); } });
                measure("directory.transaction_put_1k", count, 1024, 1, () -> { try (var tx = vfs.beginTransaction()) { return tx.putBytes(hot, value).commit().changes().size(); } });
            }
            remove(root);
        }
        for (int bytes : new int[]{65536, 262144}) {
            Path root = workspace.resolve("large-tree-" + bytes); diskFiles(root, 1000, bytes);
            try (DirectoryVfs vfs = new DirectoryVfs("disk", root)) {
                measure("directory.poll_unchanged", 1000, bytes, 1, () -> vfs.pollExternalChanges().isPresent() ? 1 : 0);
                if (bytes == 262144) readerDuringPoll(vfs, 1000, bytes);
            }
            remove(root);
        }
    }

    static void readerDuringPoll(DirectoryVfs vfs, int count, int bytes) throws Exception {
        var pool = Executors.newSingleThreadExecutor(); double[] waits = new double[9];
        VfsPath file = p("d0/f0");
        try {
            for (int i = 0; i < waits.length; i++) {
                var started = new CountDownLatch(1);
                var workerId = new java.util.concurrent.atomic.AtomicLong();
                var cpuStart = new java.util.concurrent.atomic.AtomicLong();
                Future<?> poll = pool.submit(() -> {
                    workerId.set(Thread.currentThread().threadId()); cpuStart.set(MX.getCurrentThreadCpuTime()); started.countDown();
                    try { vfs.pollExternalChanges(); } catch (IOException e) { throw new UncheckedIOException(e); }
                });
                if (!started.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Poll worker did not start");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                // Synchronize on actual scanning work, independent of whether the scan owns stateLock.
                while (MX.getThreadCpuTime(workerId.get()) - cpuStart.get() < 5_000_000L
                        && !poll.isDone() && System.nanoTime() < deadline) Thread.onSpinWait();
                if (poll.isDone()) throw new IllegalStateException("Dataset too small to overlap reader and scan");
                long start = System.nanoTime(); sink = vfs.exists(file) ? 1 : 0;
                waits[i] = (System.nanoTime() - start) / 1e6; poll.get(5, TimeUnit.SECONDS);
            }
        } finally { pool.shutdownNow(); }
        Arrays.sort(waits);
        output.printf(Locale.ROOT, "{\"case\":\"directory.reader_blocked_by_poll\",\"nodes\":%d,\"file_bytes\":%d,\"median_ms\":%.6f,\"min_ms\":%.6f,\"max_ms\":%.6f}%n", count, bytes, waits[4], waits[0], waits[8]);
        System.out.printf(Locale.ROOT, "reader blocked by poll: median %.2f ms, max %.2f ms%n", waits[4], waits[8]);
    }

    static void statAndAllocationCases(Path workspace) throws Exception {
        Path root = workspace.resolve("stat"); Files.createDirectories(root);
        for (int bytes : new int[]{4096, 8 * 1048576, 64 * 1048576}) {
            byte[] content = new byte[bytes]; new Random(20261113).nextBytes(content);
            Path physical = root.resolve("file"); Files.write(physical, content);
            try (DirectoryVfs vfs = new DirectoryVfs("stat", root); InMemoryVfs memory = new InMemoryVfs("memory")) {
                VfsPath file = p("file"); memory.writeBytes(file, content);
                measure("nio.read_attributes", 1, bytes, 100, () -> Files.readAttributes(physical, BasicFileAttributes.class).size());
                measure("directory.stat", 1, bytes, bytes > 1048576 ? 1 : 100, () -> vfs.stat(file).size());
                measure("directory.read_bytes", 1, bytes, bytes > 1048576 ? 1 : 100, () -> vfs.readBytes(file).length);
                measure("memory.open_read_first_byte", 1, bytes, bytes > 1048576 ? 1 : 100, () -> { try (var in = memory.openRead(file)) { return in.read(); } });
                measure("directory.open_write", 1, bytes, bytes > 1048576 ? 1 : 5, () -> { try (var out = vfs.openWrite(file)) { out.write(content); } return content.length; });
            }
        }
        remove(root);
    }

    static void memoryCases() throws Exception {
        for (int count : new int[]{1000, 10000, 100000}) {
            try (InMemoryVfs memory = new InMemoryVfs("memory")) {
                memoryFiles(memory, count, 100); VfsPath leaf = p("d0/f0"), subtree = p("d0"); byte[] value = new byte[32];
                measure("memory.stat", count, 32, 10000, () -> memory.stat(leaf).size());
                measure("memory.list_small_subtree", count, 32, 20, () -> memory.list(subtree).size());
                measure("memory.walk_small_subtree", count, 32, 20, () -> memory.walk(subtree).size());
                measure("memory.write_32b", count, 32, 100, () -> { memory.writeBytes(leaf, value); return 1; });
                measure("memory.transaction_put_32b", count, 32, 5, () -> { try (var tx = memory.beginTransaction()) { return tx.putBytes(leaf, value).commit().changes().size(); } });
            }
        }
    }

    static void mountCases() throws Exception {
        for (int count : new int[]{1, 10, 100, 1000, 10000}) {
            try (MountTableVfs table = new MountTableVfs("table"); InMemoryVfs backend = new InMemoryVfs("backend")) {
                backend.writeString(p("file"), "x");
                for (int i = 0; i < count; i++) table.mount(p("m" + i), backend);
                VfsPath path = p("m" + (count - 1) + "/file");
                measure("mount.resolve", count, 0, count >= 1000 ? 100 : 1000, () -> table.resolveMount(path).orElseThrow().relativePath().segmentCount());
                if (count <= 1000) measure("mount.walk_one_file_per_mount", count, 1, 1, () -> table.walk(VfsPath.ROOT).size());
            }
        }
    }

    static void crossMoveCases() throws Exception {
        for (int count : new int[]{100, 1000, 10000}) {
            try (InMemoryVfs a = new InMemoryVfs("a"); InMemoryVfs b = new InMemoryVfs("b"); MountTableVfs table = new MountTableVfs("table")) {
                memoryFiles(a, count, 100); memoryFiles(b, count, 100);
                a.writeString(p("hot"), "x"); table.mount(p("a"), a); table.mount(p("b"), b);
                boolean[] atA = {true};
                measure("mount.move_one_byte_across_memory", count * 2, 1, 1, () -> {
                    table.move(p(atA[0] ? "a/hot" : "b/hot"), p(atA[0] ? "b/hot" : "a/hot")); atA[0] = !atA[0]; return 1;
                });
            }
        }
    }

    static void zipCases(Path workspace) throws Exception {
        byte[] content = new byte[32];
        for (int count : new int[]{1000, 10000, 50000}) {
            Path archive = workspace.resolve("pack-" + count + ".zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                for (int i = 0; i < count; i++) { zip.putNextEntry(new ZipEntry("d" + i / 100 + "/f" + i)); zip.write(content); zip.closeEntry(); }
            }
            measure("zip.construct_index", count, 32, 1, () -> { try (var zip = new ZipVfs("zip", archive)) { return zip.exists(p("d0/f0")) ? 1 : 0; } });
            try (var zip = new ZipVfs("zip", archive)) {
                VfsPath file = p("d0/f0"), dir = p("d0");
                measure("zip.stat", count, 32, 10000, () -> zip.stat(file).size());
                measure("zip.list_small_subtree", count, 32, 100, () -> zip.list(dir).size());
                measure("zip.walk_small_subtree", count, 32, 10, () -> zip.walk(dir).size());
            }
            Files.delete(archive);
        }
    }

    static void extraCases(Path workspace) throws Exception {
        Path root = workspace.resolve("batch"); diskFiles(root, 10000, 4096);
        try (var vfs = new DirectoryVfs("batch", root)) {
            List<VfsPath> edits = new ArrayList<>(); for (int i = 0; i < 100; i++) edits.add(p("edit" + i));
            byte[] value = new byte[1024];
            measure("directory.transaction_put_100x1k", 10000, 1024, 1, () -> {
                try (var tx = vfs.beginTransaction()) { for (var path : edits) tx.putBytes(path, value); return tx.commit().changes().size(); }
            });
        }
        remove(root);
        root = workspace.resolve("wrappers"); Files.createDirectories(root);
        int bytes = 64 * 1048576; byte[] content = new byte[bytes]; new Random(20261113).nextBytes(content);
        Files.write(root.resolve("file"), content);
        try (var dir = new DirectoryVfs("disk", root); var table = new MountTableVfs("table"); var memory = new InMemoryVfs("memory")) {
            table.mount(p("disk"), dir); memory.writeBytes(p("file"), content);
            VfsPath physical = p("file"), mounted = p("disk/file");
            measure("directory.is_regular_file", 1, bytes, 100, () -> dir.isRegularFile(physical) ? 1 : 0);
            measure("mount.directory_is_regular_file", 1, bytes, 1, () -> table.isRegularFile(mounted) ? 1 : 0);
            byte[] chunk = new byte[8192];
            measure("directory.open_write_8k_chunks", 1, bytes, 1, () -> { try (var out = dir.openWrite(physical)) { for (int i = 0; i < bytes; i += chunk.length) out.write(chunk); } return bytes; });
            measure("memory.open_write_8k_chunks", 1, bytes, 1, () -> { try (var out = memory.openWrite(physical)) { for (int i = 0; i < bytes; i += chunk.length) out.write(chunk); } return bytes; });
        }
        remove(root);
        try (var a = new InMemoryVfs("a"); var b = new InMemoryVfs("b"); var table = new MountTableVfs("table")) {
            memoryFiles(a, 1000, 1); memoryFiles(b, 1000, 1);
            a.writeString(p("hot"), "x"); table.mount(p("a"), a); table.mount(p("b"), b); boolean[] atA = {true};
            measure("mount.move_one_byte_many_directories", 2000, 1, 1, () -> {
                table.move(p(atA[0] ? "a/hot" : "b/hot"), p(atA[0] ? "b/hot" : "a/hot")); atA[0] = !atA[0]; return 1;
            });
        }
    }

    static void remove(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException { if (e != null) throw e; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        MX.setThreadAllocatedMemoryEnabled(true); MX.setThreadCpuTimeEnabled(true);
        output = new PrintWriter(new FileWriter(args[0]), true);
        output.printf("{\"environment\":true,\"java\":\"%s\",\"vm\":\"%s\",\"arch\":\"%s\",\"os\":\"%s\",\"heap_max_bytes\":%d,\"cache\":\"warm; no cache purge\",\"warmup_batches\":3,\"measurement_batches\":9}%n", System.getProperty("java.version"), System.getProperty("java.vm.name"), System.getProperty("os.arch"), System.getProperty("os.name"), Runtime.getRuntime().maxMemory());
        Path workspace = Files.createTempDirectory("kasuga-vfs-perf-");
        System.out.println("Temporary datasets: " + workspace);
        try {
            if (args.length > 1 && args[1].equals("zipReuse")) {
                Path archive = workspace.resolve("reused.zip");
                try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
                    for (int i = 0; i < 10000; i++) { out.putNextEntry(new ZipEntry("d" + i / 100 + "/f" + i)); out.write(1); out.closeEntry(); }
                }
                try (var file = new ZipFile(archive.toFile())) {
                    VfsPath target = p("d0/f0");
                    measure("zip.reuse_open_handle", 10000, 1, 1, () -> {
                        try (var zip = new ZipVfs("zip", file, false)) { return zip.exists(target) ? 1 : 0; }
                    });
                }
            }
            else if (args.length > 1 && args[1].equals("contention")) {
                Path root = workspace.resolve("contention"); diskFiles(root, 1000, 262144);
                try (var vfs = new DirectoryVfs("contention", root)) {
                    for (int i = 0; i < 3; i++) vfs.pollExternalChanges();
                    readerDuringPoll(vfs, 1000, 262144);
                }
            }
            else if (args.length > 1 && args[1].equals("extra")) extraCases(workspace);
            else { directoryCases(workspace); statAndAllocationCases(workspace); memoryCases(); mountCases(); crossMoveCases(); zipCases(workspace); }
        } finally { remove(workspace); output.close(); }
        System.out.println("Benchmark finished; temporary datasets removed.");
    }
}
