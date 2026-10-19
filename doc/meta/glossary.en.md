# Chinese-English Glossary

The site's documentation, source code and issues use one set of terms in both languages. When you write docs or issues, or read the other language's pages, take your wording from this page.

Identifiers (class names, JSON keys, commands, module names, paths, version strings) keep their original form in both languages, and this page gives no translations for them.

## Core terminology

Grouped by subsystem. The left column is the term used in the English pages, the right column is the Chinese wording that appears in the Chinese pages. Everyday prose outside these tables follows ordinary technical English.

### Model and animation

| English | 中文 | Notes |
| --- | --- | --- |
| pose | Pose / 姿态 | The code type name is `Pose`; not posture or stance |
| static pose | 静态姿态 | A constantly sampled clip; channel ownership and reset rules are the same as for any other clip |
| pose blending | 姿态混合 | Layers compose in a fixed order: the base layer sets the reference, additive layers accumulate local motion, override layers replace the masked bone range |
| pose provider | 姿态供应器 | Where `Supplier<WorldCameraView>` appears it is a Java interface; write `Supplier` |
| PoseDriver | PoseDriver | Class name, not translated |
| clip | 片段 | "Animation clip" names the same thing |
| named clip library | 命名片段库 | |
| sampler | 采样器 | |
| sample / sampling | 采样 | |
| evaluate / evaluation | 求值 | Not compute: `compute` is the arithmetic engine package of the formula module |
| timeline | 时间轴 | |
| shared timeline | 共享时间轴 | Driven by a single host; models and the camera only follow it |
| clock | 时钟 | An independent player owns its clock: game ticks advance time, and render frames only sample by partial tick |
| keyframe | 关键帧 | |
| component track | 分量轨道 | Each track drives one component of `x / y / z / yaw / pitch / roll / zoom` |
| easing | 缓动 | Reuses the model's `Easing` and applies to the next segment after a keyframe |
| morph | morph | Not translated; not deformation or blend shape |
| material frame | 材质帧 | |
| bone mask | 骨骼掩码 | Affects only the listed bone groups |
| layer | 层 / 状态层 | Multiple layers run in parallel and their poses are composed into the model each tick |
| layered state machine | 分层状态机 | The FSM is a Mecanim-style layered state machine |
| transition | 转移 | |
| guard | 守卫 | All guards must be true for the transition to be allowed |
| trigger | 触发器 | Points at a boolean variable name or id; raising it fires |
| state variable | 状态变量 | |
| cross-fade | 淡入淡出 | Cross-fade for both Chinese wordings; 0 switches immediately |
| variant | 变体 | Not version or edition, which here mean a resource version |
| variant selector | 变体选择器 | |
| context | 上下文 | The Multiplexer's context snapshot; unrelated to the "host frame context" of the rendering framework |
| retargeting | 重定向 | Topology retargeting between skeletons is the integrator's responsibility; code entry point `AnimationPlayback.retarget` |
| formula track | 公式轨道 | Evaluated every frame |

### Skeleton, physics and ragdoll

