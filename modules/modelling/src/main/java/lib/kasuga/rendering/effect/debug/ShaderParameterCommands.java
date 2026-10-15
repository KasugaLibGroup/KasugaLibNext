package lib.kasuga.rendering.effect.debug;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import lib.kasuga.rendering.effect.shader.RenderShaderRegistry;
import lib.kasuga.rendering.effect.shader.ShaderParameterBlock;
import lib.kasuga.shader.ShaderParameter;
import lib.kasuga.shader.ShaderParameterType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.Optional;

/** Generic client commands for inspecting and changing parameters exposed by any shader. */
public final class ShaderParameterCommands {
    private ShaderParameterCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("parameter")
                .then(Commands.literal("list")
                        .then(Commands.argument("shader", StringArgumentType.word())
                                .executes(ShaderParameterCommands::list)))
                .then(Commands.literal("set")
                        .then(Commands.argument("shader", StringArgumentType.word())
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("values", StringArgumentType.greedyString())
                                                .executes(ShaderParameterCommands::set)))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("shader", StringArgumentType.word())
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ShaderParameterCommands::reset))));
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        RenderShaderHandle handle = handle(context);
        if (handle == null) return 0;
        if (handle.parameters().schema().isEmpty()) {
            context.getSource().sendFailure(Component.literal("Shader exposes no user parameters"));
            return 0;
        }
        for (ShaderParameter parameter : handle.parameters().schema().parameters()) {
            double[] values = handle.parameters().values(parameter.name());
            context.getSource().sendSuccess(() -> Component.literal(
                    parameter.name() + "=" + format(values)
                            + "  type=" + parameter.type().name().toLowerCase()
                            + "  range=[" + parameter.range().minimum() + ", "
                            + parameter.range().maximum() + "]  " + parameter.description()
            ), false);
        }
        return handle.parameters().schema().size();
    }

    private static int set(CommandContext<CommandSourceStack> context) {
        RenderShaderHandle handle = handle(context);
        if (handle == null) return 0;
        String name = StringArgumentType.getString(context, "name");
        ShaderParameter parameter;
        try {
            parameter = handle.parameters().schema().require(name);
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        String source = StringArgumentType.getString(context, "values").trim();
        try {
            double[] values = parseValues(parameter.type(), source);
            handle.parameters().set(parameter, values);
            context.getSource().sendSuccess(() -> Component.literal(
                    "Set " + handle.id() + " " + name + "="
                            + format(handle.parameters().values(name))
            ), false);
            return 1;
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        RenderShaderHandle handle = handle(context);
        if (handle == null) return 0;
        String name = StringArgumentType.getString(context, "name");
        try {
            handle.parameters().reset(name);
            context.getSource().sendSuccess(() -> Component.literal(
                    "Reset " + handle.id() + " " + name + "="
                            + format(handle.parameters().values(name))
            ), false);
            return 1;
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static RenderShaderHandle handle(CommandContext<CommandSourceStack> context) {
        String value = StringArgumentType.getString(context, "shader");
        ResourceLocation id = ResourceLocation.tryParse(value);
        Optional<RenderShaderHandle> handle = id == null ? Optional.empty() : RenderShaderRegistry.get(id);
        if (handle.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Unknown shader: " + value));
            return null;
        }
        return handle.get();
    }

    private static double[] parseValues(ShaderParameterType type, String source) {
        if (type == ShaderParameterType.BOOLEAN) {
            if (source.equalsIgnoreCase("true")) return new double[]{1};
            if (source.equalsIgnoreCase("false")) return new double[]{0};
        }
        if (source.isBlank()) throw new IllegalArgumentException("Parameter values cannot be blank");
        String[] parts = source.split("[\\s,]+");
        double[] values = new double[parts.length];
        try {
            for (int index = 0; index < parts.length; index++) values[index] = Double.parseDouble(parts[index]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Parameter values must be numbers", exception);
        }
        return values;
    }

    private static String format(double[] values) {
        if (values.length == 1) return Double.toString(values[0]);
        return Arrays.toString(values);
    }
}
