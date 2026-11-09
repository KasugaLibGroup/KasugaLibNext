# Cobalt blue

在启用 `contentTesting` 的开发客户端、私有单人世界中执行：

```text
/kasuga_cobalt_blue
```

也可使用 `/ksglib demo cobalt_blue`。进入时保持黑屏，场景区块、OBJ pipeline 和可见网格全部准备完成后，才以 1.4 秒淡入 33 × 41 格、净高 13 格的白色混凝土房间。

- WASD 行走、鼠标转动视角。
- 对准旁边的唱片，按住右键拾取；滚轮调整悬持距离，走近并将其移到唱片机上方自动入槽。
- 唱片缓慢进入后，唱片机前出现细圆框 `F` 和 `Play`；按 F 播放。
- 播放时显示左下角播放器，音乐从唱片机位置发出。墙、顶棚和地板依次翻落，露出整体海岸。
- 终幕保持玩家初始朝向 `+Z`：背后 `-Z` 是连续沙滩，前方越过不规则岸线进入钴蓝海面；三组不同频率叠加的岸线让水际在横向产生明显的进退。
- 可见沙滩、湿沙、海床与远处沙丘来自一个 97 × 101 格的 `kasuga_demo:models/cobalt/beach.obj`，不再用沙块或蓝色混凝土表现地面。`BARRIER` 只提供不可见碰撞，干沙碰撞面与海床碰撞面决定能否涉水。
- 水面继续使用现有 `WaterEffects` 后处理，在不规则沙坡与水面相交处产生非直线水际；房间及展开阶段不渲染水面、不产生脚部水波。
- 当前批次只完成整体海岸和演出方位。电话亭、电视、自行车、路牌和角色等叙事道具留到后续资产批次。
- Backspace 或 `/kasuga_cobalt_blue stop` 退出并返回原世界。

场景组合使用 KasugaLib 内置 `MinecraftStage`、`SceneWarmup`、`SceneProps`、`SceneControls`、`SceneOverlay`、`SpatialAudio`、`WaterEffects`、`CloudEffects` 和 `AnimationTimeline`。水面是带深度与水平动量的浅水有限体积模拟，包含脚部排水、动量传递、折射、吸收与反射；该模型不包含翻卷浪和飞溅流体。

海岸 OBJ/MTL/贴图是可重复生成的确定性资产：

```sh
python3 scripts/generate-cobalt-beach.py
```

生成器同时校验有限顶点、岸线起伏和贴图尺寸。JVM 资产契约检查：

```sh
./gradlew :modules:modelling:test --tests test.kasuga.modelling.demo.cobalt.CobaltBeachAssetTest
```

选用音乐为「あよ - インスタントブルー」，展示信息来自 `assets/kasuga_demo/cobalt_blue/track.json`。音频转换为单声道 Ogg Vorbis 以支持位置声源。用户录音和生成的 Ogg 不纳入 Git；重新准备本地音频时，在仓库根目录执行：

```sh
python3 scripts/cobalt-blue.py --music '/Users/glowingstone/Music/网易云音乐/あよ - インスタントブルー.flac'
```

自动客户端验收（每次使用新目录；不要再同时设置 Quick Play 世界）：

```sh
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaCobaltAuto=true \
  -PkasugaClientDirectory=build/cobalt-blue-smoke
```

验收以真实物理拾取、槽位检测和游戏移动输入推进，保存岸线总览与入水两张终幕 framebuffer。成功日志为 `COBALT_BLUE_SMOKE_PASS`，并要求 `beachInstancesAfterStop=0`；图片位于对应游戏目录的 `screenshots/kasuga_cobalt_blue/`。自动化检查模型生命周期、声音活动和空间声源属性；耳机方向感、水面艺术效果与完整操控手感仍需人工验收。

若复用已有的实验性测试世界，应先在可见客户端确认 Minecraft 的实验性设置提示；隐藏客户端会等待该确认页。
