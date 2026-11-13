package lib.kasuga.create.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.schematics.client.SchematicRenderer;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/** Our preview subclasses, linked to either public Create constructor ABI without changing upstream
 * classes. Hidden classes let the same mod binary support both 6.0.6 and 6.0.10 constructors.
 */
final class CreatePreviewFactories implements Opcodes {
    private static final String OWNER = Type.getInternalName(CreatePreviewFactories.class);
    private static final String WORLD = Type.getInternalName(VirtualRenderWorld.class);
    private static final String SCHEMATIC = Type.getInternalName(SchematicRenderer.class);
    private static final String SCHEMATIC_LEVEL = Type.getInternalName(SchematicLevel.class);
    private static final String BUFFER = Type.getDescriptor(SuperByteBuffer.class);
    private static final String LAYER = Type.getDescriptor(net.minecraft.client.renderer.RenderType.class);
    private static final Runnable NO_UPDATE = () -> {};
    private static final MethodHandle WORLD_FACTORY = worldFactory();
    private static final MethodHandle SCHEMATIC_FACTORY = schematicFactory();

    interface SchematicView extends AutoCloseable {
        void update();
        void render(PoseStack pose, SuperRenderTypeBuffer buffers);
        @Override void close();
    }

    private CreatePreviewFactories() {}

    static VirtualRenderWorld world(Level level, int minHeight, int height, Vec3i biomeOffset) {
        try { return (VirtualRenderWorld) WORLD_FACTORY.invokeExact(level, minHeight, height, biomeOffset); }
        catch (Throwable error) { throw propagate(error); }
    }

    static SchematicView schematic(SchematicLevel level) {
        try { return (SchematicView) SCHEMATIC_FACTORY.invokeExact(level); }
        catch (Throwable error) { throw propagate(error); }
    }

    private static MethodHandle worldFactory() {
        String name = OWNER + "$PreviewWorld";
        var writer = writer(name, WORLD);
        String args = "(" + Type.getDescriptor(Level.class) + "II" + Type.getDescriptor(Vec3i.class);
        var method = method(writer, ACC_PUBLIC, "<init>", args + ")V");
        method.visitVarInsn(ALOAD, 0); method.visitVarInsn(ALOAD, 1);
        method.visitVarInsn(ILOAD, 2); method.visitVarInsn(ILOAD, 3); method.visitVarInsn(ALOAD, 4);
        boolean callback = hasConstructor(VirtualRenderWorld.class, Level.class, int.class, int.class, Vec3i.class, Runnable.class);
        if (callback) method.visitFieldInsn(GETSTATIC, OWNER, "NO_UPDATE", "Ljava/lang/Runnable;");
        method.visitMethodInsn(INVOKESPECIAL, WORLD, "<init>", args + (callback ? "Ljava/lang/Runnable;" : "") + ")V", false);
        method.visitInsn(RETURN); end(method);
        method = method(writer, ACC_PUBLIC, "supportsVisualization", "()Z");
        method.visitInsn(ICONST_0); method.visitInsn(IRETURN); end(method);
        return define(writer, MethodType.methodType(void.class, Level.class, int.class, int.class, Vec3i.class))
                .asType(MethodType.methodType(VirtualRenderWorld.class, Level.class, int.class, int.class, Vec3i.class));
    }

    private static MethodHandle schematicFactory() {
        String name = OWNER + "$OwnedSchematic";
        var writer = writer(name, SCHEMATIC, Type.getInternalName(SchematicView.class));
        writer.visitField(ACC_PRIVATE | ACC_FINAL, "owned", "Ljava/util/List;", null, null).visitEnd();
        String ctor = "(L" + SCHEMATIC_LEVEL + ";)V";
        var method = method(writer, ACC_PUBLIC, "<init>", ctor);
        method.visitVarInsn(ALOAD, 0);
        boolean takesWorld = hasConstructor(SchematicRenderer.class, SchematicLevel.class);
        if (takesWorld) method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(INVOKESPECIAL, SCHEMATIC, "<init>", takesWorld ? ctor : "()V", false);
        if (!takesWorld) {
            method.visitVarInsn(ALOAD, 0); method.visitVarInsn(ALOAD, 1);
            method.visitMethodInsn(INVOKEVIRTUAL, SCHEMATIC, "display", ctor, false);
        }
        method.visitVarInsn(ALOAD, 0); method.visitTypeInsn(NEW, "java/util/ArrayList"); method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        method.visitFieldInsn(PUTFIELD, name, "owned", "Ljava/util/List;");
        method.visitInsn(RETURN); end(method);

        method = method(writer, ACC_PUBLIC, "close", "()V");
        deleteOwned(method, name); method.visitInsn(RETURN); end(method);
        method = method(writer, ACC_PROTECTED, "redraw", "()V");
        deleteOwned(method, name); method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, SCHEMATIC, "redraw", "()V", false);
        method.visitInsn(RETURN); end(method);

