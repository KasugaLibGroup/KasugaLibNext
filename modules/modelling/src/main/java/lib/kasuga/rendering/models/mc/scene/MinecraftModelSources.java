package lib.kasuga.rendering.models.mc.scene;

import lib.kasuga.rendering.models.mc.backend.UmlPreviewSource;
import lib.kasuga.rendering.models.uml.structure.Model;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Unified construction entry point; Create-specific factories are in the optional create module. */
public final class MinecraftModelSources {
    public static final int MAX_BLOCKS = 131072;
    public static final int MAX_LAYERS = 4096;
    public static final int MAX_SPAN = 4096;
    private MinecraftModelSources() {}

    public static BlockModelSource blocks(Level host, List<StructureBlockInfo> blocks) { return new BlockModelSource(host, blocks); }
    /** Auto-detects installed blueprint formats; independent of extension and preview toolkit. */
    public static BlockModelSource blueprint(Level host, Path file) throws IOException { return BlueprintImporters.load(host,file); }
    public static BlockModelSource structure(Level host, StructureTemplate structure) {
        return blocks(host, SchematicModelData.structure(structure, host.holderLookup(Registries.BLOCK)));
    }
    public static BlockModelSource structure(Level host, Path file) throws IOException {
        return blocks(host, SchematicModelData.readStructure(file, host.holderLookup(Registries.BLOCK)));
    }
    /** Reads bundled/resource-pack buildings, e.g. minecraft:end_city/base_floor. */
    public static BlockModelSource structure(Level host, ResourceLocation structure) throws IOException {
        var location = ResourceLocation.fromNamespaceAndPath(structure.getNamespace(), "structure/" + structure.getPath() + ".nbt");
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.server.packs.resources.IoSupplier<java.io.InputStream> input = null;
        var server = minecraft.getSingleplayerServer();
        if (server != null) {
            var resource = server.getResourceManager().getResource(location);
            if (resource.isPresent()) input = resource.get()::open;
        }
        try (var vanilla = net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource()) {
            if (input == null) input = vanilla.getResource(net.minecraft.server.packs.PackType.SERVER_DATA, location);
            if (input == null) throw new IOException("Structure resource does not exist: " + structure);
            try (var stream = input.get()) {
                return blocks(host, SchematicModelData.structure(net.minecraft.nbt.NbtIo.readCompressed(stream,
                        net.minecraft.nbt.NbtAccounter.create(SchematicModelData.MAX_NBT_BYTES)), host.holderLookup(Registries.BLOCK), 0));
            }
        }
    }
    public static BlockModelSource litematic(Level host, Path file) throws IOException {
        return blocks(host, SchematicModelData.readLitematic(file, host.holderLookup(Registries.BLOCK)));
    }
    /** Snapshot a machine/region already present on the client; no chunks are loaded on its behalf. */
    public static BlockModelSource capture(Level host, BlockPos min, BlockPos max) {
        long dx = (long) max.getX() - min.getX() + 1, dy = (long) max.getY() - min.getY() + 1, dz = (long) max.getZ() - min.getZ() + 1;
        if (dx <= 0 || dy <= 0 || dz <= 0 || dx > MAX_SPAN || dy > MAX_SPAN || dz > MAX_SPAN || dx * dy * dz > MAX_BLOCKS)
            throw new IllegalArgumentException("Invalid capture area");
        List<StructureBlockInfo> result = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!host.hasChunkAt(pos)) throw new IllegalArgumentException("Capture includes an unloaded chunk");
            var entity = host.getBlockEntity(pos);
            result.add(new StructureBlockInfo(pos.immutable(), host.getBlockState(pos),
                    entity == null ? null : entity.saveWithFullMetadata(host.registryAccess())));
        }
        return new BlockModelSource(host, result, min);
    }

    /** Owns a private instance/GPU allocation, using the same UML rendering path as GuiModelPreview. */
    public static UmlPreviewSource model(Supplier<Model> source) { return new UmlPreviewSource(source); }

    public record Placement(MinecraftModelSource source, Matrix4f transform) {
        public Placement {
            Objects.requireNonNull(source);
            transform = new Matrix4f(transform);
            if (!transform.isFinite() || !transform.isAffine() || transform.determinant() == 0)
                throw new IllegalArgumentException("Model placement must be finite, affine and invertible");
        }
        public Placement(MinecraftModelSource source, Matrix4fc transform) { this(source, new Matrix4f(transform)); }
        @Override public Matrix4f transform() { return new Matrix4f(transform); }
    }

    /** Combines native and UML geometry into one depth-tested preview; takes ownership of child sources. */
    public static MinecraftModelSource compose(Placement... placements) {
        List<Placement> children = List.of(placements);
        var seen = new IdentityHashMap<MinecraftModelSource, Boolean>();
        for (var child : children) if (seen.put(child.source(), true) != null)
            throw new IllegalArgumentException("A composed source cannot own the same child twice");
        return new MinecraftModelSource() {
            private boolean closed;
            @Override public Snapshot snapshot(LayerLayout layout, float partialTick) {
                if (closed) return null;
                List<Part> parts = new ArrayList<>();
                ModelBounds bounds = null;
                for (int i = 0; i < children.size(); i++) {
                    Placement child = children.get(i);
                    Snapshot frame = child.source().snapshot(layout, partialTick);
                    if (frame == null) continue;
                    ModelBounds b = frame.bounds().transform(child.transform);
                    bounds = bounds == null ? b : bounds.union(b);
                    for (Part part : frame.parts()) {
                        parts.add(new Part(i + "/" + part.id(), part.layer(), part.bounds().transform(child.transform), ctx -> {
                            ctx.pose().pushPose();
                            try { ctx.pose().mulPose(child.transform); part.renderer().render(ctx); }
                            finally { ctx.pose().popPose(); }
                        }, MinecraftModelSource.placedPicker(child.transform, part.picker())));
                    }
                }
                return bounds == null ? null : new Snapshot(bounds, parts);
            }
            @Override public void close() {
                if (closed) return;
                closed = true;
                RuntimeException failure = null;
                for (Placement child : children) try { child.source().close(); }
                catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
                if (failure != null) throw failure;
            }
        };
    }
}