| English | 中文 | Notes |
| --- | --- | --- |
| skeleton | 骨架 / Skeleton | Structures always take skeleton; not framework |
| bone | 骨骼 / Bone | |
| bind pose | 绑定姿态 | Code field `bindPoseFollowers` |
| skinning | 蒙皮 | Same form for noun and gerund; not skin, since `Skin` / `SkinInstance` are the format's skin data in glTF/PMX |
| skin weight | 蒙皮权重 | |
| rigid body | 刚体 | `GenericRigidBody` is the default for non-PMX content |
| joint | 关节 | |
| constraint | 约束 | |
| limit | 限位 | Only limit / limits, never limiter or position limit |
| reverse mapping | 反向映射 | `ikChainsByBone()` gives the mapping from a driver bone to every chain that controls it |
| inverse kinematics (IK) | 逆向运动学 / IK | The abbreviation IK is not translated |
| diamond closure | 菱形闭环 | Two bases share a pivot at the bind position and two tips stand for the same closure point. Not diamond constraint or diamond loop |
| closure residual | 闭环残差 | Counts both the tip separation and the base separation; stopping the iteration does not mean convergence, so diagnostics keep residual and satisfied |
| driver bone | 驱动骨 | |
| effector bone | 末端骨 / effector | The field name effector stays; use endpoint only when describing the closure point |
| chain / IK chain | 链 / IK 链 | From base to tip; the last link points at the effector |
| writeback | 写回 | Not feedback or write into |
| fixed step | 固定步长 | `KinematicDriver.beginStep(world)` runs before every fixed step |
| physics writeback | 物理写回 | Only the hierarchy is rebuilt afterwards; IK cannot be solved again |
| ragdoll | 布娃娃 | |
| active ragdoll | 主动布娃娃 | The same frame's animation/IK targets become bounded PD spring forces and torques; Box3D still handles contacts and joints |
| secondary motion | 次级运动 | Not secondary physics; keys such as `include_secondary_bodies` are copied verbatim |
| body profile | 主体 profile | profile stays as is; the profile generates capsules, semantic mass and joints from the actual bone segments |
| floating origin | 浮动原点 | The model root's world translation is moved out of the float bone matrices and kept as a separate double `worldOrigin` |
| large coordinates | 大坐标 | |
| world anchor | 世界锚点 | The double world anchor is kept apart from the local float physics coordinates; environment collision, rays and drag are converted into one simulation frame first |
| solver | 求解器 | `CalikoIkSolver` offers `solve()` and `results()` |
| sleeping | 休眠 | Sleeps a whole group of rigid bodies; turn it off when debugging contacts or continuous airborne simulation |
| impulse | 冲量 | Changes velocity immediately; not momentum; code entry point `applyImpulse` |
| force / torque | 力 / 力矩 | Integrated by the next native Box3D step; not strength |
| drag | 拖拽 | Creates a kinematic anchor and a spherical joint in native Box3D instead of teleporting the body |
| environment mesh | 环境网格 | `StaticEnvironmentMesh` incrementally syncs MC terrain through `putCell` / `removeCell`; Java never solves collisions |
| contact | 接触 | A negative `separation` means penetration |
| take over | 接管 | IK takes over the authored local pose of the chain bones; with physics enabled, physics takes over pose evaluation |
| crosshair picking | 准星拾取 | The mouse button (`0` is the left button) and the maximum picking distance |
| base body | 素体 | The undressed base humanoid model; not bare model |

### Rendering and output

