# Data-Driven Registration

> **Trap: the old layout does not match the current implementation.** The layout described by earlier upstream docs is
> `data/<modid>/kasugalib/*.json` (flat files, a `sources` field), which is deprecated. The current contract is the index
> directory `data/<modid>/kasuga_lib/data_driven/` plus the content directory `data/<modid>/kasuga_lib_content/`.

Reference pages (this directory):

| Document | Type | Content |
| --- | --- | --- |
| [schema.md](schema.md) | Reference | Fields, types, D1–D6 rules, and error classification for the index manifest and content files |
| [api-registration.md](api-registration.md) | Reference | Registration-phase entry point `JsonTreeBuilder`, the index API, content dispatch, and conflict resolution |
| [api-extension.md](api-extension.md) | Reference | The `TypeHandler` system, `BuildContext`, `FactoryRegistry`, and property parsers |
| [api-diagnostics.md](api-diagnostics.md) | Reference | The `Diagnostics` facade, the `/kasuga_data` command, and error classification |
| [api-reload.md](api-reload.md) | Reference | The reload API, the assertable pure-function surface, and call examples |

> **Section-number cross-reference**: the four `api-*.md` pages in this directory were once the same page, `api.md`; after
> being split by reader task they **kept their original section numbers**, so that the `§N` cross-references already
> written throughout the tree stay valid — which is why the numbering inside each page is not contiguous:
>
> | Page | Original section numbers |
> | --- | --- |
> | [api-registration.md](api-registration.md) | §1 module and package structure, §2 JsonTreeBuilder, §3 index API, §4 content dispatch and apply, §5 conflict resolution |
> | [api-extension.md](api-extension.md) | §8 TypeHandler system, §9 BuildContext / RegBuildContext, §10 FactoryRegistry, §11 property parser |
> | [api-diagnostics.md](api-diagnostics.md) | §6 Diagnostics facade, §7 `/kasuga_data errors` command, §12 error classification |
> | [api-reload.md](api-reload.md) | §13 reload API, §14 pure-function surface assertable from tests, §15 call examples |

Getting started and tutorials (the `guides/` section):

| Document | Type | Content |
| --- | --- | --- |
| [intro.md](../../guides/data-driven/intro.md) | Tutorial | The getting-started path from an empty directory to a block that moves |
| [guide-content.md](../../guides/data-driven/guide-content.md) | How-to | Content organization, group inheritance, last-wins, verification, and troubleshooting |
| [guide-extension.md](../../guides/data-driven/guide-extension.md) | How-to | Custom TypeHandler / factory / property compiler / ReloadHandler |

For an overview of the mechanics, see [Data-driven registration fundamentals](../../explanation/data-driven-registration.md).

> The few still-valid limitations from the old upstream pages (models/textures and translation keys are not generated
> automatically, the coverage of the built-in item property parser, and the fact that an embedded BE's `data_type`
> cannot be configured from JSON) have been folded into
> [guide-content.md](../../guides/data-driven/guide-content.md) section 7 and
> [guide-extension.md](../../guides/data-driven/guide-extension.md) section 10.
