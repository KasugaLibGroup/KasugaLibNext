# 多转向架列车车厢

KasugaLibNext 的 Create 集成允许一节车厢装配超过原版的两个转向架：结构中第一个转向架作为前转向架，最后一个作为后转向架，中间的全部作为"内部转向架"随车厢一起运动、渲染、保存和同步，并在弯道上按真实轨道几何贴轨。机制细节见[多转向架车厢原理](../../explanation/create/multi-bogey-carriage.md)。车体相对转向架的垂直起伏是独立系统，见[车厢垂直姿态](carriage-posture.md)。

## 前提

- 已安装 Create，且列车通过 Create 车站的装配流程组装；
- 车厢结构在装配时捕获到**至少 3 个**转向架；
- 这些转向架在车站的转向架列表中**连续且有序**（沿装配方向从前往后），中间不能夹着别的结构或缺失的转向架；
- `kasuga_lib` 的 common 配置 `enable_multi_bogey_carriage` 为 `true`。

## 配置项

编辑 `config/kasuga_lib-common.toml`：

```toml
enable_multi_bogey_carriage = true
max_bogey_offset = 1.0
derailed_on_bogey_offset_overflow = false
```

| 键 | 默认值 | 作用 |
| --- | --- | --- |
| `enable_multi_bogey_carriage` | `false` | 多转向架总开关：捕获、装配、持久化、同步、渲染与角度计算。 |
| `max_bogey_offset` | `1.0` | 内部转向架在弯道上允许的最大侧移（格），计算值会夹紧到该幅度。 |
| `derailed_on_bogey_offset_overflow` | `false` | 弯道要求的内部转向架侧移超过 `max_bogey_offset` 时，服务端判定列车出轨。 |

- 这些是 common 配置：客户端和服务端各自读取本地文件。单人游戏共享；专用服务器需要改服务器上的文件，客户端也建议保持一致（客户端负责渲染与内部转向架角度）。
- 默认值可以通过 API 修改（见"其他模组"）；配置文件中显式写入的值优先级最高。
- 修改后重启游戏（或等待配置重载）生效。

关闭 `enable_multi_bogey_carriage` 时整套逻辑回退原版：超过两个转向架的车站装配会重新抛出 `train_assembly.too_many_bogeys`；已有多转向架数据不再渲染、不再同步，也不会写回存档（再次保存时会被丢弃），重新开启后从旧存档读取时也同样按原版处理。

## 搭建与装配

1. 沿轨道搭建一节完整车厢结构，在结构里放置至少 3 个转向架方块，样式任意（可混用、可倒置）。
2. 其余车厢结构照常搭建；中间转向架占据的方块会各自成为车厢的一部分。
3. 触发车站装配。
4. 结果：只生成**一节**车厢，前转向架为第一个、后转向架为最后一个，`bogeySpacing` 覆盖首尾距离，中间转向架跟随车厢运动。

如果内部转向架与首尾不连续、顺序不符，或捕获到了车站列表外的转向架，装配会放弃合并并回到原版路径，通常表现为 `train_assembly.not_connected_in_order`。

## 曲线贴合与出轨

内部转向架在弯道上按真实轨道几何定位与定向（贝塞尔弯道、跨节点都覆盖），因此会相对车体弦线产生一个侧移，使车轮保持在轨道上：

- 侧移量随曲率变化，超过 `max_bogey_offset` 的部分被夹紧；
- 开启 `derailed_on_bogey_offset_overflow` 后，若弯道要求的侧移超过上限，服务端按 Create 的高应力出轨流程判定脱轨；
- 客户端刚进服、尚未拿到轨道图时，朝向会临时回退到首尾转向架角度插值，接到轨道图后自动切换到真实轨道采样。

## 其他模组：修改默认值

依赖该功能的模组可以在自己的 `@Mod` 构造函数或 `FMLConstructModEvent` 中调用：

```java
KasugaLibCreateApi.setMultiBogeyCarriageDefault(true);
KasugaLibCreateApi.setDerailedOnBogeyOffsetDefault(true);
```

默认值在配置加载时才求值（`ModConfigSpec.Builder#define(path, Supplier<Boolean>)`），而配置加载发生在所有 mod 构造完成之后，因此不存在时序问题。它只影响"配置缺省"的场景：新生成的配置文件、或缺少该键的旧文件；用户显式写入的值始终优先。该 API 不提供"无视用户配置强制开启"的能力。

## 行为边界

- 内部转向架不参与车厢耦合，只作为中间轮参与运动、渲染与光照；
- 其朝向从真实轨道采样（含贝塞尔弯与节点），S 弯跨节点时角度连续；
- 传送门遮挡、光照采样与车厢局部坐标一致；
- 客户端内部转向架通过独立 RPC 同步；
- 样式由转向架自身的 `bogeyData` 决定，混用不同样式、倒置放置都可以。

## 验证清单

- 开启后装配 3 个及以上转向架的结构 → 生成一节车厢，而不是拆成多节；
- 中间转向架随车滚动，在弯道/坡道上沿真实轨道转向；
- 保存并重进世界 → 中间转向架仍在；
- 后加入的玩家能看到中间转向架；
- 调小 `max_bogey_offset` 并开启 `derailed_on_bogey_offset_overflow`，过急弯 → 列车出轨；
- 把 `enable_multi_bogey_carriage` 改回 `false` 再装配同样结构 → 走原版报错路径。
