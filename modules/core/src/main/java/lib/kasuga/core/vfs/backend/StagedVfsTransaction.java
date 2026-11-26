package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VfsPreparedTransaction;
import lib.kasuga.core.vfs.VfsWriteTransaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Queues writes for backends that validate and apply their transactions separately. */
abstract class StagedVfsTransaction implements VfsWriteTransaction {
    final List<StagedOp> ops = new ArrayList<>();
    VfsPreparedTransaction reservation;
    boolean committed;
    boolean rolledBack;

    @Override
    public final VfsWriteTransaction putBytes(VfsPath path, byte[] content) {
        ensureActive();
        ops.add(new StagedOp.PutBytes(Objects.requireNonNull(path, "path"), Arrays.copyOf(content, content.length)));
        return this;
    }

    @Override
    public final VfsWriteTransaction putStream(VfsPath path, ContentSource source) {
        ensureActive();
        ops.add(new StagedOp.PutStream(Objects.requireNonNull(path), Objects.requireNonNull(source)));
        return this;
    }

    @Override
    public final VfsWriteTransaction mkdirs(VfsPath dir) {
        ensureActive();
        ops.add(new StagedOp.Mkdirs(Objects.requireNonNull(dir, "dir")));
        return this;
    }

    @Override
    public final VfsWriteTransaction delete(VfsPath path) {
        ensureActive();
        ops.add(new StagedOp.Delete(Objects.requireNonNull(path, "path")));
        return this;
    }

    @Override
    public final VfsWriteTransaction move(VfsPath from, VfsPath to) {
        ensureActive();
        ops.add(new StagedOp.Move(Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to")));
        return this;
    }

    @Override
    public final void close() {
        rollback();
    }

    final void ensureActive() {
        if (committed || rolledBack || reservation != null) {
            throw new IllegalStateException("Transaction already finished");
        }
    }

    final List<VfsPath> affectedPaths() {
        List<VfsPath> paths = new ArrayList<>();
        for (StagedOp op : ops) {
            paths.add(op.path());
            if (op instanceof StagedOp.Move move) paths.add(move.to());
        }
        return paths;
    }

    sealed interface StagedOp {
        VfsPath path();

        record PutBytes(VfsPath path, byte[] bytes) implements StagedOp {}
        record PutStream(VfsPath path, ContentSource source) implements StagedOp {}
        record Mkdirs(VfsPath path) implements StagedOp {}
        record Delete(VfsPath path) implements StagedOp {}
        record Move(VfsPath from, VfsPath to) implements StagedOp {
            @Override
            public VfsPath path() { return from; }
        }
    }
}
