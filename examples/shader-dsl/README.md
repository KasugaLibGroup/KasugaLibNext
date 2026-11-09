# Shader DSL 演示：Prism Scan

一个完整的 **脚本 → 类型化 IR → GLSL / Minecraft JSON → 游戏 GPU 画面 → MP4** 演示。

三份源码构造同一个 `showcase:prism_scan` 程序，并在真实执行后比较 IR 和全部资源的字节。

| 源码 | 接入方式 |
| --- | --- |
| [JavaScript](prism-scan.js) | 项目内置的可选 GraalJS 前端 `GraalShaderCompiler` |
| [Kotlin](prism-scan.main.kts) | `kotlinc -script` 直接调用类型化 JVM DSL |
| [Python](prism-scan.py) | JPype 在本地 JVM 中调用相同 API；这是受信任宿主桥接示例，不是项目内置的 Python 引擎或沙箱 |

效果使用类型化向量运算、纹理采样、可暴露的 `Strength` 参数、由 `Time` 驱动的折射和彩色环、GPU `forRange` 循环和 `ifThenElse` 扫描分界。示例没有使用 raw GLSL。更完整的 API 见 [DSL 参考](../../doc/reference/shader-dsl.md)。

## 一次生成全部演示

需要 JDK 21+、Python 3.11+、`kotlinc`、`ffmpeg` 和 `ffprobe`，以及一个现有的 Minecraft 1.21.1 测试世界。启动脚本在仓库 `build/` 内创建独立 Python venv，并安装固定版本的演示工具依赖。

```bash
./scripts/run-shader-showcase.sh \
  --world /absolute/path/to/test-world \
  --java-home /absolute/path/to/jdk-21
```

原世界会复制到输出目录下的独立客户端，演示只修改副本的时间、天气和玩家视角。默认输出到仓库根目录的 `build/shader-showcase/`，可用 `--output /absolute/path` 修改。`--fps` 默认 30，`--seconds` 默认 24，限定录制 16–120 秒。视频前半段展示源码与产物，后半段完整播放游戏采集，因此成片时长为游戏录制的两倍。视频无音轨。

macOS 使用项目已有的隐藏窗口 OpenGL 后端，仍需正常图形会话。Linux 使用现有 EGL 启动器；显卡驱动与 patched GLFW 的要求见 [无头运行脚本](../../scripts/run-headless-client.sh)。本示例不会给 modelling 的普通运行或发布依赖增加 Graal 运行时。

## 输出内容

```text
build/shader-showcase/
├── shader-dsl-showcase.mp4       # 1920×1080 H.264/yuv420p，默认 48 秒
├── minecraft.mp4                 # 24 秒真实客户端输出，分辨率以报告为准
├── poster.png                    # 游戏效果预览
├── contact-sheet.png             # 源码/产物/游戏关键帧
├── javascript/                   # 下面的四个文件
│   ├── program.ir.txt            # 真实 ShaderModule / ShaderIr 结构
│   └── assets/showcase/shaders/core/
│       ├── prism_scan.vsh
│       ├── prism_scan.fsh
│       └── prism_scan.json
├── kotlin/                       # 相同的 IR 与资源
├── python/                       # 相同的 IR 与资源
├── client-generated/             # 客户端本次从 JS 生成的实际资源
├── language-report.json          # 三语言字节比对与 SHA-256
├── capture-report.json           # GPU/像素/alpha/帧数/资源清理结果
├── video-report.json             # MP4 编码/帧数/全片解码结果
└── previews/                     # 9 张视频关键帧
```

`program.ir.txt` 是类型化中间表示的可读结构转储；`.vsh/.fsh/.json` 是后端真正生成的 Minecraft 资源，不应把 DSL 源码和最终 GLSL 混称为同一层产物。

## 分步运行

只编译 JavaScript 和导出其 IR/资源，不需要启动 Minecraft：

```bash
./gradlew :modules:shader-graal:exportShowcase
```

Kotlin/Python 所需的完整 JVM classpath 会写到输出目录的 `jvm-classpath.txt`。完整启动器会直接执行两份源码，并调用共用的 `ShowcaseExport.write` 导出。

已有成功采集时，只重新合成视频：

```bash
build/shader-showcase-tools/bin/python scripts/shader-showcase.py \
  --skip-compile --skip-capture
```

仅重录游戏：

```bash
./scripts/run-shader-showcase.sh --skip-compile --skip-compose \
  --world /absolute/path/to/test-world
```

也可以直接使用真实客户端入口（先在该隔离目录准备好世界）：

```bash
./scripts/run-headless-client.sh \
  -PkasugaShaderShowcase=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client \
  -PkasugaQuickPlayWorld=showcase-world \
  -PkasugaShowcaseOutput=/absolute/path/to/output \
  -PkasugaShowcaseFrames=720 \
  -PkasugaShowcaseFps=30
```

## 验证内容

运行器只有在 `language-report.json` 与 `capture-report.json` 的 `passed` 均为 `true` 时才合成视频。真实客户端检查：

- `Strength=0` 时，世界 RGB 与原图的最大通道误差不超过 1。
- 动态效果开启时，至少 10% 的世界像素有显著 RGB 变化。
- 世界全画面 alpha 不变；另用 32×16、alpha≈0.5 的探针检查半透明输出。
- GPU program ID、generation 在录制与调参期间保持不变，OpenGL 无错误。
- 每个输出帧只录制一次，`Time=frame/FPS`；正常和失败路径均释放注册、输出订阅与编码器。
- FFmpeg 成功退出，ffprobe 核对帧数、H.264/yuv420p，且最终视频可全片解码。

中间的字体与画面观感还需查看 `previews/`；数值验证不能代替视觉验收。验证范围以本次输出报告为准，不能外推至其他 GPU 或启用第三方 shader pack 的环境。
