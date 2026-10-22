# 世界空间 UI 与自由光标输入

[English](world-ui-input.en.md)。

包：`lib.kasuga.rendering.models.mc.backend.ui`。所有方法在 Minecraft 客户端线程调用。
设计、坐标变换和输入所有权见 [原理](../explanation/world-ui-input.md)。

## 接入现有 Screen

```java
Minecraft mc = Minecraft.getInstance();
UIBackend backend = Constants.UI_BACKEND; // client setup 后可用
Screen panel = new MyPanelScreen();
panel.init(mc, 320, 180); // 世界 UI 的逻辑尺寸，不随输入层窗口尺寸改变

// 面板中心放在相机前 2.5 格，宽 1.92 格；局部 UI 的 +Y 向下。
var camera = mc.gameRenderer.getMainCamera();
var center = camera.getPosition().add(
        camera.getLookVector().x * 2.5,
        camera.getLookVector().y * 2.5,
        camera.getLookVector().z * 2.5);
Matrix4f localToWorld = new Matrix4f()
        .translation((float) center.x, (float) center.y, (float) center.z)
        .rotate(camera.rotation())
        .scale(.006f, -.006f, 1)
        .translate(-160, -90, 0);
backend.addRenderable(panel, localToWorld);
backend.openPointer();
```

`Constants.UI_BACKEND` 由内置 `world_ui` 管线在主视角 `AFTER_LEVEL` 渲染。
`openPointer()` 要求当前在世界内且没有其他 Screen；从按键绑定调用即可。
从聊天命令调用时先结束聊天 Screen，例如开发示例中的 `mc.setScreen(null)`。
方法返回已提交给 `Minecraft.setScreen` 的 `WorldUiInputScreen`；其他模组仍可取消 Screen opening。

输入层透明、不绘制背景、不暂停世界。原版 Screen 生命周期负责释放鼠标；Escape 关闭输入层，
回到鼠标控制视角。关闭后面板继续存在，恢复准心交互。调用 `removeRenderable(panel)` 移除面板。
注册项属于 backend，跨世界不会自动删除；世界绑定 UI 应由调用方在离开世界时移除。

正常视角下，用准心点击并聚焦 `EditBox` 或 `MultiLineEditBox` 后，backend 临时接管键盘，
字符交给文本框，游戏移动键和快捷键暂停响应；鼠标仍控制视角。Escape 清除文本焦点并退出，
本次 Escape 不打开暂停菜单。退出时仍按住的旧按键，其 repeat/release 会被消费，重新按下后恢复游戏输入。
按钮、禁用/隐藏或只读的文本框不会触发接管；离开世界、失焦、切换 Screen 或删除目标也会结束接管。

`Screen` 可正常使用其子控件的焦点、键盘和文本输入。世界面板若需透明背景，应重写自身
`renderBackground`；输入层的透明与面板自身的外观分别控制。世界渲染期间只临时关闭面板的 blur，
退出渲染后恢复其原值。

控件先绘制到独立的透明 RGBA 画布，沿用原版 GUI 的正交深度范围；之后将画布一次绘制到
世界平面，避免把文字与阴影的微小 Z 间隔压缩到世界透视深度中。画布使用当前 GUI scale
决定像素密度，窗口缩放时重建目标；局部 scissor 按画布尺寸换算。合成使用预乘 alpha，
不会再次压暗透明文字边缘，也不会将面板矩形写入世界深度。
`MIN_GUI_Z`、`MAX_GUI_Z` 及边界处的文字阴影只参与画布内部排序；合成顶点始终为局部 Z=0，
不会把面板向玩家方向推出。画布裁剪范围在 GUI 深度端点外各预留 1 单位。

## 接口与约定

| 接口 | 行为 |
| --- | --- |
| `addRenderable(Renderable)` | 默认在世界原点居中，以当前 GUI 高度作为每格像素数；重复注册不重复绘制 |
| `addRenderable(Renderable, Matrix4fc)` | 注册并复制像素局部坐标到绝对世界坐标的变换 |
| `setTransform(Renderable, Matrix4fc)` | 更新/预设变换；拒绝非有限、非仿射、不可逆矩阵 |
| `removeRenderable(Renderable)` | 停止渲染，并为该目标补发捕获中的松键事件 |
| `openPointer()` | 打开自由光标输入 Screen；无玩家、无世界或已有 Screen 时抛 `IllegalStateException` |
| `getInputRouter().setMaxDistance(float)` | 最大命中距离，默认 5 格，从渲染近裁剪面起算；必须为有限正数 |
| `worldChanged()` | 清理输入并释放临时 GPU 画布，保留面板注册；全局 backend 已接入世界切换钩子 |
| `close()` | 清理输入、画布与全部注册；自有 backend 的所有者应在销毁时调用 |
| `resetInput()` | 清理 hover、键盘焦点和输入捕获；为已按下的键/鼠标补发 release |
| `isKeyboardCaptured()` | 是否正在正常视角的文本键盘接管模式中 |
| `renderAllUis(GuiGraphics, float)` | 自定义世界渲染入口；使用当前世界投影和有效 model-view，不能在 HUD 的正交投影阶段调用 |
| `renderWorld(WorldRenderPipelineContext)` | 自有 backend 可从世界管线调用；负责投影/model-view 保存恢复，只接受主视角 |

