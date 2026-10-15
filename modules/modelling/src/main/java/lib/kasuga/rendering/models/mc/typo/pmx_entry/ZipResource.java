package lib.kasuga.rendering.models.mc.typo.pmx_entry;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.ZipEntry;

public record ZipResource(ZipHelper file, String name, ZipEntry entry, ByteBuffer buffer) {

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof ZipResource other)) return false;
        return Objects.equals(file, other.file) &&
                Objects.equals(name, other.name) &&
                Objects.equals(entry, other.entry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(file, name, entry);
    }

    @Override
    public @NotNull String toString() {
        return "ZipResource {" + file.getPath().toString() + "#" + name + "}";
    }
}
