# 最小示例工程

本页提供一个可直接构建并运行的 NeoForge `1.21.1` 最小下游模组工程，演示如何通过 Composite Build 接入 `KasugaLibNext`、声明 `Registry`、使用 `BlockReg` / `ItemReg` / `CreativeTabReg` 注册方块、物品与创造栏，并附带可选的数据驱动接入与 Micronaut DI 扩展说明。

> 版本与仓库前提见 [依赖与运行环境](dependencies.md)。以下示例假定你的模组工程与 `KasugaLibNext` 位于同一上级目录（即 `../KasugaLibNext`）。

## 1. 目录结构

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

## 2. Gradle 配置文件

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

`kasuga-library-*-all.jar` 在当前聚合配置下会加载 `kasuga_lib_create.mixins.json`，因此在开发期 `runClient` / `runServer` 运行时需同步提供 Create / Ponder / Flywheel / Registrate 依赖（与 `modules/library/build.gradle` 和 `KuaYue/modules/core/build.gradle` 一致）：

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

    // 与聚合模组 kasuga_lib_create.mixins.json 匹配的运行期伴随模组
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

## 3. 模组元数据 `src/main/templates/META-INF/neoforge.mods.toml`

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

## 4. 最小 Java 入口 `ExampleMod.java`

只使用 `KasugaLibRegistry` 与 `Reg` 系列 API 时，无需在下游模组引入 Micronaut 注解处理器：`Registry.register(modEventBus)` 内部会自动通过 `KasugaLib.afterRunning` 等待 `KasugaLibStartupEvent` 并在 `KasugaLib` 自身的应用上下文就绪后分派所有注册阶段；同时也会触发 `JsonTreeIntegration`，自动扫描你模组 JAR 内的 `data/examplemod/kasuga_lib/data_driven/*.json`。

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

关键行为说明（对应 `modules/core` 源码）：

- `KasugaLibRegistry.getRegistryOf(MODID)` 创建根 `Registry`，并自动附加 `ResourceLocationModifiers.withNamespace(MODID)`，因此子节点的资源名会自动变为 `examplemod:<name>`。
- `BlockReg.withDefaultBlockItem("example_block")` 会在 `BlockReg` 下挂载一个子 `ItemReg<BlockItem>`；在 `BlockReg`（或公共 `RegistryGroup`）上调用 `.configure(CreativeTabModifiers.set(EXAMPLE_TAB::getResourceLocation))` 会沿着父链传播给子 `BlockItem`。
- 实际注册对象需在 `RegisterEvent` 完成后通过 `EXAMPLE_ITEM.getEntry()` / `EXAMPLE_BLOCK.getEntry()` 延迟获取，切勿在类静态初始化阶段直接解引用。
- `REGISTRY.registerClient(modEventBus)` 标有 `@OnlyIn(Dist.CLIENT)`，仅应在客户端物理侧调用（负责绑定菜单界面、模型烘焙回调与流体客户端扩展等阶段）。

补充本地化文本 `src/main/resources/assets/examplemod/lang/en_us.json`：

```json
{
  "itemGroup.examplemod.example_tab": "Example Mod",
  "item.examplemod.example_item": "Example Item",
  "block.examplemod.example_block": "Example Block"
}
```

## 5. 运行与验证

在 `examplemod/` 目录下执行：

```bash
./gradlew build
./gradlew runClient
./gradlew runServer
```

启动日志中应能看到 `KasugaLibApplication initialized.`，进入游戏后可在创造模式物品栏看到 `Example Mod` 标签页及其中的 `Example Item` 和 `Example Block`。

## 6. 进阶：接入 Micronaut DI 与数据驱动

当模组体积增大、需要模块化依赖注入或 `@Configurable` 配置绑定时，可采用与 `KasugaLib` / `KuaYue` 相同的 DI 结构：

1. 按 [依赖与运行环境](dependencies.md) 添加 `lib.kasuga:inject` 与 Micronaut `4.9.10` 依赖及注解处理器。
2. 在主 `@Mod` 类中创建并启动自己的上下文：

```java
public static final ApplicationContext context = ModApplicationContext.create(ExampleMod.class);

public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
    ModApplicationContext.init(context, modEventBus, modContainer);
}
```

3. 使用 `@Context`（通用入口）和 `@Context` + `@BeanOnlyIn.Client`（仅客户端入口）拆分 `REGISTRY.register(modEventBus)` 与 `REGISTRY.registerClient(modEventBus)`。

更多内容请继续阅读：

- [数据驱动上手：从空目录到方块动起来](../guides/data-driven/intro.md)：不写 Java 声明方块、状态机与动画剪辑
- [把内容接入 Proxy Kernel](../guides/proxy-integration.md) 与 [模型加载与渲染](../reference/modelling/README.md)：为方块实体挂接 `.bbmodel` / `.gltf` / `.mmd.zip` 模型
- [生命周期与初始化顺序](../reference/lifecycle.md) 与 [侧边模型](../reference/sidedness.md)：了解注册阶段与客户端/服务端隔离约束
