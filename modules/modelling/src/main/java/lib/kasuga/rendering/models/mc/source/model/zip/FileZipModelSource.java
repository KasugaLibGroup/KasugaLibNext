package lib.kasuga.rendering.models.mc.source.model.zip;

import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

public class FileZipModelSource extends ZipModelSource<Path> {

    public FileZipModelSource(String name) {
        super(name);
    }

    @Override
    public Optional<ZipHelper> getInput(Path input) {
        File file = input.toFile();
        if (!file.exists() || !file.isFile()) return Optional.empty();
        try {
            ZipHelper helper = ZipHelper.fromFile(file.getPath());
            return Optional.of(helper);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Class<Path> getInputType() {
        return Path.class;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof Path;
    }
}
