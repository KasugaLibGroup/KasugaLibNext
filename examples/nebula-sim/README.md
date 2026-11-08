# Nebula Sim

本示例已迁入 `contentTesting` 的 `test.kasuga.modelling.demo.nebula` 包。完整的管线、资源归属、材质与时钟接入说明见 [Nebula demo 指南](../../doc/guides/nebula-demo.md)。

开发客户端的动画演示：末地高空中的暗色三维星云、少量内部星点、分叉闪电，以及星际迷航（Star Trek）风格的星际舰队（Starfleet）星舰曲速驱动序列。
冷色背光与体积自遮挡显出前后云层，闪电短暂照亮附近的尘埃。
固定在云中的冷光核心透过曲折裂隙显露，近景云壁遮挡远光，形成近壁、飞行物体、远处光核的纵深构图。
一艘由独立 OBJ/MTL 资产构建的 Constitution 风格星际舰队星舰（大直径分层船碟、阶梯舰桥、梯形连接颈、长工程舰体、宽后掠挂架与双曲速引擎舱）沿曲线穿行，镜头从斜后上方连续掠拍，并随曲速阶段侧绕、后掠与侧倾；前方云气压缩，后方出现逐渐扩散、回填的尾流，部分闪电会落到星舰附近。
星舰缩放为 90%，相机基准距离为 2.25 个局部单位、基准仰角 14°，以 66° 巡航镜头近距离观察；船尾约 20.7 秒航迹在航道层状云幕中推开排空沟槽，两侧形成向外扩散、双涡翻卷并逐渐破碎消散的湍流云脊，近景世界尘埃随相机产生掠过与短拖影。
演示画面不绘制左上角控制面板；频率仍保持默认 `0.25 Hz`，由仿真内部维护。

在仓库根目录运行（JDK 21，Python 环境安装 `nbtlib==2.0.4`）：

```bash
python3 scripts/nebula-sim.py --world '/path/to/test-world'
```

脚本将存档复制到 `build/nebula-sim/client/saves/nebula-sim`，不修改源存档。
首次运行未指定 `--world` 时使用本仓库已有的 `modules/modelling/run/client/saves/New World` 开发存档。
进入后自动传送至末地 `(0, 512, 0)`。星云实际范围约 `1344 × 768 × 960` 格，中心位于 `(0, 513.62, 576)`。
演示视图使用 Kasuga 跟随相机，从星舰的低角度斜后方近距离观察千格尺度的云墙。

- 频率：默认 `0.25 Hz`，不显示调频控件。零频率仍允许已有闪电自然衰减，内部 setter 和仿真逻辑保持不变。
- 每次闪电包含细分主干、分叉和多重回击，约一秒内衰减；频率按整次放电计数。
- 放电同时改变整片体积云的环境散射，局部点光仍保留；暗部和小尺度受光保持差异。
- 按住鼠标右键拖动：调整第三人称跟随视角。
- `Space` 空格键：在巡航期间按下立即激活曲速驱动，切入蓄能阶段。
- `Esc`：退出演示，恢复原维度、位置、视角、游戏模式、飞行与 HUD 状态。
- 手动启动或重启：`/kasuga_nebula`；手动停止：`/kasuga_nebula stop`；手动进入曲速：`/kasuga_nebula warp`。

仅支持未开放 LAN 的单人集成服务器。没有实体、真实雷击、声音或地形编辑。
退出游戏时也会在玩家保存之前恢复原状态。异常终止进程不经过正常退出回调。
本示例位于 `contentTesting`，不会包含在正式库的 jar 中。

### 曲速驱动与渲染实现