| English | 中文 | Notes |
| --- | --- | --- |
| pipeline | 管线 | Not conduit or duct |
| model pipeline | 模型管线 | `ModelPipeLine` |
| scheduling | 调度 | `ModelRenderScheduler` keeps one policy per mounted instance, `ALWAYS` by default |
| visibility | 可见性 | Continuous drawing, explicit host control and the vanilla renderer mark are the three policies |
| culling | 剔除 | Not cropping or clipping |
| frustum | 视锥 | Always view frustum |
| render distance | 视距 | `setMaxRenderDistance(instance, blocks)` culls by squared camera distance; 0 means unlimited |
| frame mark | 帧标记 | The host renderer calls `markRenderedThisFrame` within its visible frame; the first model pass calls `flipFrame` once |
| gate (verb: gating) | 门控 / 闸门 | Two Chinese wordings for one concept, both gate; distance and frustum are extra gates on top of the mode |
| mount | 挂载 | The registry matches mounts by the caller's key; not installation |
| attachment | 装备挂接 | `McModelHandle.attachToAnchor` / `detachFromAnchor` |
| camera view | 机位 | One rendering unit inside the same client process; not camera, rig or station |
| multi-camera | 多机位 | Never multi-view (see "Terms that are easy to translate wrongly") |
| view | 视角 | `view ID` identifies an already rendered view; do not mix it up with camera view |
| main view | 主视角 | Not main camera |
| main frame | 主画面 | `MIRROR` delivers both the FBO and the display window |
| offscreen | 离屏 | |
| borrow | 借用 | Output is a synchronous read-only borrow on the render thread; consumers may not resize or delete textures |
| read-only borrow | 只读借用 | |
| compositing | 合成 | |
| effect | 效果 | Effects of the same kind share renderers and pipeline state |
| post-processing | 后处理 | |
| scope | 作用域 | A registrar for dynamic content that closes the resources it owns; parent scopes own child scopes |
| descriptor | 描述 | An immutable value that defines a piece of resource configuration |
| generation | 分代 | Shader handles stay stable across resource reloads while the internal compiled instances are replaced per generation |
| upload | 上传 | A GPU upload receives a complete snapshot; the dirty range describes the changed elements |
| in-flight read | 在途读取 | Storage cannot be overwritten while the GPU is still reading it |
| upload ring | 上传环 | `UploadRing` handles slot rotation, dirty-element merging, version tracking and resource lifetime |
| atlas | 图集 | Base color, normal and specular are packed into three matching atlases |
| material variant | 材质变体 | Same source texture with different parameters produces an independent variant and independent normal/specular sprites |
| straight alpha | 直通 alpha | |
| translucent replay | 半透明回放 | Every draw and every translucent replay records consumption; a completion marker must not be missed even when this frame's data did not change |
| ray marching | 光线步进 | The cloud field is drawn with one sky-covering ray march plus one composite pass, never one full-screen pass per cloud |
| self-shadowing | 自遮光 | The cloud field samples nearby density along the sun direction to approximate its own shadowing |
| batching | 合批 | Static block geometry suits section mesh batching |
| host | 宿主 | The host owns the frame and view entry points; the backend owns resources and drawing |
| backend | 后端 | |
| geometry adapter | 几何适配器 | Converts model representations; the resource factory creates one instance's resources |
| frame output | 帧输出 | The WYSIWYG output entry point for the final frame |
| render thread | 渲染线程 | Register on the render thread and keep the returned registration |

### Data-driven

| English | 中文 | Notes |
| --- | --- | --- |
| data-driven | 数据驱动 | |
| registration phase | 注册期 | The stage in which a mod writes objects into MC registries (such as `BuiltInRegistries.BLOCK`) during startup and construction; table headers shorten it to registration |
| reload phase | reload 期 | The resource re-read triggered by `/reload`; the source is the pack stack, so data packs can override it |
| the two phases | 两个时机 | registration phase and reload phase; not "two timings" |
| domain | 域 | The two arrays of an index manifest: `on_register` (registration domain) and `on_reload` (reload domain); code `Diagnostics.Domain` |
| registration domain / reload domain | 注册域 / 重载域 | |
| bucket | 桶 | A list holding diagnostics, split by mod id and by type; not basket or pail |
| index manifest | 索引清单 | Lists paths only and holds no object definitions; "index file" is the same thing, not list or inventory |
| content file | 内容文件 | The single source of truth for block, item and group definitions |
| top-level type | 顶层类型 | A content file's top-level field name is the top-level registry type and its value must be an array of objects |
| embedded type | 内嵌类型 | Such as `block_entities`, which is not a top-level field |
| factory | 工厂 | When no factory matches a `type`, the entry is logged as a WARN and skipped without entering the error bucket |
| factory registration key | 工厂注册键 | The `type` field of a JSON entry |
| property parser | 属性解析器 | The block-side layer is `JsonPropertyParser.registerCompiler`; the API name there is compiler |
| registry group | 注册组 | Establishes property grouping (parent / child) |
| embedded block entity | 内嵌方块实体 | |
| last-wins | 后写覆盖 / last-wins | The last write wins for a duplicate effective id; resource pack overrides follow pack stack order; code comments spell it `last-wins` |
| deduplication | 去重 | `DuplicateIdResolver` is a pure last-wins deduplication |
| orchestrator | 编排器 | Also written 编排者, same meaning; code `ReloadOrchestrator` |
| dispatch | 分派 | Code `dispatchContent` |
| phase | 阶段 | Coexists with the registration and reload phases and denotes a handler's execution stage (`PHASE_GROUPS`) |
| hot reload | 热更 | New content takes effect right after `/reload`; some content is loaded once and cannot be hot reloaded |
| pack stack | 包栈 | Read from data packs rather than the jar, so data packs can override these files |
| directory glob | 目录 glob | glob stays as is; declared by `FsmReloadHandler.globDirectories()` |
| content tree | 内容树 | The parsing layer only builds the raw content tree |
| diagnostics facade | 诊断门面 | `diagnostics/Diagnostics.java` is the single diagnostics outlet |
| animation clip | 动画剪辑 | A bone / morph / material frame track sampled along a timeline; JSON key `animation_clips` |
| decoder | 解码器 | A pure file-layer component (`StateMachineDefinitionLoader` / `AnimationClipLoader`); parse, decode and solve are independent |