        method = method(writer, ACC_PROTECTED, "drawLayer", "(" + LAYER + ")" + BUFFER);
        var start = new Label(); var done = new Label(); var failed = new Label();
        method.visitTryCatchBlock(start, done, failed, null); method.visitLabel(start);
        method.visitVarInsn(ALOAD, 0); method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(INVOKESPECIAL, SCHEMATIC, "drawLayer", "(" + LAYER + ")" + BUFFER, false);
        method.visitVarInsn(ASTORE, 2);
        method.visitVarInsn(ALOAD, 0); method.visitFieldInsn(GETFIELD, name, "owned", "Ljava/util/List;");
        method.visitVarInsn(ALOAD, 2);
        method.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true); method.visitInsn(POP);
        method.visitLabel(done); afterLayer(method);
        method.visitVarInsn(ALOAD, 2); method.visitInsn(ARETURN);
        method.visitLabel(failed); method.visitVarInsn(ASTORE, 3); afterLayer(method);
        method.visitVarInsn(ALOAD, 3); method.visitInsn(ATHROW); end(method);
        return define(writer, MethodType.methodType(void.class, SchematicLevel.class))
                .asType(MethodType.methodType(SchematicView.class, SchematicLevel.class));
    }

    private static void deleteOwned(MethodVisitor method, String name) {
        method.visitVarInsn(ALOAD, 0); method.visitFieldInsn(GETFIELD, name, "owned", "Ljava/util/List;");
        method.visitMethodInsn(INVOKESTATIC, OWNER, "deleteBuffers", "(Ljava/util/List;)V", false);
    }
    private static void afterLayer(MethodVisitor method) {
        method.visitVarInsn(ALOAD, 0); method.visitFieldInsn(GETFIELD, SCHEMATIC, "schematic", "L" + SCHEMATIC_LEVEL + ";");
        method.visitMethodInsn(INVOKESTATIC, OWNER, "finishLayer", "(L" + SCHEMATIC_LEVEL + ";)V", false);
    }
    private static void deleteBuffers(List<SuperByteBuffer> buffers) {
        for (var buffer : buffers) buffer.delete();
        buffers.clear();
    }
    private static void finishLayer(SchematicLevel level) {
        net.minecraft.client.renderer.block.ModelBlockRenderer.clearCache(); level.renderMode = false;
    }
    private static boolean hasConstructor(Class<?> type, Class<?>... parameters) {
        try { type.getConstructor(parameters); return true; }
        catch (NoSuchMethodException absent) { return false; }
    }
    private static ClassWriter writer(String name, String parent, String... interfaces) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_FINAL | ACC_SUPER, name, null, parent, interfaces);
        return writer;
    }
    private static MethodVisitor method(ClassWriter writer, int access, String name, String signature) {
        var method = writer.visitMethod(access, name, signature, null, null); method.visitCode(); return method;
    }
    private static void end(MethodVisitor method) { method.visitMaxs(0, 0); method.visitEnd(); }
    private static MethodHandle define(ClassWriter writer, MethodType constructor) {
        writer.visitEnd();
        try {
            var lookup = MethodHandles.lookup().defineHiddenClass(writer.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
            return lookup.findConstructor(lookup.lookupClass(), constructor);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Could not link Create preview constructors", error); }
    }
    private static RuntimeException propagate(Throwable error) {
        if (error instanceof Error fatal) throw fatal;
        return error instanceof RuntimeException runtime ? runtime : new IllegalStateException("Create preview failed", error);
    }
}
