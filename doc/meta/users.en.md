# Consumers

This page lists only consumer projects directly verified in the current workspace or repository; external downstream projects outside the workspace are not speculated or listed without verification.

## Verified Downstream Consumer: `KuaYue`

- **Workspace path and identity**: Sibling repository `KuaYue` (`mod_id=kuayue`, `mod_version=2.0.0`, `mod_group_id=cn.kycraft.kuayue`, MIT License in `KuaYue/LICENSE`).
- **Build setup and target environment**: Targets `Minecraft 1.21.1` / `NeoForge 21.1.203` (Parchment `2024.11.17`, `Java 21`). `KuaYue/settings.gradle` consumes `KasugaLibNext` via `includeBuild("../KasugaLibNext")`, substituting `lib.kasuga:kasuga_lib` with `:modules:library` (`Bundling.SHADOWED`), along with `inject`, `generator`, `resource-compiler`, `formula`, and `../KasugaLibNext/modules/gradle-plugin`.
- **Module layout**: Composed of `modules/core`, `modules/decoration`, `modules/train-parts`, `modules/scheduling`, `modules/ui`, `modules/provisions`, and `temporal/migration`, applying `builder.kasuga.monorepo`, `builder.kasuga.generator`, and `builder.kasuga.resource-compiler` alongside Micronaut `4.9.10` DI.

### `KasugaLibNext` Subsystems Used by `KuaYue`

| Subsystem | Observed Usage in `KuaYue` |
| --- | --- |
| DI and registration | `KuaYue.java` calls `ModApplicationContext.create(KuaYue.class)` / `init(...)` and `KasugaLibRegistry.getRegistryOf(MODID)`; content modules use `Reg`, `Registry`, `RegistryGroup`, `BlockReg`, `ItemReg`, `BlockEntityReg`, `BlockEntityRendererReg`, `RpcApi`, and `@BeanOnlyIn` |
| Data-driven v2 and resource platform | `KuaYue.java` installs `DataDrivenRegistrationProviders.install(MODID, KuaYueContentRegistrationProvider::build)` and hooks `ResourceSystem` / `ResourceSources` / `ResourceSnapshot`; `KuaYue/doc/数据驱动注册-快速上手指南.md` and `KuaYue/doc/轨道数据驱动Schema.md` document `on_register` manifests, `registry_groups`, `kuayue-preset-running-gear` (`tracks` parcel), and custom `TrackTypeHandler` registrations, while `KuaYue/modules/train-parts/resources/packs/` also ships packs such as `kuayue-preset-industrial` (containing `parcels/jy_maintenance/parcel.json`) |
| Create railway and bogey extensions | Uses `RailwayData`, `RailwayManager`, `BogeySizeReg`, `TrackReg`, `TrackMaterialReg`, `CustomTrackSegment`, `ProxyBogeyVisual`, and `ContraptionShapeProvider` |
| Model proxies, animation scopes, and dimensions | Uses `ProxyModelBlockFactories`, `BlockEntityProxyDefinitions`, `ProxyComposition`, `ProxyControllerPort`, `ScopedPlaybackCommandApplier`, `ControlFrame`, and `ControllerCheckpoint` in `modules/train-parts`; `BlueprintBlockRenderer` in `KuaYue/modules/ui` uses `BlockDimensions` / `BlockModelSource` |
| Runtime packaging hygiene | `KuaYue/modules/scheduling/build.gradle` and `KuaYue/modules/ui/build.gradle` exclude standalone `lib.kasuga:utils` from runtime classpaths so `kasuga_lib`'s shaded `-all` JAR remains the sole owner of `lib.kasuga.utils` under JPMS |

## In-Repo Reference Integrations and Examples

In addition to `KuaYue`, the `KasugaLibNext` repository itself hosts downstream-style adapter mods and test fixtures exercising the public contracts:

- **`external/uip/ldlib`, `external/uip/ldlib-compose`, `external/uip/modernui`**: Separate NeoForge adapter mods consuming `modules/library` for world UI canvases and model previews.
- **`external/slp/javet`**: Separate mod (`kasuga_lib_javet`) extending `modules/scripting` with the Javet V8 engine on top of `kasuga_lib`.
- **`examples/` and `contentTesting` source sets**: Runnable integrations for Shader DSL, the resource platform, world display, wardrobe swapping, and physics ragdolls (see [Build and Verification Entries](../guides/verification.md)).

> Downstream projects built on `KasugaLibNext` that wish to be listed here can submit a PR or issue against `master` in `KasugaLibGroup/KasugaLibNext` with their repository URL and version compatibility notes.
