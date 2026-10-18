package lib.kasuga.core.rendering;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.regex.Pattern;

/** Shared transform-shader import processing, independent of Minecraft and mixins. */
public final class ShaderFunctionImport {
    private static final Pattern DECLARATION = Pattern.compile("^\\s*(?:in|out|uniform)\\b.*");
    private static final Pattern ENTRY = Pattern.compile("^\\s*void\\s+main\\s*\\(.*");
    private ShaderFunctionImport() {}

    /** The caller owns the reader. Interface declarations and the standalone entry point are omitted. */
    public static String read(Reader source) throws IOException {
        BufferedReader reader = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        StringBuilder result = new StringBuilder();
        for (String line; (line = reader.readLine()) != null;) {
            if (DECLARATION.matcher(line).matches()) continue;
            if (ENTRY.matcher(line).matches()) break;
            result.append(line).append('\n');
        }
        return result.toString();
    }
}