### Lifecycle, build and verification

| English | 中文 | Notes |
| --- | --- | --- |
| lifecycle | 生命周期 | |
| idempotent | 幂等 | Not idempotency when used as an adjective |
| prepare-then-publish | 先准备，再发布 | A new mount must be prepared successfully before it can be published; on a failed replacement the unpublished new context is closed and the old mount kept |
| invalidate | 失效 | Invalidation and eviction do not modify results already handed out; code `invalidate(Model)` |
| resource reload | 资源重载 | Cache eviction does not destroy a model that is still rendering; a resource reload requires the host to end its old instances before releasing their borrowed resources |
| source set | 源集 | A Gradle source set, such as `contentTesting` |
| test fixture | 测试夹具 | Not shipped with the library; not clamp or jig |
| smoke test | 烟雾测试 | |
| verification entry | 验证入口 | Gradle tasks, standalone GL, client acceptance and evidence boundaries |
| client acceptance | 客户端验收 | |
| success marker | 成功标识 | Such as `ANCHOR_EQUIPMENT_SMOKE_PASS`, with the report in `debug/anchor-equipment.json` |
| headless | 无窗口 | `runClientHeadless` is still a full client: hidden by default on desktop platforms, EGL by default on Linux |
| evidence boundary | 证据边界 | A verification record should state compile / JVM, native physics, standalone GL, real client and platform / renderer combination separately |
| outfit | 穿搭 | The code variable name is `ModelAssembly outfit`; not wardrobe, which names an example page |
| swap | 换装 | Code `McModelHandle.switchAssembly`; not switch, which collides visually with the Java keyword |
| assembly | 组装 | Code `ModelAssembly` / `ModelAssemblyBuilder` |
| part | 组件 / 部件 | |
| hidden region | 隐藏区域 | Expanded into face indices when the Part is built |
| reference remapping | 引用重映射 | Code `ModelAssembly.Remap` |
| cache key | 缓存键 | Covers part order, the object identity and revision of the source Models, the bone mapping, the final hidden-face set and the dynamics switch |
| anchor | 锚点 | Class name `Anchor`, not translated |
| display child | 显示子物体 | A display anchor bound to a skinned bone, such as the sword in a player's hand |
| display context | 显示上下文 | The vanilla `ItemDisplayContext` |
| dominant hand | 惯用手 | `bindHands` picks the physical hand anchor from the entity's dominant hand |
| namespace | 命名空间 | Unrelated to the formula module's namespace variable (`Namespace`) |
| resource location | 资源位置 | The code name `ResourceLocation` stays |
| creative tab | 创建栏 | A vanilla concept; the Chinese pages also say 创意标签页, both creative tab |
| lighting | 光照 | The MC light system (light engine); strictly distinct from shader pack |
| shader pack | 光影 | An Iris shader pack (`ShaderPack` / `PipelineManager`), not lighting |

