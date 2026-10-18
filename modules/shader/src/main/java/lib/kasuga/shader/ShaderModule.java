package lib.kasuga.shader;

import java.util.List;

public record ShaderModule(
        List<ShaderStructType> structs,
        List<ShaderGlobal> globals,
        List<String> rawPreamble,
        List<String> rawDeclarations,
        ShaderIr.Block entryPoint
) {
    public ShaderModule {
        structs = List.copyOf(structs);
        globals = List.copyOf(globals);
        rawPreamble = List.copyOf(rawPreamble);
        rawDeclarations = List.copyOf(rawDeclarations);
    }

    public ShaderModule(List<ShaderGlobal> globals, ShaderIr.Block entryPoint) {
        this(List.of(), globals, List.of(), List.of(), entryPoint);
    }
}
