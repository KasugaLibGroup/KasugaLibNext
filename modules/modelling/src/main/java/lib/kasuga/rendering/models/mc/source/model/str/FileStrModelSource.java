package lib.kasuga.rendering.models.mc.source.model.str;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Scanner;

public class FileStrModelSource extends StringModelSource<Path> {

    public FileStrModelSource(String name) {
        super(name);
    }

    @Override
    public Optional<String> getInput(Path input) {
        File file = input.toFile();
        if (!file.exists() || !file.isFile()) return Optional.empty();
        try (Scanner scanner = new Scanner(file)) {
            StringBuilder builder = new StringBuilder();
            while (scanner.hasNextLine()) {
                builder.append(scanner.nextLine()).append('\n');
            }
            return Optional.of(builder.toString());
        } catch (IOException e) {
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