仿真由公开的 `AnimationTimeline` 以每秒 20 tick 推进；云流动、星舰轨迹、尾流、相机、闪电与曲速序列在同一时钟上插值。
跟随位姿由 `CameraFollowSettings` / `CameraTarget` / `CameraProjection` 生成。巡航时相机缓慢侧绕、升降并改变距离；蓄能时推近至偏侧后方，跃迁时连续甩回更靠近航向轴的追拍位置，配合广角拉伸（约 90° FOV，样条允许轻微惯性超调）与微幅高频引擎震颤，脱出时逐渐回稳。阶段运镜使用共享节点速度和加速度的五次 Hermite 样条，中间节点连续通过；升降、推拉、侧倾与视场角分别延后 0.05–0.19 秒响应，叠加幅度有限的瞄准偏移，右键拖动仍可调整观察方向。
整段展示采用“开头近、结尾远”：飞行时间10.5–15秒间以五次平滑曲线将基准距离从2.25拉至7.2个局部单位，略微升高镜头并打开视场；15秒后保持远景，减弱跟拍侧绕与侧倾，并关闭近景用的拉伸补偿推拉，避免后续曲速再次大幅抽动。此拉远独立于可手动跳转的曲速周期，不会在15秒回绕时切回近景。展示重启时才重新从近景开始。
巡航漂移只由飞行时间驱动，阶段偏移只由曲速时间驱动，手动按空格不会重置巡航镜头；允许手动触发的时间窗内阶段相机偏移为零。相机直接解析采样，不累计逐帧弹簧或历史状态，暂停和固定帧导出保持一致。
射线的逆视图矩阵与 CPU 星点/尘埃剔除使用同一相机姿态，保留实际 yaw、pitch、roll、瞄准偏移和引擎震颤；不额外渲染一份世界。
星舰使用 `assets/kasuga_demo/models/constitution_starship.obj` 与配套 `.mtl`，共 6,564 个三角面。渲染线程一次性解析、上传，并以与体积云完全相同的相机和投影光栅化到颜色、世界法线、PBR 参数与 24 位深度缓冲；体积 pass 从深度重建命中距离，再继续执行原有云体遮光、环境光、雾积分和曲速合成。双引擎轴保持 `x=±0.255, y=0.115`，尾部喷口平面保持 `z=-0.56`。
OBJ 为每个面提供可重复的投影 UV；MTL 使用 `map_Kd`、`map_Bump`、`map_Pr`、`map_Ke` 声明四个材质槽。底色现为内建 imagegen 生成的 `constitution_starship_imagegen_basecolor.png`（1254×1254），包含错缝装甲、维护盖板与轻微加工磨损；运行时以 sRGB 解码、镜像重复和 mipmap 采样，MTL 与游戏中的材质槽共同引用该图。原512×512底色保留作对照，法线/Metallic-Roughness-AO/Emissive数据图仍由生成脚本提供，法线仅保留轻微微表面影响，避免旧网格法线覆盖新装甲分区。重新生成OBJ不会覆写imagegen资产。生成提示词见本目录 `imagegen-material-prompt.txt`；未声称数据贴图也由imagegen生成。

材质与介质 shader 分开：`nebula_starship.frag`输出OBJ的颜色、世界法线和材质数据；`include/nebula_hull_lighting.glsl`负责金属/非金属的GGX与Schlick直接反射及云场间接光；`include/nebula_warp_medium.glsl`只生成非自发光烟尘密度，由体积pass沿真实射线进行消光/散射，船体深度截断前景雾。间接光通过三个半球方向的近/远云密度与受光探针估计漫反射辐照，考虑材质AO及遮挡，并加入少量粗糙环境反射；这是低成本场采样近似GI，不是完整多跳光线追踪或路径追踪。

