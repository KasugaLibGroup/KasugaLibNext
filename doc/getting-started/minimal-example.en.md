# Minimal Example Project

This page walks through a complete, runnable NeoForge `1.21.1` minimal downstream mod project wired to `KasugaLibNext` via a Gradle composite build. It demonstrates `Registry`, `BlockReg`, `ItemReg`, and `CreativeTabReg`, plus notes on optional Micronaut DI and data-driven integration.

> See [Dependencies and Runtime Environment](dependencies.md) for prerequisite versions. The setup below assumes your mod directory sits next to `KasugaLibNext` (`../KasugaLibNext`).

## 1. Directory Layout

```text
examplemod/
├── settings.gradle
├── build.gradle
├── gradle.properties
└── src/
    └── main/
        ├── java/com/example/examplemod/
        │   └── ExampleMod.java
        ├── resources/assets/examplemod/
        │   └── lang/en_us.json
        └── templates/META-INF/
            └── neoforge.mods.toml
```

## 2. Gradle Configuration

### `settings.gradle`

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.9.0'
}

rootProject.name = 'examplemod'

includeBuild('../KasugaLibNext') {
    dependencySubstitution {
        substitute module('lib.kasuga:kasuga_lib') using project(':modules:library')
        substitute module('lib.kasuga:inject') using project(':modules:inject')
    }
}
```

### `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.daemon=true

minecraft_version=1.21.1
minecraft_version_range=[1.21.1]
neo_version=21.1.268
parchment_minecraft_version=1.21.1
parchment_mappings_version=2024.11.17
loader_version_range=[1,)
kasuga_lib_version_range=[1.0.0,)

mod_id=examplemod
mod_name=Example Mod
mod_license=All Rights Reserved
mod_version=1.0.0
mod_group_id=com.example.examplemod
mod_authors=Author
mod_description=Minimal KasugaLibNext example mod.

create_version=6.0.11-346
ponder_version=1.0.92
flywheel_version=1.0.7
registrate_version=MC1.21-1.3.0+67
```

### `build.gradle`

Because the aggregate `kasuga-library-*-all.jar` currently declares `kasuga_lib_create.mixins.json`, dev runs (`runClient` / `runServer`) also provide Create, Ponder, Flywheel, and Registrate (matching `modules/library/build.gradle` and `KuaYue/modules/core/build.gradle`):

```groovy
plugins {
    id 'java-library'
    id 'net.neoforged.moddev' version '2.0.107'
}

version = mod_version
group = mod_group_id

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

repositories {
    mavenCentral()
    maven { url = 'https://maven.createmod.net' }
    maven { url = 'https://maven.ithundxr.dev/snapshots' }
}

neoForge {
    version = project.neo_version

    parchment {
        mappingsVersion = project.parchment_mappings_version
        minecraftVersion = project.parchment_minecraft_version
    }

    runs {
        client {
            client()
        }
        server {
            server()
            programArgument '--nogui'
        }
    }

    mods {
        "${mod_id}" {
            sourceSet(sourceSets.main)
        }
    }
}

dependencies {
    implementation('lib.kasuga:kasuga_lib:1.0.0') {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling, Bundling.SHADOWED))
        }
        transitive = false
    }

    // Companion mods expected at runtime by kasuga_lib_create.mixins.json
    implementation("com.simibubi.create:create-${minecraft_version}:${create_version}") { transitive = false }
    implementation("net.createmod.ponder:ponder-neoforge:${ponder_version}+mc${minecraft_version}")
    compileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-${minecraft_version}:${flywheel_version}")
    runtimeOnly("dev.engine-room.flywheel:flywheel-neoforge-${minecraft_version}:${flywheel_version}")
    implementation("com.tterrag.registrate:Registrate:${registrate_version}")
}

def generateModMetadata = tasks.register('generateModMetadata', ProcessResources) {
    def replaceProperties = [
            minecraft_version       : minecraft_version,
            minecraft_version_range : minecraft_version_range,
            neo_version             : neo_version,
            loader_version_range    : loader_version_range,
            kasuga_lib_version_range: kasuga_lib_version_range,
            mod_id                  : mod_id,
            mod_name                : mod_name,
            mod_license             : mod_license,
            mod_version             : mod_version,
            mod_authors             : mod_authors,
            mod_description         : mod_description
    ]
    inputs.properties replaceProperties
    expand replaceProperties
    from 'src/main/templates'
    into 'build/generated/sources/modMetadata'
}
sourceSets.main.resources.srcDir generateModMetadata
neoForge.ideSyncTask generateModMetadata

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
}
```

## 3. Mod Metadata `src/main/templates/META-INF/neoforge.mods.toml`

```toml
modLoader="javafml"
loaderVersion="${loader_version_range}"
license="${mod_license}"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="${mod_authors}"
description='''${mod_description}'''

[[dependencies.${mod_id}]]
    modId="neoforge"
    type="required"
    versionRange="[${neo_version},)"
    ordering="NONE"
    side="BOTH"

[[dependencies.${mod_id}]]
    modId="minecraft"
    type="required"
    versionRange="${minecraft_version_range}"
    ordering="NONE"
    side="BOTH"

