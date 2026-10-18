# Wardrobe Example Verification Record and Performance Baseline

## This Implementation and Verification (2026-10-08)

This change provides real-model examples of texture swapping, clothing model swapping and a vanilla MC held
weapon, with every outfit wearing the user-supplied No. 13 hair. The public data consists of the generic `Model`, `Skeleton`, material instances and the assembly interface.

| Change | Effect |
| --- | --- |
| `WardrobeExample` | Connects user assets to the existing Reader and demonstrates material frame switching, model replacement, cache reuse and holding a sword at the right wrist |
| `KsgPmxLoader` | Atlas texture IDs are derived as a SHA-256 over the archive, the PMX entry and the texture entry, fixing texture mix-ups between identically named assets across archives |
| `KsgPmxTextureIdentityTest` | Covers different archives, different models/textures, Unicode names and old string-hash collisions |
| Iris vertex upload and MC render stack | Explicitly fills in intermediate UVs, tangents and entity attributes, supports worker threads and partial updates, and restores the PoseStack in a finally block when rendering fails |
| Shader smoke entry point | Optional real shader pack, verifying the active Iris pipeline; by default it only outputs pass/fail logs, with no screenshots and no performance sampling |
| `ModelAssemblyProbe` | Adds RibbonDress short-sleeve/long-sleeve benchmarks with hair, recording the JVM PID, Java version and heap limit |
| Local asset preparation script | Turns extracted assets into a local resource pack, can append the enable setting and keeps the other resource packs |
| Performance summary script | Validates the reports of three separate JVMs and outputs Markdown only, generating no HTML and no extra summary JSON |
| Gradle and API documentation | Adds the opt-in example/automated acceptance properties, the run steps and the performance explanation |

The scope of this commit is the source, tests, scripts and API Markdown documentation listed above. Models, textures, resource packs, saves,
screenshots, HTML, raw JSON reports, logs and build artifacts are not committed.

The verification environment was macOS / aarch64, Apple M3 Max, Java 21.0.3, Minecraft 1.21.1, NeoForge 21.1.203.
The client loaded Iris 1.8.12 / Sodium 0.6.13. Earlier screenshots and performance observations used the disabled configuration;
separately, the six-stage smoke check with an enabled shader pack was run through with BSL 8.2.04. The verification scope is limited to that environment and that shader pack.

| Verification | Result |
| --- | --- |
| `renderingReleaseCheck` | passed |
| `modelUnitTest` | 811 cases, 0 failures, 0 errors, 3 conditionally skipped |
| `renderGlTest` | 13 groups passed; the 21,259 vertices of the real matching base body/long dress passed a CPU/GPU skinning comparison |
| Local PMX probe | three JVMs each loaded all 92 PMX files successfully, 0 load failures |
| Real client example with hair | `WARDROBE_EXAMPLE_PASS`, 180 frames, 61 vanilla item submissions |
| BSL 8.2.04 shader path | `WARDROBE_SHADER_SMOKE_PASS`, all six stages completed, actually entering IrisRenderingPipeline with no fallback to no-shader |

The client verified, in order, the original colorway, the Rose colorway, the long-sleeve model, switching back to short sleeves, holding the MC sword and wrist rotation.
Texture switching keeps the same Model; after a model switch, switching back to short sleeves returns the same assembly Model
from the first time, and the build counter does not increase. A new session builds two assembly models in total.
Here the switch-back is served by the outfit registry reusing an already-resolved result, so the underlying assembly cache shows `hits` 0 and `builds` 2,
and registry reuse is not miscounted as an underlying cache hit.

## Local Performance Results (2026-10-08)

Three separate JavaExec JVMs were run, with PIDs `73993`, `74024` and `74050`, in the measurement order
`uncached-first`, `cached-first`, `uncached-first`. The Java runtime was `21.0.3+10-LTS` and the maximum heap was configured to 4 GiB each time.

The results below are the median of the per-JVM batch-mean p50 values. Uncached assembly includes the common geometry/skeleton assembly work;
the two warm-cache columns measure, respectively, rebuilding a request and then querying the cache, and reusing an existing request and only querying the cache.
File parsing, texture decoding, GPU skinning and drawing are not counted in these CPU assembly metrics.

| Case | Vertices | Bones | Uncached assembly (ms) | Rebuild request + warm cache (µs) | Existing request, cache query (µs) |
| --- | ---: | ---: | ---: | ---: | ---: |
| Ayuchan matching base body + long dress | 21,259 | 316 | 70.168 | 7.400 | 0.263 |
| Base body + RibbonDress short sleeves + No. 13 hair | 21,397 | 263 | 60.738 | 3.015 | 0.041 |
| Base body + RibbonDress long sleeves + No. 13 hair | 22,069 | 263 | 63.751 | 2.286 | 0.040 |
| Base body + 1 real small garment | 8,806 | 181 | 34.303 | 4.105 | 0.063 |
| Base body + 10 duplicate small garments | 12,955 | 694 | 41.089 | 20.289 | 0.093 |
| Base body + 100 duplicate small garments | 54,445 | 5,824 | 125.227 | 131.003 | 0.730 |