船体使用与体积云相同的核心位置、冷暖色调和云体遮光场，保留深色粗糙材质与克制自发光。相机距离随船体拉伸连续后撤，避免近景裁切；命中船体的前景雾沿原始光学射线积分，避免以弯曲射线和未弯曲命中距离混用造成附着边缘。尘埃仍固定在世界坐标中，单元间距缩至1.25以增加船体之前的近景视差。
星舰细节包括：圆形主船碟的抬升上甲板、窄边缘带与两级下沉底壳，分层舰桥/B/C 甲板，后掠连接颈与背脊整流；工程舰体具有圆滑主壳、独立尾锥、后部机库凹口和导航偏导盘；左右挂架各由两根细后掠撑杆与引擎根部整流组成；双曲速舱具有前收集器颈环、纵向背脊、精确封口的尾端和 19 段暗色肋条分隔的蓝色线圈。表面使用大片 Aztec 分区、同心甲板缝、舷窗带和粗糙金属颗粒，红橙收集器与琥珀向青蓝过渡的偏导盘只作局部点缀。规范包络、引擎轴和 CPU 粒子喷口由纯 JVM 契约测试共同守卫；引擎粒子仍保持短、细和限幅亮度，避免长串白色亮斑盖住船体。
曲速驱动由 15 秒共享时钟周期驱动：
1. 0.0 .. 5.2s 亚光速巡航破云，双引擎喷射 32 枚短小晶莹的离散等离子火花；
2. 5.2 .. 8.2s 曲速蓄能，偏导盘变色，相机 Dolly-Zoom 轻度推近；
3. 8.0 .. 9.0s 标志性“橡皮筋”纵向拉伸、Alcubierre 空间度规透镜折射弯曲、相对论光行差、短促舰首闪点与扰动烟尘密度的压力波；
4. 8.95 .. 12.2s 高速曲速巡航，分层翻卷烟雾与短粉尘沿航向掠过、少量稀疏短星流、低亮度双引擎离散粒子，以及单调积分无倒流的星云高速飞掠；移除了大范围加色发光隧道与亮环；
5. 12.2 .. 15.0s 脱出减速压力波扰动周围烟尘，阶段视场角平滑回落至66°；整段收尾镜头叠加广角展开至约74°，保持远景衔接后续周期。

尾流使用固定24个船尾历史位置、相邻0.9秒，在CPU侧计算船首与三段尾流子包围盒供射线与采样点精确裁剪，在尾流横截面正交基上施加向外推移与对称双涡域扭曲，排空中心云气并翻卷两侧压缩云脊，随年龄扩张并在约20秒内逐渐回填消散，不使用直线发光尾迹。
云团使用预烘焙无缝周期 `32³` Worley 细胞距离/法线图集（`272×136`，每张切片带1像素周期包裹边框）与常驻缓存的 `64×64` 双切片三维值噪声纹理进行两种尺度侵蚀与受光，粗略云阴影采用低阶密度场；曲速附加烟尘使用两个尺度的平流噪声与旋卷域扭曲，在飞行参照系中形成疏密结构，并与原云层一同积分；近景侵蚀与高频噪声随距离与透射率门控以兼顾清晰度与高帧率。
云峡密度与光核均固定在云坐标中。前方压缩弧和尾流边缘增强散射，最终使用 filmic HDR 映射保留亮暗层次。
渲染通过 `RenderShaderDescriptor` 与三阶段 `PostProcessGraph` 实现：星舰几何写入 graph 管理的三张 `RGBA16F` 附件和深度，体积 pass 读取 G-buffer 并合成到受管颜色目标，最后由 Kasuga `copyColor` 写入 `MAIN`。每帧动态尾流、闪电线段、双引擎最多 64 枚粒子及剔除后的星点、尘埃，通过 `TBOBuffer` 上传 384 个 `vec4`（6144 字节），由 `samplerBuffer` 在纹理单元 7 读取；不再分配旧的 `64×6` 二维数据纹理。体积仍以完整分辨率结合紧致椭球边界、带边界回退的空域跳步与72段近景混合步进积分，原生 GUI 在其后绘制。
阴影每四次有效采样更新，并在进入云体时重新求取；停止或断开连接时自动在渲染线程释放自定义纹理。
仅演示启用期间替换末地背景，保持无地形的暗色观测空间。通用能力补在公共 API：MRT target 描述与采样、fullscreen TBO sampler、OBJ/MTL 的静态顶点视图和 PBR 指令、GPU 纹理封装、相机矩阵与五次 Hermite easing。示例通过标准 `AnimationTimeline`、`CameraAnimationClip.Track`、`CameraAnimationPlayer` 和 `StateMachine` 组织时间、镜头与曲速阶段，保留程序化跟随和体积 shader 的场景逻辑。

