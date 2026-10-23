# UI 模型预览

`GuiModelPreview` 在 UI 内显示模型加载管线已发布的模型。模型资源地址和可选的 MMD 内部模型名沿用
`model_loader` 的注册方式。预览创建独立 `ModelInstance`、材质状态、GPU 缓冲和透明深度画布；
不会挂载到世界后端，也不会改变世界中已有实例的位置或姿态。

```java
var preview = new GuiModelPreview(
        ResourceLocation.parse("kasuga_lib:models/gltf/maribel.glb"), null);
preview.setView(-0.55f, 0.25f, 1f); // yaw / pitch：弧度，zoom：正数
```

资源未就绪时 `render` 返回 `false`，宿主可绘制加载占位。每次绘制会重新解析当前发布资源，
资产替换后释放旧预览实例并重新创建。默认相机根据模型包围盒适配视窗；放大后可以主动裁切。
需要显示代码构造的模型时可传入 `Supplier<Model>`，返回 `null` 表示暂未就绪。

| UI | 控件 | 生命周期 |
|---|---|---|
| 原版 | `ModelPreviewWidget(x, y, width, height, preview)` | 宿主 `Screen.removed()` 调用控件的 `close()` |
| LDLib2 | `ModelPreviewElement(preview)` | 元素移除时关闭；适用于普通 ModularUI 和 `LdElement` 世界面板 |
| ModernUI | `ModelPreviewView(context, modelLocation, modelName)` | 使用上游 `MinecraftSurfaceView` 的渲染回调；分离时回到 MC 渲染线程释放 |

LDLib2 和 ModernUI 的类与依赖分别位于 `external/uip/ldlib` 和 `external/uip/modernui`；
通用模型渲染代码不依赖这两个工具包。每个控件拥有自己的预览，不应把同一个预览交给多个宿主关闭。

直接绘制可调用 `preview.render(graphics, x, y, width, height, partialTick)`；另有接受 `[0,1]`
透明度的重载。绘制、实例访问及关闭必须在 MC 客户端渲染线程执行，`setView` 可从 UI 输入线程调用。
`currentFrame()` 返回借用的只读颜色帧，其纹理和 FBO 只在下次绘制、尺寸变化或关闭之前有效，不应删除。
`instance()` 可供调用方设置预览实例姿态；预览本身不推进世界物理或全局模型调度。

原生预览材质路径包含深度、蒙皮和模型纹理，独立于 Iris 的世界 pass。Windows/Linux/Iris 尚未实测；
目前验证环境为 macOS arm64、MC 1.21.1、NeoForge 21.1.203、LDLib2 2.2.1、ModernUI 3.13.0.1。

## 开发演示

```sh
# 原版 / LDLib2 二维预览
./gradlew :external:uip:ldlib:runClientModelDemo -PkasugaModelToolkit=vanilla
./gradlew :external:uip:ldlib:runClientModelDemo -PkasugaModelToolkit=ldlib

# 世界面板：存档应位于该模块 run/model-demo/saves 下
./gradlew :external:uip:ldlib:runClientModelDemo -PkasugaModelToolkit=world -PkasugaQuickPlayWorld=YourTestWorld

# ModernUI Fragment
./gradlew :external:uip:modernui:runClientModelDemo
```

添加 `-PkasugaModelCapture=/absolute/path/model.png` 会运行自动交互/像素检查并退出，旁边的
`model-report.json` 需检查 `passed`。原版/LDLib2 检查实际模型像素、旋转、GUI 尺寸变化和画布释放；
ModernUI 检查真实渲染回调及分离后的清理。开发演示不进入发布 jar。