## Do-not-translate list

These keep their original form in both languages; the examples are the forms that actually appear in the site.

| Category | Examples |
| --- | --- |
| Class / API names | `ModelInstance`, `ModelTickLoop`, `SkeletonDynamicsBuilder`, `CalikoIkSolver`, `ModelAssemblyCache`, `RenderPipelineScope`, `JsonTreeBuilder`, `ReloadOrchestrator`, `Diagnostics`, `McModelHandle` |
| JSON keys / field names | `on_register`, `on_reload`, `state_vars`, `bone_mask`, `cross_fade_seconds`, `alphaCutoff`, `include_secondary_bodies`, `model_scale`, `registry_groups`, `state_machines`, `animation_clips`, `ik_enabled` |
| Commands | `/reload`, `/kasuga_data errors`, `/kasuga_ragdoll deploy`, `/kasuga_physics block [1-32]`, `/ksglib debug oit`, `/kasuga_pbr status` |
| Module / directory names | `modules/modelling`, `modules/render-core`, `modules/render-opengl`, `modules/data-driven`, `modules/formula`, `external/slp/javet`, `contentTesting` |
| Version strings | `Minecraft 1.21.1`, `NeoForge 21.1.203`, `Parchment 2024.11.17`, `Java 21`, `GLSL 150`, `Iris 1.8.12`, Box3D commit `30c67b5e6d0a3a66f0f506c69ce9e9e0587e3b7c` |
| Abbreviations | FSM, IK, PBR, OIT, WBOIT, MRT, AO, FBO, VBO, TBO, UBO, SSBO, VAO, CCD, AABB, UV, SSS, LabPBR, LRU, LOD, MMD, PMX, PMD, VMD, VPD, glTF, BE (block entity), D1–D6 |
| Technologies / library names | OpenGL, LWJGL, JOML, Blaze3D, Micronaut, DataFixerUpper, Box3D, Caliko, Iris, Sodium, Blockbench, NeoForge, Fabric, Gradle, JUnit |
| Model format names | `.bbmodel`, `.geo.json`, `.obj`, `.glb`, `.gltf`, `.mmd.zip`, `.pmx`, `.pmd`, `.vmd`, `.vpd` |
| Resource / file paths | `data/<namespace>/kasuga_lib/data_driven/`, `assets/<namespace>/models/model_proxy.json`, `modules/modelling/run/client/`, `build/reports/modelAssemblyProbe/report.json` |
| Gradle tasks and properties | `:modules:modelling:modelUnitTest`, `:modules:modelling:renderGlTest`, `renderingReleaseCheck`, `-PkasugaTestModel=mmd`, `-PkasugaClientDirectory`, `-Dkasuga.renderTestModels=false` |
| Log strings / success markers | `Loaded N JSON entries across M types for mod '<mod id>'`, `ANCHOR_EQUIPMENT_SMOKE_PASS`, `CAMERA_ANIMATION_SMOKE_PASS`, `REAL_OVERLAY_SKINNING_PASS`, `FRAME_WINDOWS_SMOKE_PASS`, `HEADLESS_CLIENT_SMOKE_PASS`, `OITVisualTestScene` |
| Render states / phase enums | `AFTER_ENTITIES`, `AFTER_TRANSLUCENT_BLOCKS`, `OPAQUE`, `MASK`, `BLEND`, `MIRROR`, `OFFSCREEN_ONLY`, `PHASE_GROUPS` / `PHASE_CONTENT` / `PHASE_EMBEDDED`, `READY / PAUSED / FAILED / CLOSED`, `ALWAYS / MANUAL / HOST_RENDERER / VANILLA_RENDERER` |
| Platform / driver terms | `F3 + T`, `EGL`, `pbuffer`, `surfaceless`, `DPI`, `read/draw FBO`, `viewport`, `fence`, `storage`, `orphan`, `glReadPixels`, `glFinish`, `glBlitFramebuffer` |

