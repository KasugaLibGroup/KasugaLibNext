package lib.kasuga.rendering.models.mc.source.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.compat.iris.KasugaTextureAtlas;
import lib.kasuga.rendering.models.uml.loaders.sources.Source;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.NonNull;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

@Getter
public class KasugaTextureManager extends SourceManager<InputStream> implements PreparableReloadListener, AutoCloseable {

    @NotNull
    protected final KasugaTextureAtlas textureAtlas;
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<Object, SpriteUploader<?>> spriteUploaders;
    private final Map<Object, SpriteContents> caches;
    private final Map<Object, TextureAtlasSprite> loadedSprites;
    private final Function<ResourceLocation, SpriteContents> missingImage;


    public KasugaTextureManager(SourceType type, String name,
                                TextureManager textureManager,
                                ResourceLocation textureAtlasLocation,
                                @Nullable Function<ResourceLocation, SpriteContents> missingImage) {
        super(type, name);
        this.textureAtlas = new KasugaTextureAtlas(textureAtlasLocation);
        this.spriteUploaders = new HashMap<>();
        this.loadedSprites = new HashMap<>();
        this.caches = new HashMap<>();
        textureManager.register(this.textureAtlas.location(), this.textureAtlas);
        this.missingImage = missingImage != null ? missingImage : ignored -> MissingTextureAtlasSprite.create();
    }

    @Override
    public void close() throws Exception {}

    @Override
    public @NotNull CompletableFuture<Void> reload(PreparationBarrier preparationBarrier,
                                                   @NotNull ResourceManager resourceManager,
                                                   @NotNull ProfilerFiller preparationsProfiler,
                                                   @NotNull ProfilerFiller reloadProfiler,
                                                   @NotNull Executor backgroundExecutor,
                                                   @NotNull Executor gameExecutor) {
        SpriteLoader loader = SpriteLoader.create(this.textureAtlas);
        CompletableFuture<SpriteLoader.Preparations> preparation = loadAndStitch(
                loader, 0, backgroundExecutor
        );
        return preparation.thenCompose(SpriteLoader.Preparations::waitForUpload)
                .thenCompose(preparationBarrier::wait)
                .thenAcceptAsync(param -> this.apply(param, reloadProfiler), gameExecutor)
                .thenAcceptAsync(any -> this.spriteUploaders.clear(), gameExecutor)
                .thenAcceptAsync(any -> this.caches.forEach((k, v) -> {
                    for (Map.Entry<ResourceLocation, TextureAtlasSprite> entry : this.textureAtlas.getTextures().entrySet()) {
                        // 图集按内容名建 key：同名重复内容只能保留一个 sprite，故按名称匹配而非恒等。
                        if (entry.getValue().contents().name().equals(v.name())) {
                            this.loadedSprites.put(k, entry.getValue());
                            break;
                        }
                    }
                    if (!this.loadedSprites.containsKey(k)) {
                        LOGGER.warn("Texture atlas {} has no sprite for content {}", this.textureAtlas.location(), v.name());
                    }
                }), gameExecutor)
                .thenAcceptAsync(any -> this.caches.clear(), gameExecutor);
    }

    protected void apply(SpriteLoader.Preparations preparations, ProfilerFiller profiler) {
        profiler.startTick();
        profiler.push("upload");
        this.textureAtlas.upload(preparations);
        profiler.pop();
        profiler.endTick();
    }

    protected CompletableFuture<SpriteLoader.Preparations> loadAndStitch(SpriteLoader loader, int mipLevel, Executor executor) {
        return CompletableFuture.supplyAsync(this::list, executor)
                .thenCompose(list -> runSpriteSuppliers(list, executor))
                .thenApply((param) -> loader.stitch(param, mipLevel, executor));
    }

    protected List<Supplier<Pair<Object, SpriteContents>>> list() {
        ResourceLocation missingNo = MissingTextureAtlasSprite.getLocation();
        List<Supplier<Pair<Object, SpriteContents>>> suppliers = new ArrayList<>();
        suppliers.add(() -> Pair.of(missingNo, missingImage.apply(missingNo)));
        for (SpriteUploader<?> uploader : spriteUploaders.values()) {
            suppliers.add(() -> Pair.of(
                    uploader.getResourceIdentifier(),
                    uploader.loadSprite(null, null)
            ));
        }
        return suppliers;
    }

    public CompletableFuture<List<SpriteContents>> runSpriteSuppliers(List<Supplier<Pair<Object, SpriteContents>>> factories,  Executor executor) {
        List<CompletableFuture<SpriteContents>> list = factories.stream().map((element) -> CompletableFuture.supplyAsync(() -> {
            Pair<Object, SpriteContents> pair = element.get();
            SpriteContents contents = pair.getSecond();
            this.caches.put(pair.getFirst(), contents);
            return contents;
        }, executor)).toList();
        return Util.sequence(list).thenApply((p_252234_) -> p_252234_.stream().filter(Objects::nonNull).toList());
    }


    @Override
    public @NotNull String getName() {
        return super.getName();
    }

    @Override
    public Optional<InputStream> load(Object sourceIdentifier) {
        if (this.spriteUploaders.containsKey(sourceIdentifier)) {
            return Optional.empty();
        }
        for (Source<?, ?> source : getSources().values()) {
            if (source.isValidInput(sourceIdentifier)) {
                this.spriteUploaders.put(sourceIdentifier, new SpriteUploader(this, (TextureSource) source, sourceIdentifier));
                return Optional.empty();
            }
        }
        throw new IllegalArgumentException("No source found for identifier: " + sourceIdentifier);
    }

    public TextureAtlasSprite get(Object identifier) {
        return loadedSprites.get(identifier);
    }

    public @NonNull TextureAtlas getTextureAtlas() {
        return this.textureAtlas;
    }
}
