package lib.kasuga.shader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Ordered case builder used by {@link ShaderStageBuilder#switchOn}. */
public final class ShaderSwitchBuilder {
    private final Function<Runnable, ShaderIr.Block> capture;
    private final List<ShaderIr.SwitchCase> cases = new ArrayList<>();
    private final Set<Integer> labels = new HashSet<>();
    private ShaderIr.Block defaultBlock;
    private boolean closed;

    ShaderSwitchBuilder(Function<Runnable, ShaderIr.Block> capture) {
        this.capture = Objects.requireNonNull(capture, "capture");
    }

    /** Cases preserve declaration order and do not insert an implicit break. */
    public ShaderSwitchBuilder caseOf(int label, Runnable body) {
        ensureOpen();
        if (!labels.add(label)) {
            throw new IllegalArgumentException("Duplicate shader switch case: " + label);
        }
        cases.add(new ShaderIr.SwitchCase(label, capture.apply(Objects.requireNonNull(body, "body"))));
        return this;
    }

    public ShaderSwitchBuilder defaultCase(Runnable body) {
        ensureOpen();
        if (defaultBlock != null) throw new IllegalStateException("Shader switch already has a default case");
        defaultBlock = capture.apply(Objects.requireNonNull(body, "body"));
        return this;
    }

    ShaderIr.SwitchStatement finish(ShaderIr.Expression selector) {
        ensureOpen();
        closed = true;
        if (cases.isEmpty() && defaultBlock == null) {
            throw new IllegalStateException("Shader switch must declare at least one case");
        }
        return new ShaderIr.SwitchStatement(selector, cases, defaultBlock);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Shader switch builder is closed");
    }
}
