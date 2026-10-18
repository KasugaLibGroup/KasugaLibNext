package lib.kasuga.rendering.models.mc.source.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.client.loading.LoadingIndicator;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.mc.source.texture.bake.PbrBakeCoordinator;
import lib.kasuga.rendering.models.mc.source.texture.bake.PbrBakeProfile;
import lib.kasuga.rendering.models.mc.source.texture.bake.PbrBakeResult;
import lib.kasuga.rendering.models.uml.loaders.sources.Source;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.structure.Pair;
import lombok.NonNull;
import net.minecraft.Util;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

public class CombinedTextureManager extends KasugaTextureManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    protected final TextureAtlas normalMap;
    protected final TextureAtlas specularMap;
    private final Map<Object, SpriteUploader<?>[]> spriteUploaders;
    private final Map<Object, SpriteContents[]> caches;
    private final Map<Object, TextureAtlasSprite[]> loadedSprites;
    private final Map<Object, BakeRequest> bakeRequests;
    private final Function<ResourceLocation, SpriteContents>[] missingImages;
    private final DefaultSpriteSupplier[] defaultSprites;

    public interface DefaultSpriteSupplier {
        SpriteContents get(ResourceLocation rl, int width, int height);
    }

    public CombinedTextureManager(SourceType type, String name,
                                  TextureManager textureManager,
                                  ResourceLocation textureAtlasLocation,
                                  @Nullable Function<ResourceLocation, SpriteContents> missingImage,
                                  ResourceLocation normalMapLocation,
                                  @Nullable Function<ResourceLocation, SpriteContents> missingNormalMapImage,
                                  @NonNull DefaultSpriteSupplier defaultNormalSprite,
                                  ResourceLocation specularMapLocation,
                                  @Nullable Function<ResourceLocation, SpriteContents> missingSpecularMapImage,
                                  @NonNull DefaultSpriteSupplier defaultSpecularSprite) {
        super(type, name, textureManager, textureAtlasLocation, missingImage);
        this.normalMap = new TextureAtlas(normalMapLocation);
        this.specularMap = new TextureAtlas(specularMapLocation);
        this.spriteUploaders = new ConcurrentHashMap<>();
        this.caches = new ConcurrentHashMap<>();
        this.loadedSprites = new ConcurrentHashMap<>();
        this.bakeRequests = new ConcurrentHashMap<>();
        this.missingImages = new Function[]{missingImage, missingNormalMapImage, missingSpecularMapImage};
        this.defaultSprites = new DefaultSpriteSupplier[]{defaultNormalSprite, defaultSpecularSprite};
        textureManager.register(this.normalMap.location(), this.normalMap);
        textureManager.register(this.specularMap.location(), this.specularMap);
    }

    @Override
    public @NotNull CompletableFuture<Void> reload(PreparationBarrier preparationBarrier, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller preparationsProfiler, @NotNull ProfilerFiller reloadProfiler, @NotNull Executor backgroundExecutor, @NotNull Executor gameExecutor) {
        SpriteLoader[] loaders = new SpriteLoader[]{
                SpriteLoader.create(this.textureAtlas),
                SpriteLoader.create(this.normalMap),
                SpriteLoader.create(this.specularMap)
        };
        CompletableFuture<SpriteLoader.Preparations[]> preparations = preparePbrBakes(backgroundExecutor, gameExecutor)
                .thenCompose(ignored -> combinedLoadAndStitch(loaders, 0, backgroundExecutor));
        return preparations.thenCompose(param -> {
            List<CompletableFuture<SpriteLoader.Preparations>> l = new ArrayList<>(3);
            for (SpriteLoader.Preparations p : param) {
                l.add(p.waitForUpload());
            }
            return Util.sequence(l);
        }).thenCompose(preparationBarrier::wait)
                .thenAcceptAsync(param -> this.apply(param, reloadProfiler), gameExecutor)
                .thenAcceptAsync(any -> this.spriteUploaders.clear(), gameExecutor)
                .thenAcceptAsync(any -> this.caches.forEach((k, v) -> {
                    TextureAtlasSprite[] sprites = new TextureAtlasSprite[v.length];
                    for (int i = 0; i < v.length; i++) {
                        SpriteContents content = v[i];
                        Objects.requireNonNull(content);
                        for (TextureAtlasSprite sprite : getAtlas(i).getTextures().values()) {
                            // 图集按内容名（ResourceLocation）建 key；同名内容只能保留一个 sprite。
                            // 因此按名称匹配（而非对象恒等）：同名的重复标识符共享同一个 sprite。
                            if (sprite.contents().name().equals(content.name())) {
                                sprites[i] = sprite;
                                break;
                            }
                        }
                        if (sprites[i] == null) {
                            LOGGER.warn("Combined texture atlas {} has no sprite for content {} ({}x{}); "
                                            + "{} sprites stitched, name present={}",
                                    getAtlas(i).location(), content.name(), content.width(), content.height(),
                                    getAtlas(i).getTextures().size(),
                                    getAtlas(i).getTextures().containsKey(content.name()));
                            sprites[i] = getAtlas(i).getTextures().get(MissingTextureAtlasSprite.getLocation());
                        }
                    }
                    loadedSprites.put(k, sprites);
                })).thenAcceptAsync(any -> this.caches.clear(), gameExecutor)
                .thenAcceptAsync(any -> {
                    this.bakeRequests.clear();
                    PbrBakeCoordinator.getInstance().releaseMemoryResults();
                }, gameExecutor);
    }

    @Override
    @Deprecated
    protected CompletableFuture<SpriteLoader.Preparations> loadAndStitch(SpriteLoader loader, int mipLevel, Executor executor) {
        throw new UnsupportedOperationException("Use combinedLoadAndStitch instead for combined texture manager");
    }

    protected CompletableFuture<SpriteLoader.Preparations[]> combinedLoadAndStitch(SpriteLoader[] loaders, int mipLevel, Executor executor) {
        return CompletableFuture.supplyAsync(this::combinedList, executor)
                .thenCompose(list -> runCombinedSuppliers(list, executor))
                .thenApply(param -> {
                    List<SpriteContents> textures = new ArrayList<>();
                    List<SpriteContents> normalMaps = new ArrayList<>();
                    List<SpriteContents> mrMaps = new ArrayList<>();
                    for (SpriteContents[] contents : param) {
                        textures.add(contents[0]);
                        normalMaps.add(contents[1]);
                        mrMaps.add(contents[2]);
                    }
                    SpriteLoader.Preparations[] preparations = new SpriteLoader.Preparations[3];
                    preparations[0] = loaders[0].stitch(textures, mipLevel, executor);
                    preparations[1] = loaders[1].stitch(normalMaps, mipLevel, executor);
                    preparations[2] = loaders[2].stitch(mrMaps, mipLevel, executor);
                    return preparations;
                });
    }

    @Override
    @Deprecated
    protected List<Supplier<Pair<Object, SpriteContents>>> list() {
        throw new UnsupportedOperationException("Use combinedList instead for combined texture manager");
    }

    protected List<Supplier<Pair<Object, SpriteContents[]>>> combinedList() {
        ResourceLocation missingNo = MissingTextureAtlasSprite.getLocation();
        List<Supplier<Pair<Object, SpriteContents[]>>> combined = new ArrayList<>();
        combined.add(() -> Pair.of(missingNo, new SpriteContents[]{
                missingImages[0] != null ? missingImages[0].apply(missingNo) : MissingTextureAtlasSprite.create(),
                missingImages[1] != null ? missingImages[1].apply(missingNo) : MissingTextureAtlasSprite.create(),
                missingImages[2] != null ? missingImages[2].apply(missingNo) : MissingTextureAtlasSprite.create()
        }));
        combined.add(() -> Pair.of(RenderState.DEFAULT_TRANSPARENCY, new SpriteContents[] {
                RenderState.createTransparencyDefaultSprite(),
                RenderState.createTransparencyDefaultSprite(),
                RenderState.createTransparencyDefaultSprite()
        }));
        for (SpriteUploader<?>[] uploader : spriteUploaders.values()) {
            combined.add(() -> {
                Object identifier = uploader[0].getResourceIdentifier();
                Objects.requireNonNull(uploader[0]);
                SpriteContents content = uploader[0].loadSprite(null, null);
                Objects.requireNonNull(content);
                int width = content.width(), height = content.height();
                @Nullable SpriteContents normalContent = null, specularContent = null;
                BakeRequest bakeRequest = bakeRequests.get(identifier);
                PbrBakeResult baked = bakeRequest == null ? null : PbrBakeCoordinator.getInstance().getOrBake(
                        bakeRequest.source(), bakeRequest.profile()
                );
                if (baked != null) {
                    try {
                        normalContent = spriteFromImage(content.name(), baked.normalMap());
                        specularContent = spriteFromImage(content.name(), baked.specularMap());
                    } catch (RuntimeException exception) {
                        LOGGER.warn("Failed to decode baked PBR maps for {}; using defaults", identifier, exception);
                        normalContent = defaultSprites[0].get(content.name(), width, height);
                        specularContent = defaultSprites[1].get(content.name(), width, height);
                    }
                } else if (uploader[1] == null) {
                    normalContent = defaultSprites[0].get(content.name(), width, height);
                } else {
                    normalContent = uploader[1].loadSprite(null, null);
                    if (normalContent == null) {
                        normalContent = defaultSprites[0].get(content.name(), width, height);
                    }
                }
                if (baked != null) {
                    // Both maps were assigned together above.
                } else if (uploader[2] == null) {
                    specularContent = defaultSprites[1].get(content.name(), width, height);
                } else {
                    specularContent = uploader[2].loadSprite(null, null);
                    if (specularContent == null) {
                        specularContent = defaultSprites[1].get(content.name(), width, height);
                    }
                }
                Objects.requireNonNull(normalContent);
                Objects.requireNonNull(specularContent);
                if (content.width() != normalContent.width() || content.height() != normalContent.height()) {
                    throw new IllegalStateException("Texture and normal map dimensions do not match for " + identifier);
                }

                if (content.width() != specularContent.width() || content.height() != specularContent.height()) {
                    throw new IllegalStateException("Texture and metallic-roughness map dimensions do not match for " + identifier);
                }
                return Pair.of(identifier, new SpriteContents[]{content, normalContent, specularContent});
            });
        }
        return combined;
    }

    public void requestPbrBake(Object identifier, BufferedImage source, PbrBakeProfile profile) {
        bakeRequests.compute(identifier, (ignored, request) -> {
            if (request == null) return new BakeRequest(identifier, source, profile);
            if (request.source() != source || !request.profile().equals(profile)) {
                throw new IllegalStateException(
                        "A texture identifier cannot use multiple PBR sources or profiles: " + identifier
                );
            }
            return request;
        });
    }

    private CompletableFuture<Void> preparePbrBakes(Executor backgroundExecutor, Executor gameExecutor) {
        if (bakeRequests.isEmpty()) return CompletableFuture.completedFuture(null);
        List<BakeRequest> requests = List.copyOf(bakeRequests.values());
        long batchStart = System.nanoTime();
        PbrBakeCoordinator coordinator = PbrBakeCoordinator.getInstance();
        PbrBakeCoordinator.PbrBakeStats before = coordinator.stats();
        LoadingIndicator.label("Loading " + requests.size() + " PBR cache entries");
        List<CompletableFuture<BakeRequest>> cacheChecks = requests.stream()
                .map(request -> CompletableFuture.supplyAsync(
                        () -> coordinator.loadFromCache(request.source(), request.profile()) ? null : request,
                        backgroundExecutor
                ))
                .toList();
        Set<BufferedImage> uniqueSources = Collections.newSetFromMap(new IdentityHashMap<>());
        long variantPixels = 0L;
        for (BakeRequest request : requests) {
            uniqueSources.add(request.source());
            variantPixels += (long) request.source().getWidth() * request.source().getHeight();
        }
        long finalVariantPixels = variantPixels;
        CompletableFuture<?>[] checks = cacheChecks.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(checks).thenCompose(ignored -> {
            List<BakeRequest> misses = cacheChecks.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();
            if (misses.isEmpty()) {
                LoadingIndicator.label("Stitching cached PBR atlases");
                return CompletableFuture.completedFuture(null);
            }

            LoadingIndicator.label("GPU baking " + misses.size() + " PBR textures");
            return CompletableFuture.runAsync(() -> {
                boolean traceGpuBakes = Boolean.getBoolean("kasuga.pbr.traceGpuBake");
                for (int index = 0; index < misses.size(); index++) {
                    BakeRequest request = misses.get(index);
                    if (traceGpuBakes) {
                        LOGGER.info("[pbr-gpu-trace] begin {}/{} identifier={} size={}x{} profile={}",
                                index + 1, misses.size(), traceIdentifier(request.identifier()),
                                request.source().getWidth(), request.source().getHeight(),
                                request.profile().cacheDescriptor());
                    }
                    coordinator.getOrBakeGpu(request.source(), request.profile());
                    if (traceGpuBakes) {
                        LOGGER.info("[pbr-gpu-trace] complete {}/{} identifier={}",
                                index + 1, misses.size(), traceIdentifier(request.identifier()));
                    }
                }
            }, gameExecutor);
        }).thenRun(() -> {
            PbrBakeCoordinator.PbrBakeStats after = coordinator.stats();
            long elapsedNanos = System.nanoTime() - batchStart;
            LOGGER.info("Prepared {} stylized PBR variants from {} source textures ({}, {}): "
                            + "GPU {}, CPU {}, disk hits {}, failures {}, elapsed {}",
                    requests.size(), uniqueSources.size(), formatPixels(finalVariantPixels),
                    requests.size() - uniqueSources.size() + " shared-source variants",
                    after.gpuBakes() - before.gpuBakes(),
                    after.cpuBakes() - before.cpuBakes(),
                    after.cacheHits() - before.cacheHits(),
                    after.failures() - before.failures(), formatMillis(elapsedNanos));
        });
    }

    private static SpriteContents spriteFromImage(ResourceLocation name, BufferedImage image) {
        NativeImage nativeImage = new NativeImage(image.getWidth(), image.getHeight(), false);
        try {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    int abgr = (argb & 0xff00ff00)
                            | (argb >>> 16 & 0xff)
                            | (argb & 0xff) << 16;
                    nativeImage.setPixelRGBA(x, y, abgr);
                }
            }
            return new SpriteContents(
                    name,
                    new FrameSize(nativeImage.getWidth(), nativeImage.getHeight()),
                    nativeImage,
                    ResourceMetadata.EMPTY
            );
        } catch (RuntimeException exception) {
            nativeImage.close();
            throw exception;
        }
    }

    private static String formatPixels(long pixels) {
        return String.format(Locale.ROOT, "%.1f MP", pixels / 1_000_000.0);
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.1f ms", nanos / 1_000_000.0);
    }

    private static Object traceIdentifier(Object identifier) {
        if (identifier instanceof Pair<?, ?> pair) return pair.getFirst();
        return identifier;
    }

    private record BakeRequest(Object identifier, BufferedImage source, PbrBakeProfile profile) {}

    @Override
    @Deprecated
    public CompletableFuture<List<SpriteContents>> runSpriteSuppliers(List<Supplier<Pair<Object, SpriteContents>>> factories, Executor executor) {
        throw new UnsupportedOperationException("Use runCombinedSuppliers instead for combined texture manager");
    }

    public CompletableFuture<List<SpriteContents[]>> runCombinedSuppliers(List<Supplier<Pair<Object, SpriteContents[]>>> factories, Executor executor) {
        List<CompletableFuture<SpriteContents[]>> list = factories.stream().map(
                element -> CompletableFuture.supplyAsync(
                        () -> {
                            Pair<Object, SpriteContents[]> pair = element.get();
                            Object identifier = pair.getFirst();
                            SpriteContents[] contents = pair.getSecond();
                            caches.put(identifier, contents);
                            return contents;
                        }, executor
                )
        ).toList();
        return Util.sequence(list).thenApply(param -> param.stream().filter(Objects::nonNull).toList());
    }

    protected void apply(List<SpriteLoader.Preparations> preparations, ProfilerFiller profiler) {
        profiler.startTick();
        profiler.push("upload");
        for (int i = 0; i < 3; i++) {this.getAtlas(i).upload(preparations.get(i));}
        profiler.pop();
        profiler.endTick();
    }

    @Override
    @Deprecated
    protected void apply(SpriteLoader.Preparations preparations, ProfilerFiller profiler) {
        throw new UnsupportedOperationException("Use apply(List<SpriteLoader.Preparations>) instead for combined texture manager");
    }

    @Override
    public Optional<InputStream> load(@NonNull Object sourceIdentifier) {
        return this.load(sourceIdentifier, null, null);
    }


    public Optional<InputStream> load(@NonNull Object sourceIdentifier,
                                      @Nullable Object normalMapIdentifier,
                                      @Nullable Object specularMapIdentifier) {
        if (this.spriteUploaders.containsKey(sourceIdentifier)) {
            return Optional.empty();
        }
        for (Source<?, ?> source : getSources().values()) {
            if (source.isValidInput(sourceIdentifier)) {
                this.spriteUploaders.put(sourceIdentifier, new SpriteUploader[]{
                        new SpriteUploader(this, (TextureSource) source, sourceIdentifier),
                        normalMapIdentifier != null ? new SpriteUploader<>(this,(TextureSource) source, normalMapIdentifier) : null,
                        specularMapIdentifier != null ? new SpriteUploader<>(this,(TextureSource) source, specularMapIdentifier) : null
                });
                return Optional.empty();
            }
        }
        throw new IllegalArgumentException("No source found for identifier: " + sourceIdentifier);
    }

    @Override
    public TextureAtlasSprite get(Object identifier) {
        TextureAtlasSprite[] sprites = loadedSprites.get(identifier);
        if (sprites == null) return null;
        return sprites[0];
    }

    @Override
    public @NonNull TextureAtlas getTextureAtlas() {
        return super.getTextureAtlas();
    }

    public @NonNull TextureAtlas getNormalMap() {
        return normalMap;
    }

    public @NonNull TextureAtlas getSpecularMap() {
        return specularMap;
    }

    public @NonNull TextureAtlas getAtlas(int index) {
        return switch (index) {
            case 0 -> getTextureAtlas();
            case 1 -> getNormalMap();
            case 2 -> getSpecularMap();
            default -> throw new IllegalArgumentException("Invalid atlas index: " + index);
        };
    }

    public void dumpAllContents(Path destDir) {
        if (!destDir.toFile().exists()) {
            destDir.toFile().mkdirs();
        }
        for (int i = 0; i < 3; i++) {
            TextureAtlas atlas = getAtlas(i);
            String suffix = switch (i) {
                case 0 -> "texture";
                case 1 -> "normal";
                case 2 -> "specular";
                default -> throw new IllegalArgumentException("Invalid atlas index: " + i);
            };
            Path path = destDir.resolve(suffix);
            if (!path.toFile().exists()) {
                path.toFile().mkdirs();
            }
            try {
                atlas.dumpContents(ResourceLocation.tryParse("texture"), path);
            } catch (Exception e) {
                System.err.println("Failed to dump " + suffix + " atlas: " + e.getMessage());
            }
        }
    }
}
