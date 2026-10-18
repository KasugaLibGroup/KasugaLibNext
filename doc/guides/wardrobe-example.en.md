# Real-Asset Wardrobe Swap and MC Equipment Example

Development example class: `lib.kasuga.rendering.models.mc.api.WardrobeExample`, in the `contentTesting` source set.
It loads user-supplied assets through the production PMX Reader, then uses only the generic `Model`, the assembly cache, material instances,
Skeleton Anchor and the MC equipment adapters. The example code does not ship in the release package.

## Local Asset Preparation

Using the already-extracted test directories `pack_1` … `pack_9`, this example picks:

| Component | Asset |
| --- | --- |
| Base body | `pack_7/TDA Morphable Base by Ayuchan513/TDA Base Edit by Ayuchan.pmx` |
| Texture-swappable clothing | `pack_4/RibbonDress/Dress_ShortSleeves.pmx` |
| Colorway | the asset's original `Dress_Sapphire.jpg` / `Dress_Rose.jpg` |
| Replaceable clothing model | `pack_4/RibbonDress/Dress_LongSleeves.pmx`, original Mist colorway |
| Matching-skeleton assembly comparison | `pack_3/#1 Dress by Ayuchan513/Dress by Ayuchan513.pmx` |
| Hair | `pack_8/VRoid Overall Hair Presets/13.pmx`, included in every outfit |
| Held item | the vanilla MC Diamond Sword; can also switch to the Iron Pickaxe |

Run from the repository root:

```bash
python3 scripts/prepare-wardrobe-example.py \
  --fixtures /absolute/path/to/extracted-fixtures \
  --output /tmp/kasuga-wardrobe-client/resourcepacks/kasuga-wardrobe-local \
  --enable
```

The script needs only the Python standard library; it keeps the original PMX and texture content and generates six `.mmd.zip` archives, the matching scale=0.1
configuration and `model_proxy.json`. The Rose archive only swaps the short-sleeve Sapphire texture for the original Rose texture, so that a second atlas sprite
is preloaded; at runtime the two copies of the garment are never rendered at the same time. `--enable` appends that directory's resource pack to the
test client's `options.txt`, keeping the other resource packs; without the flag, enable it by hand in the MC resource pack screen.

Assets, resource packs, saves and reports should live in a local test directory and are not committed to Git.

## Interactive Run

```bash
./gradlew :modules:modelling:runClient \
  -PkasugaClientDirectory=/tmp/kasuga-wardrobe-client \
  -PkasugaWardrobeDemo=true
```

On entering the test world, the example opens a standalone preview window by itself; the camera looks at the real model 40 blocks above the player.
Only one Wardrobe session is allowed per client. You can also pass no property and run `/ksglib wardrobe start` after entering the world.

| Command | Behavior |
| --- | --- |
| `/ksglib wardrobe start` | Recreates the example and the standalone window; reports an error when the source is not ready |
| `/ksglib wardrobe texture blue` / `rose` | Switches the material frame on the short model, keeping the same mesh and instance |
| `/ksglib wardrobe model short` / `long` / `ayuchan` | Replaces the clothing model, keeping the same body and handle |
| `/ksglib wardrobe item sword` / `pickaxe` / `none` | Changes the held item without modifying the real player's inventory |
| `/ksglib wardrobe capture` | Automatically captures six stages, written to `debug/wardrobe-example/` |
| `/ksglib wardrobe status` | Shows the current model, texture, item and assembly cache counters |
| `/ksglib wardrobe stop` | Releases the window, camera, equipment bindings, instance, assembly and source references |

A resource reload or leaving the current world closes the session; a fresh `start` picks up newly published assets.
Material frames do not migrate to another clothing model automatically, so this example explicitly selects blue when switching back to short.

## Automated Screenshot Acceptance

First create a test save in the test directory above, or copy an existing test save; for example, a save named `wardrobe`:

```bash
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaClientDirectory=/tmp/kasuga-wardrobe-client \
  -PkasugaQuickPlayWorld=wardrobe \
  -PkasugaWardrobeDemo=true -PkasugaWardrobeCapture=true
```

This mode does not create a preview window and captures 960×720 Free Camera output. It produces, in order:

1. The short-sleeve original Sapphire colorway.
2. The same short-sleeve model in the Rose colorway.
3. Swapped to the long-sleeve Mist model.
4. Swapped back to the already-cached short sleeves, checking that the `Model` object is identical and the build counter does not increase.
5. The vanilla MC sword rendered on the real right-wrist anchor.
6. The same render after rotating the right wrist, verifying that the weapon follows.

Each stage waits 30 frames and then takes a screenshot; the report records the image differences for material, model, equipment and bone rotation.
In a new session the automated mode performs two assembly builds in total; an interactive session that has already been exercised may reuse previous cache results.
The success log is `WARDROBE_EXAMPLE_PASS`, together with `report.json.passed=true`; the failure log is
`WARDROBE_EXAMPLE_FAIL`. A successful Gradle exit does not replace that client-side evidence. The automated mode exits MC when it finishes.

RibbonDress and this base body bind their same-named bones at different positions, so the example deliberately keeps the clothing's own bind skeleton,
enables no physics and runs no full-body animation; this is not automatic body fitting or skeleton retargeting.
The `ayuchan` comparison uses the matching long dress and strictly compatible shared bones. See
[assembly boundaries](../reference/model-assembly.md) and [Anchor and rigid equipment](../reference/anchors.md).


> This is the recipe page. The implementation and verification record for this pass, plus the local performance baseline, are in [wardrobe example verification record and performance baseline](../meta/wardrobe-benchmarks.md).