验证与录制（需 `ffmpeg` 在 PATH）：

```bash
./gradlew :modules:modelling:modelUnitTest --tests 'test.kasuga.modelling.demo.nebula.*'
python3 scripts/nebula-sim.py --test --record-seconds 15
```

产物：`build/nebula-sim/nebula-sim.mp4`、PNG 截图与 `report.json`。
客户端 smoke 读取实际 RGBA，并检查左上角控件隐藏、资源重载、缩放、视角变化、曲速拉伸与曲速巡航阶段差分、退出/重启/断线清理以及保存后的玩家状态。另在冻结飞船与环境时间时，仅改变镜头时间，验证自动运镜带来真实视差，并读取 GPU `InverseView` 验证实际姿态与解析结果一致。专用 `CraftDebug` 探针输出实际 OBJ 深度遮罩与隔离 PBR 材质，用船体区域的覆盖率、亮度和高光比例验收占比与曝光，避免全画面统计掩盖局部问题。新增生成底色开关、漫反射GI近似开关及烟尘开关的冻结场景A/B，分别验证船体实际采样、暗部间接受光和烟雾体积变化。
录制阶段由可配置帧率的唯一动画时钟驱动，每帧按 `1 / FPS` 秒推进仿真，不因离线渲染耗时改变飞行或闪电速度。默认仍为 30 FPS；可用以下命令输出 15 秒 4K/60 FPS 版本（高分辨率渲染允许超过开发机 GPU 门禁）：

```bash
python3 scripts/nebula-sim.py --test --record-seconds 15 --width 3840 --height 2160 --fps 60 \
  --allow-slow-render --timeout-seconds 1800 --output build/nebula-4k60
```

命令行会实时输出 `NEBULA_RENDER_PROGRESS 0%..100%`。普通交互演示仍由 20Hz 客户端 tick 驱动；导出帧率表示视频时间基准，不保证实时渲染可达该帧率。
已验证环境：Apple M3 Max、Java 21、Minecraft 1.21.1、NeoForge 21.1.203、Iris/Sodium 已安装且未开启外部光影包。
其他 GPU、启用外部光影包的结果尚未验证。

字体随资源包提供，许可见 `modules/modelling/src/contentTesting/resources/assets/kasuga_demo/font/OFL-JetBrainsMono.txt`。

当前展示段在自动检查结束后重置到云峡入口，再开始固定帧采集，避免测试耗时改变演示的起始位置。

2026-11-08 近景验收产物位于 `build/nebula-closeup/`（使用 `--output build/nebula-closeup`）：27 项 Nebula JVM 测试、真实客户端 smoke、450 帧 / 15 秒固定步录像通过。巡航冻结视角船体遮罩为14,302像素（画面1.552%，包围矩形283×120像素）；曲速为3,750像素（0.407%）。船体局部高光比例均为0；关闭星云核心后，隔离船体平均亮度由32.09降至25.00，3,073个实际船体像素产生可见变化。已审查逐秒及跃迁每0.25秒抽帧；独立人工操作验收仍由使用者进行。

`nebula-sim.mp4` 为近景完整展示；`before-after.mp4` 左侧为上一版、右侧为近景版，使用同一固定步仿真起点。上一版录像保留在 `build/nebula-cinematic-refined/`。

2026-11-08 独立 OBJ/PBR 产物位于 `build/nebula-model-refined/`：30 项 Nebula JVM 测试零 failure/error；Java 21.0.4 真实客户端 smoke 与 450 帧 / 15 秒录像通过，OBJ 网格与 4 个 PBR 槽均已验证。巡航船体遮罩为15,137像素（1.642%，包围矩形277×126像素），曲速为4,065像素（0.441%）；隔离材质与正常场景局部高光比例均为0。曲速环境光影响3,464/4,065个船体像素，核心开启/关闭的隔离船体平均亮度为20.88/15.71。GPU“OBJ光栅化 + 体积合成”启动阶段采集20个异步样本，均值30.66 ms、最大值33.90 ms，低于45 ms门禁。`model-before-after.png` 左侧为上一版 SDF、右侧为 OBJ/PBR 版的隔离材质放大对照；`pbr-map-slots.png` 展示四张纹理槽；`model-contact-sheet.png` 为整段15秒录像逐秒抽帧。其他GPU与启用外部光影包的组合仍未验证。