`Screen.width/height` 与 `LayoutElement` 的位置/尺寸决定可点击范围。只有 `Renderable`、没有
`GuiEventListener` 或已知尺寸的对象只绘制。平面命中继续使用 `RayTracingHelper.intersectRayWithPlane`。
多个面板重叠时将事件交给最近的矩形；同距离按注册顺序。命中不检查方块遮挡，也不做纹理 alpha 测试。

透明层吞掉所有传入的鼠标、滚轮和键盘事件；未命中时不会触发世界操作。键盘输入交给最后点击的面板，
面板内部再分发给选中的控件。Escape 始终退出。鼠标按下被面板接受后，为该按钮捕获目标；
移出边界仍在原平面上计算局部坐标并拖拽，松键发回原目标。射线平行或转到背后时暂用最后有效坐标，
仍保证 release。窗口失焦、屏幕替换、目标移除、维度切换和断开连接都会取消捕获。

## 复用已有射线检测

若宿主已有世界命中求解器，可独立使用投影组件，不需要重写相交算法：

```java
var projection = new ScreenPointerProjection(worldProjection, guiWidth, guiHeight);
Vector3f origin = new Vector3f(), direction = new Vector3f();
if (projection.ray(mouseGuiX, mouseGuiY, origin, direction)) {
    // viewMatrix 包含相机平移时，inverseView 将输出还原为绝对世界坐标。
    inverseView.transformPosition(origin);
    inverseView.transformDirection(direction).normalize();
    existingRaycast(origin, direction);
}
```

若 view 只含相机旋转，先用逆矩阵变换，再给 origin 加上相机世界位置。
`ray` 输出从近裁剪面出发的单位射线，兼容透视和正交投影。传入 GUI 逻辑像素，左上角为 `(0,0)`；
原始 GLFW/window 光标坐标按 `guiWidth / screenWidth`、`guiHeight / screenHeight` 换算。
不要把 framebuffer 像素尺寸与 window 光标坐标混用。构造器复制投影并验证可逆性；无效投影或 viewport
抛 `IllegalArgumentException`，非有限光标返回 `false`。FOV、缩放和宽高比变化后重新采集渲染投影。

## 开发验收

```sh
./gradlew :modules:modelling:modelUnitTest --tests 'lib.kasuga.rendering.models.mc.backend.ui.*' --offline
./gradlew :modules:modelling:compileContentTestingJava --offline
./gradlew :modules:modelling:runClient
```

进入测试世界后执行 `/ksglib debug world_ui`：面板固定在开启时的相机前方，输入层立即打开。
移动光标点击面板左右两侧、在文本框中输入、滚动、按住鼠标拖出面板，再松开；检查计数和拖动增量。
Escape 后转动视角，确认准心交互仍可用；再次执行命令会重建面板并开启自由光标。
`/ksglib debug world_ui stop` 移除面板。

退出自由光标后，用准心点文本框并输入 WASD/中文：文字应变化，玩家不移动，鼠标仍能转动视角。
按住 W 时按 Escape，确认只退出编辑；松开再按 W 应恢复移动。按钮不应接管键盘。
分别执行 `/ksglib debug world_ui min_z` 和 `/ksglib debug world_ui max_z`，检查边界处文字/阴影完整，
面板距离不变；这两个预设与默认面板使用相同世界变换。

渲染验收时检查文字主体/阴影、按钮边框和输入框边框没有条纹或缺块，输入较长的中文/ASCII
文本检查裁剪；停止 demo 后确认世界、手部和 HUD 的颜色与深度正常。

窗口大小、GUI scale、FOV、第三人称和失焦切换需在真实客户端手动验收。
JVM 测试覆盖投影数值和输入路由状态，不替代游戏内渲染及 GLFW 验证。

已有隔离测试存档时，可自动检查真实键盘 Mixin、字符输入和极端 Z 的字体/控件像素：

```sh
./gradlew :modules:modelling:runClientHeadless --offline \
  -PkasugaClientDirectory=run/world-ui-smoke -PkasugaQuickPlayWorld=TestWorld \
  -PkasugaTestWorldUiInput=true
```

`TestWorld` 需位于该运行目录的 `saves/` 下。测试不显示窗口，完成后退出；JSON 和 PNG 位于
该运行目录的 `debug/world-ui-input/`。此自动化不覆盖系统输入法候选框和玩家操作手感。