The 100-copy stress case repeats the same 461-vertex small garment and shares only the bind-compatible bones; the remaining bones are named independently.
This result does not mean that 100 different garments have been fitted to the base body, and cannot be converted into a game FPS gain.
The p50 of batch means is not a per-call latency distribution, and a cache query for an existing request is not a whole swap operation.

The following single-call CPU durations were also recorded in the real client; they have no warm-up statistics:

| Actual operation | Single-call CPU observation (ms) |
| --- | ---: |
| Switching short sleeves to the Rose texture | 0.087 |
| Replacing with the long-sleeve model (including instance and backend mount) | 289.673 |
| Switching back to the cached short-sleeve model (including instance and backend mount) | 171.290 |
| Switching short sleeves back to the Blue texture, two observations | 0.073 / 0.014 |

The CPU assembly cache avoids re-assembling the same geometry and skeleton, but a new ModelInstance and new backend
render resources are still created. The cache in this pass did not remove that part of the cost of a whole swap operation; the single-call figures in the table above are also not GPU or whole-frame durations.

## Current Fitting and Verification Boundaries

RibbonDress and this base body bind their same-named bones at different positions, so the clothing and hair keep their own separate bind skeletons.
This example enables no physics and runs no full-body animation; automatic body fitting and skeleton retargeting are not implemented, so it cannot be taken as a guarantee that
any garment will have no interpenetration. The Ayuchan matching long dress comparison uses strictly compatible shared bones.

The held item uses a real right-wrist Anchor and the vanilla ItemRenderer, and does not modify the player's inventory.
What this client pass actually accepted is the Diamond Sword; the Iron Pickaxe is another option the example provides.
The humanoid armor layer for worn armor, other shader packs and other operating systems were not accepted in this example.
The BSL path only verifies that it runs; do not read it as a new image-quality acceptance or performance measurement.

The interactive commands and the standalone preview window entry point are provided; the client measurements above use automated headless Free Camera output,
and are not to be read as re-verifying standalone window operation in this pass.

## Smoke Verification With a Shader Pack Enabled

Use an existing local shader pack, for example BSL 8.2.04; relative paths resolve from the repository root.

```bash
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaClientDirectory=/tmp/kasuga-wardrobe-client \
  -PkasugaQuickPlayWorld=wardrobe \
  -PkasugaWardrobeShaderSmoke=true \
  -PkasugaWardrobeShaderPack=/absolute/path/to/BSL_v8.2.04.zip
```

`kasugaWardrobeShaderSmoke=true` starts the same six-stage example with hair automatically and does not need
`kasugaWardrobeDemo` to be enabled as well. During the real model and item render stages it checks that the current Iris pack is non-empty, that a shader pack is in use,
and that the actual pipeline is `IrisRenderingPipeline`; only when all stages finish and pass the GL checks is
`WARDROBE_SHADER_SMOKE_PASS` recorded. Failure records `WARDROBE_SHADER_SMOKE_FAIL`, and a successful Gradle
exit is not taken as shader acceptance success.

By default this mode reads no pixels, saves no images/JSON and records no operation durations; it cannot be combined with `kasugaWardrobeCapture`.
It uses only the camera's own shader session and does not set BSL as the main view shader pack.
To see the actual result, add `-PkasugaWardrobeShaderPreview=true`, which saves one
`debug/wardrobe-example/shader-preview.png` during the sword stage only and still samples no performance. The image is for local viewing and is not committed.

On this machine the BSL 8.2.04 path completed all six stages, with Iris OIT enabled normally and no model upload failure, matrix stack exception or
no-shader fallback. Because the user asked to see the result, one extra sword frame was captured; the original no-shader screenshots and performance report were not overwritten.
This verification confirms that the run path is clear; it does not prove that other shader packs, other platforms or final image quality all pass.

## Performance Showcase

Run [modelAssemblyProbe](../reference/model-assembly.md#本地真实素材探针与缓存基准) three times as separate JavaExec JVMs,
alternating `uncached-first`, `cached-first`, `uncached-first`, with a different report path each time.
Besides the matching long dress and 1/10/100 duplicate small garments, the probe also measures this example's base body, RibbonDress short sleeves/long sleeves and hair assembly.
When it finishes, run:

```bash
python3 scripts/summarize-wardrobe-example.py \
  --capture /tmp/kasuga-wardrobe-client/debug/wardrobe-example \
  /tmp/wardrobe-bench-1.json /tmp/wardrobe-bench-2.json /tmp/wardrobe-bench-3.json
```

It writes only `summary.md` in the same directory; `--output /path/to/report.md` specifies a Markdown path.
The summary file depends on no images, HTML or extra summary JSON. The table shows the median of the per-JVM batch-mean p50 values and separates uncached assembly, rebuilding a request then querying the warm cache,
and querying the warm cache for an existing request. Input parsing, texture decoding, GPU skinning and drawing are not counted in these CPU assembly metrics.
The 100-copy stress case repeats the same real small garment and cannot be read as 100 different garments that have been fitted.

The `operations` entries in the client report are CPU observations of a few actual operations; a model switch includes instance and backend mounting and
must not be mixed with the warm-up benchmarks above or with GPU/whole-frame performance. Performance numbers should be taken from the report actually generated on your own machine.

> This is the verification record and performance baseline page. For the recipe covering asset preparation, interactive runs and automated screenshot acceptance, see [the real-asset wardrobe swap example](../guides/wardrobe-example.md).