## Terms that are easy to translate wrongly

Each of these already means something else in rendering, animation or this repository's code, so the wrong pick collides with an established concept. The left column holds the term to use.

| English term | 中文 | ❌ Don't write | Notes |
| --- | --- | --- | --- |
| multi-camera | 多机位 | `multi-view`, `multi-viewport` | `multi-view` already means multiview rendering, which is not the same as this project rendering several camera views in sequence inside one client, each with its own world cache and output |
| camera view | 机位 | `camera`, `rig`, `station` | A viewpoint, not a physical rig; `camera` collides with `CameraType` (Player / Free / Fixed) and the vanilla Camera |
| view | 视角 | `camera`; don't use 视角 for a camera view | A view is the output-side route (`view ID`, main view = main view); camera view is the viewpoint |
| diamond closure | 菱形闭环 | `diamond constraint`, `diamond loop` | `constraint` collides with the generic constraint used throughout the repository (joint constraint, pose constraint) |
| closure / closed loop | 闭环（通用） | `closed chain` | "Chain" is taken by the IK chain (`IkChain`, `links`); a closure is not a closed chain but a positional closure imposed on two tips |
| pose | 姿态 | `posture`, `stance`, `gesture` | posture and stance describe a person's position, which is not the same concept as an animation pose; the code type names are `Pose`, `PoseDriver`, `ModelPosing` |
| pipeline | 管线 | `conduit`, `duct`, `line` | conduit and duct are physical pipes |
| mount | 挂载 | `installation`, `loading`, `equip` | Install means installing a mod or a JDK; a mount is a runtime registry entry (`mount()` / `add` / `remove`). Equipment attachment is a separate concept |
| culling | 剔除 | `cropping`, `clipping`, `cutting` | clipping means clip space and clip planes, not frustum culling |
| frustum | 视锥 | `visual cone`, `view cone` | The fixed term is view frustum |
| `opaque` / `mask` / `blend`, `translucent` for stages | 半透明 / 透明 | `transparent` for everything | Materials are split into `OPAQUE` / `MASK` / `BLEND`, and there is a separate vanilla `translucent` render stage (`AFTER_TRANSLUCENT_BLOCKS`); "translucent replay" is translucent replay. Writing `transparent` everywhere erases the three-way split |
| lighting | 光照 | `light`, `shadow` | The MC light system (light engine) |
| shader pack | 光影 | `lighting`, `light and shadow` | An Iris shader pack (each camera view has its own `ShaderPack`, `PipelineManager` and material settings) |
| impulse | 冲量 | `momentum` | Momentum is a different quantity; the code entry point is `applyImpulse` |
| force / torque | 力 / 力矩 | `strength` | Code entry points `applyForce` / `applyTorque` |
| writeback | 写回 | `feedback`, `write into` | `feedback` is used in this repository only for framebuffer feedback |
| evaluate / evaluation | 求值 | `compute`, `calculation` | `compute` names the formula module's arithmetic engine |
| parse / decode / solve | 解析 / 解码 / 求解 | mixing the three | parse handles fields and JSON, decode turns them into objects (`Decoded` / `decodeFile`), solve handles IK and physics. Three independent mechanisms with their own code names |
| bucket | 桶 | `basket`, `pail`, `container` | Code `clearBucket()` |
| domain | 域 | `area`, `region`, `field` | Code `Diagnostics.Domain`; `region` is taken by the bone region (`PartBuilder.region`) |
| manifest | 清单 | `list`, `inventory` | Code `resolveIndexManifests`, `IndexManifest` |
| fixture | 夹具 | `clamp`, `jig` | In maths and graphics clamp is a clamping operation; borrowing it for a test fixture creates ambiguity |
| variant | 变体 | `version`, `edition` | version and revision mean a resource version (`long revision`) and are part of the cache key |
| skeleton | 骨架 | `framework` | `framework` means the rendering framework here (`uml.framework`, the UML rendering framework) |