2026-11-08 近景开场/远景收尾版产物位于 `build/nebula-ending-wide/`：完整15秒720p录像、`opening-shot.png` / `ending-shot.png` 和 `report.json`。实际GPU相机距离由2.24872拉至7.01372（约3.12倍）；`recordedNearToWide=true`，全部客户端检查通过。末段每0.5秒抽帧已检查。29项Nebula JVM测试通过（包括模型会话新增的包络同步测试）；此轮启动阶段GPU pass均值32.60ms，含当时模型版本，不代表整帧FPS。


2026-11-08 Imagegen贴图与烟尘曲速版本产物在 `build/nebula-textured-smoke/`：31项Nebula JVM测试、完整真实客户端smoke与450帧/15秒720p录像通过。新底色A/B改变1895/4070个实际船体像素；云场GI近似A/B改变4061/4070个船体像素，开启/关闭均值19.02/12.79；烟尘A/B改变904580个像素。局部亮度与过曝、原核心受光和初近末远门禁均保留。M3Max启动阶段OBJ+体积pass均值28.40ms（21样本），不是整帧/整段FPS。`before-after.mp4`左侧为此前OBJ/PBR版，右侧为本版；`imagegen-material.png`、`diffuse-gi-off.png`、`warp-smoke-on/off.png`与contact sheets记录实际GPU结果。生成底色与提示词路径见上文；其余平台/外部光影包组合仍未验证。


### Kasuga 管线迁移验收（2026-11-08）

代码已归入 `test.kasuga.modelling.demo.nebula`。几何/体积/呈现三阶段使用实际 graph 读写依赖、受管三附件 G-buffer 与深度；模型复用 OBJ/MTL processor，GPU 分配与释放、TBO sampler、参数块、FSM 和镜头轨道均消费 Kasuga API。旧 present shader 与示例自管 FBO 已移除。详见 [Nebula demo 指南](../../doc/guides/nebula-demo.md)。

全量 modelling JUnit XML：982 项中978项通过、0 failure/error、4项因环境前置条件跳过（Box3D缺库反例、两个NeoForge注册运行时用例、可选PMX素材）；17项独立GL回归全通过，覆盖MRT半浮点附件/深度读回、resize、即时删除、纹理上传与绑定恢复。三模块jar构建通过，正式jar不包含此demo。新增graph/loader/FSM/easing及GL断言均用故意错误实现验证过会失败，随后恢复并通过。

720p完整客户端报告在 `build/nebula-kasuga-migration/report.json`：`passed=true`，启动阶段几何+体积GPU均值33.377ms、最大39.493ms，45ms门禁保持启用。最终4K60产物在 `build/nebula-kasuga-migration-4k60-final/`：`nebula-sim.mp4` 经ffprobe确认为3840×2160、60/1 FPS、900帧、15.000秒，进度100%。`report.json` 的固定步时钟约15.000001秒，开头/结尾相机距离2.2494/7.0137；graphManagedMrt、DataSampler=7、shader/模型重载generation=2、目标/纹理/query清理、重复启停、active disconnect和玩家落盘恢复全部通过。

4K录制使用 `--allow-slow-render`；启动阶段几何+体积GPU均值238.761ms，不代表实时60FPS。smoke在最后断开前等待区块生成稳定，仍检查完整退出和保存，不绕过原有生命周期断言。`opening-shot.png`、`warp-cruise.png`、`ending-shot.png`记录实际GPU画面。当前验证范围仍限定上文列出的M3 Max与无外部光影包环境。
