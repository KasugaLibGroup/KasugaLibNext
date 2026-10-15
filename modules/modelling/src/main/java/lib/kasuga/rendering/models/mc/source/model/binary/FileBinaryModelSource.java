package lib.kasuga.rendering.models.mc.source.model.binary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class FileBinaryModelSource extends BinaryModelSource<Path> {
    public FileBinaryModelSource(String name) { super(name); }
    @Override public Optional<byte[]> getInput(Path input) {
        try { return Files.isRegularFile(input) ? Optional.of(Files.readAllBytes(input)) : Optional.empty(); }
        catch (Exception ignored) { return Optional.empty(); }
    }
    @Override public Class<Path> getInputType() { return Path.class; }
    @Override public boolean isValidInput(Object input) { return input instanceof Path; }
}
