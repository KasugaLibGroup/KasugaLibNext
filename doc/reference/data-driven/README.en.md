# Data-Driven Registration

> **Trap: the old layout does not match the current implementation.** The layout described by earlier upstream docs is
> `data/<modid>/kasugalib/*.json` (flat files, a `sources` field), which is deprecated. The current contract is the index
> directory `data/<modid>/kasuga_lib/data_driven/` plus content directories (such as `data/<modid>/kasuga_lib_content/`
> or any path declared relative to `data/<modid>/`).
>
> The current tree ships two registration entry points: the v2 host-managed **multi-source build pipeline**
> (`DataDrivenRegistrationProviders` + `ContentSourceFactory.snapshot` + `DataDrivenBuildPipeline`) and the **legacy
> single-mod JAR path** (`JsonTreeBuilder.buildForMod`).

Reference pages (this directory):

| Document | Type | Content |
| --- | --- | --- |
| [schema.en.md](schema.en.md) | Reference | Index and content fields, built-in `proxy_model` / `proxy_model_be` schema, STRICT vs LEGACY behavior, D1–D6 rules, and error classification |
| [shapes.en.md](shapes.en.md) | Reference | Shape definitions, expressions, inheritance, references, transforms, and diagnostics |
| [block-state-properties.en.md](block-state-properties.en.md) | Reference | Block-state declarations, vanilla properties, and custom factories |
| [api-registration.en.md](api-registration.en.md) | Reference | v2 host providers and build pipeline (`DataDrivenRegistrationProviders`, `ContentSourceFactory`, `SnapshotContentSource`, `DataDrivenBuildPipeline`, `BuildOptions`, `BuildResult`, `DataDrivenBuildHooks`), plus legacy `JsonTreeBuilder`, index API, dispatch, and conflict resolution |
| [api-extension.en.md](api-extension.en.md) | Reference | `TypeHandler`, `HandlerCapability` (including `OVERLAY`), `DefinitionKey`, `RegistryClaim`, `ValidationContext`, `BuildContext`, `FactoryRegistry`, and property parsers |
| [api-diagnostics.en.md](api-diagnostics.en.md) | Reference | `Diagnostics`, `/kasuga_data`, `BuildDiagnostic`, `/kasuga_proxy list`, and error classification |
| [api-reload.en.md](api-reload.en.md) | Reference | `ReloadOrchestrator`, `ReloadHandler`, `ManagedReloadAllowlist`, the assertable pure-function surface, and call examples |

> **Section-number cross-reference**: the four `api-*.md` pages in this directory were once the same page, `api.md`; after
> being split by reader task they **kept their original section numbers §1–§15**, so that the `§N` cross-references already
> written throughout the tree stay valid. Newer v2 multi-source pipeline and host-takeover sections continue from §16:
>
> | Page | Section numbers |
> | --- | --- |
> | [api-registration.md](api-registration.en.md) | §1 module and package structure, §2 JsonTreeBuilder, §3 index API, §4 content dispatch and apply, §5 conflict resolution, §16 host takeover and providers, §17 content sources and snapshots (`ContentSourceFactory` / `SnapshotContentSource`), §18 multi-source build pipeline (`DataDrivenBuildPipeline` / `BuildOptions` / `BuildResult` / `DataDrivenBuildHooks`) |
> | [api-extension.md](api-extension.en.md) | §8 TypeHandler system (including `HandlerCapability` / `DefinitionKey` / `RegistryClaim`), §9 BuildContext / RegBuildContext / ValidationContext, §10 FactoryRegistry and built-in factories, §11 property parsers and strict validation |
> | [api-diagnostics.md](api-diagnostics.en.md) | §6 Diagnostics facade, §7 `/kasuga_data errors` command, §12 error classification, §19 structured `BuildDiagnostic` and `/kasuga_proxy list` |
> | [api-reload.md](api-reload.en.md) | §13 reload API (including `ManagedReloadAllowlist` and startup preflight), §14 pure-function surface assertable from tests, §15 call examples |

Getting started and tutorials (the `guides/` section):

| Document | Type | Content |
| --- | --- | --- |
| [intro.md](../../guides/data-driven/intro.en.md) | Tutorial | The getting-started path from an empty directory to a block that moves |
| [guide-content.md](../../guides/data-driven/guide-content.en.md) | How-to | Content organization, group inheritance, last-wins, verification, and troubleshooting |
| [guide-extension.md](../../guides/data-driven/guide-extension.en.md) | How-to | Custom TypeHandler / factory / property compiler / ReloadHandler |

Explanations and architecture:

- [Data-Driven System (v2 architecture and explanations)](../../explanation/data-driven/README.en.md) (concepts, lifecycle, content snapshots, multi-source pipeline, host wiring, and migration)
- [Data-driven registration fundamentals](../../explanation/data-driven-registration.en.md)

> The few still-valid limitations from the old upstream pages (models/textures and translation keys are not generated
> automatically, the coverage of the built-in item property parser, and the fact that an embedded BE's `data_type`
> cannot be configured from JSON) have been folded into
> [guide-content.md](../../guides/data-driven/guide-content.en.md) section 7 and
> [guide-extension.md](../../guides/data-driven/guide-extension.en.md) section 10.