[[dependencies.${mod_id}]]
    modId="kasuga_lib"
    type="required"
    versionRange="${kasuga_lib_version_range}"
    ordering="AFTER"
    side="BOTH"
```

## 4. Minimal Java Entry Point `ExampleMod.java`

When using only `KasugaLibRegistry` and the `Reg` builders, your downstream mod does not need Micronaut or annotation processing: `Registry.register(modEventBus)` delegates via `KasugaLib.afterRunning` (`KasugaLibStartupEvent`) to `KasugaLib`'s own `ApplicationContext` and dispatches registration stages as well as `JsonTreeIntegration` (automatically loading `data/examplemod/kasuga_lib/data_driven/*.json` if present).

```java
package com.example.examplemod;

import lib.kasuga.KasugaLibRegistry;
import lib.kasuga.registration.Registry;
import lib.kasuga.registration.core.CreativeTabModifiers;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.creative_tab.CreativeTabReg;
import lib.kasuga.registration.minecraft.item.ItemReg;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(ExampleMod.MODID)
public class ExampleMod {
    public static final String MODID = "examplemod";

    public static final Registry REGISTRY = KasugaLibRegistry.getRegistryOf(MODID);

    public static final CreativeTabReg EXAMPLE_TAB = new CreativeTabReg("example_tab")
            .title(Component.translatable("itemGroup.examplemod.example_tab"))
            .icon(() -> new ItemStack(ExampleMod.EXAMPLE_ITEM.getEntry()))
            .setParent(REGISTRY);

    public static final ItemReg<Item> EXAMPLE_ITEM = ItemReg.of("example_item", Item::new)
            .stacksTo(64)
            .tabTo(EXAMPLE_TAB)
            .setParent(REGISTRY);

    public static final BlockReg<Block> EXAMPLE_BLOCK = BlockReg.of("example_block", Block::new)
            .strength(2.0f, 3.0f)
            .requiresCorrectToolForDrops()
            .withDefaultBlockItem("example_block")
            .configure(CreativeTabModifiers.set(EXAMPLE_TAB::getResourceLocation))
            .setParent(REGISTRY);

    public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
        REGISTRY.register(modEventBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            REGISTRY.registerClient(modEventBus);
        }
    }
}
```

Key behaviors (from `modules/core`):

- `KasugaLibRegistry.getRegistryOf(MODID)` creates the root `Registry` and attaches `ResourceLocationModifiers.withNamespace(MODID)`, so child entries resolve under `examplemod:<name>`.
- `BlockReg.withDefaultBlockItem("example_block")` attaches a child `ItemReg<BlockItem>`; configuring `CreativeTabModifiers.set(EXAMPLE_TAB::getResourceLocation)` on the `BlockReg` (or a parent `RegistryGroup`) propagates down to that child `BlockItem`.
- Call `EXAMPLE_ITEM.getEntry()` / `EXAMPLE_BLOCK.getEntry()` only after `RegisterEvent` has run (e.g., inside `Supplier` callbacks or gameplay code), never during static field initialization.
- `REGISTRY.registerClient(modEventBus)` is annotated `@OnlyIn(Dist.CLIENT)` and handles client-only stages (menu screens, model baking completion, fluid client extensions), so guard it behind `FMLEnvironment.dist == Dist.CLIENT` or a `@BeanOnlyIn.Client` bean.

Add a minimal language file at `src/main/resources/assets/examplemod/lang/en_us.json`:

```json
{
  "itemGroup.examplemod.example_tab": "Example Mod",
  "item.examplemod.example_item": "Example Item",
  "block.examplemod.example_block": "Example Block"
}
```

## 5. Build and Run

From `examplemod/`:

```bash
./gradlew build
./gradlew runClient
./gradlew runServer
```

The startup log prints `KasugaLibApplication initialized.`, and in-game you will find the `Example Mod` creative tab containing `Example Item` and `Example Block`.

## 6. Next Steps: Micronaut DI and Data-Driven Content

For larger mods that want modular dependency injection or `@Configurable` NeoForge configs, mirror the `KasugaLib` / `KuaYue` pattern:

1. Add `lib.kasuga:inject` and Micronaut `4.9.10` (plus `annotationProcessor 'io.micronaut:micronaut-inject-java:4.9.10'`) as shown in [Dependencies and Runtime Environment](dependencies.md).
2. Initialize your mod's `ApplicationContext` inside your `@Mod` constructor:

```java
public static final ApplicationContext context = ModApplicationContext.create(ExampleMod.class);

public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
    ModApplicationContext.init(context, modEventBus, modContainer);
}
```

3. Dispatch `REGISTRY.register(modEventBus)` from a `@Context` bean and `REGISTRY.registerClient(modEventBus)` from a `@Context` + `@BeanOnlyIn.Client` bean.

Continue exploring:

- [Data-driven getting started: from an empty directory to a moving block](../guides/data-driven/intro.md): register blocks, state machines, and clips with JSON
- [Integrating Content With the Proxy Kernel](../guides/proxy-integration.md) and [Modelling Module](../reference/modelling/README.md): attach `.bbmodel`, `.gltf`, and `.mmd.zip` models to block entities
- [Lifecycle and Initialization Order](../reference/lifecycle.md) and [Sidedness Model](../reference/sidedness.md): registration stages and client/server isolation rules
