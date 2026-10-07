# Anchor 与 Minecraft 装备挂接

原理见 [模型子系统](../doc/model-subsystems.md)。核心 Anchor 只依赖通用骨骼和 Transform，
Minecraft 物品接入位于 `lib.kasuga.rendering.models.mc.api.McModelEquipment`。

## 两类附件

工具、武器、帽子、饰品等整体运动的对象使用 Anchor。需要手臂、腿部或身体多个骨骼共同变形的
服装使用 [Model Assembly](model-assembly.md)，并保留其独立骨骼、morph 和次级物理。
穿搭管理可以统一使用逻辑槽位，但服装几何仍按蒙皮权重绑定；单个锚点不会自动重定向服装骨架或拟合体型。
组装会重映射并保留来源 Anchor；身体名称保留，组件名称加 part 前缀。

## 定义和读取 Anchor

```java
Skeleton skeleton = model.getSkeleton();
skeleton.defineAnchor("right_grip", skeleton.getBoneMap().get("right_hand"),
        new Transform().translate(0, 0.05f, 0));
skeleton.defineAnchor("left_grip", skeleton.getBoneMap().get("left_hand"), new Transform());
skeleton.defineAnchor("head", skeleton.getBoneMap().get("head"), new Transform());
```

`Skeleton.defineAnchor(String name, Bone bone, Transform localOffset)` 返回 Anchor，将骨骼局部 offset
转换为模型绑定空间，使用该骨的权重 1；复制 offset。名称须非空且唯一，bone 必须属于该 Skeleton。
在资源构造阶段定义，创建实例和组装缓存之前完成；修改共享资源后需要更新 revision/使缓存失效。
原 `Anchor(name, BoneBinding, Transform authoredBindPose, AnchorData)` 仍支持多骨的线性加权。

求值采用 `currentBone * inverseBind * authoredAnchor`，与生产蒙皮路径一致。

| 接口 | 用途 |
| --- | --- |
| `ModelInstance.anchorTransform(String name)` | 旧的 float 世界变换，缺少 Anchor 时返回 null |
| `SkeletonInstance.anchorTransformRelative(String name, Vector3d origin)` | 相对于指定 double 原点的变换，适合相机空间渲染 |
| `ModelInstance.attachToAnchor(String name, AnchorModule.Attachment callback)` | 最终 tick 姿态后发布变换，无法解析时传 null |
| `ModelInstance.detachFromAnchor(String name, AnchorModule.Attachment callback)` | 使用原 callback 对象解绑 |
| `McModelHandle.attachToAnchor / detachFromAnchor(String, BiConsumer<String, Transform>)` | MC 句柄适配，使用原 receiver 对象解绑 |

同一 callback 可挂多个 Anchor，同一 Anchor 的重复绑定合并；允许在回调中解绑。
旧回调接口在模型卸载、重载或切换后释放，通过 `onInstanceChanged` 为新实例重新配置。
精度敏感的渲染使用 relative 接口，先在 double 中减去原点，再转换为 float；无需先取得 float 世界位置。

## McModelEquipment

```java
McModelEquipment equipment = new McModelEquipment(handle, () -> entity)
        .bindHands("right_grip", "left_grip")
        .bindHeadItem("head", new Transform());

// 在模型当前帧的最终姿态已经求值后，使用相机相对的世界 PoseStack。
int count = equipment.render(poseStack, bufferSource, camera.getPosition(), packedLight, packedOverlay);

equipment.unbind(EquipmentSlot.OFFHAND);
equipment.close();
```

`bindHands` 读取实体当前主/副手 ItemStack，并按实体惯用手选择物理手锚点及
`THIRD_PERSON_LEFT_HAND / THIRD_PERSON_RIGHT_HAND` 显示变换。可传左右两个 Transform，
调整工具握持位置、旋转与单位；绑定时复制它们。
`bindHeadItem` 使用 HEAD 显示上下文，适合南瓜等头戴物品。
原生物品 renderer 处理物品模型、显示上下文、颜色与附魔效果，使用传入的光照和 overlay。

```java
equipment.bind(EquipmentSlot.MAINHAND, "right_grip", () -> weapon,
        ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false, localOffset);

equipment.bind(EquipmentSlot.CHEST, "torso", () -> entity.getItemBySlot(EquipmentSlot.CHEST),
        ItemDisplayContext.FIXED, false, localOffset, context -> {
            customArmorRenderer.render(context);
        });
```

`bind(EquipmentSlot, String anchor, Supplier<ItemStack>, ItemDisplayContext, boolean leftHand, Transform offset)`
使用原生 ItemRenderer；附加 `Renderer` 参数可接入自定义装备、盔甲层或其他显示对象。
`Renderer.render(Context)` 的 Context 包含 slot、stack、displayContext、leftHand、entity、poseStack、
buffers、level、light、overlay、seed。ItemStack 在本帧借用，不由挂接器修改。
胸甲、护腿等贴身盔甲的 humanoid armor layer/蒙皮适配由自定义 renderer 或组装入口提供，
原生物品 renderer 本身不会把物品图标变为贴身盔甲。

每 slot 的新绑定替换旧绑定；空 ItemStack、缺少 Anchor、未挂载/不可见的模型跳过。
名称绑定每次渲染查询当前实例，资源重载和换装后自动使用新 Skeleton；不保存旧 ModelInstance。
close 释放绑定和实体供应器，不关闭模型、物品 renderer 或 buffers。

调用在渲染线程执行，PoseStack 必须处于相机相对世界空间，不能额外重复实体平移。
挂接器 push/pop 每件物品的矩阵，渲染异常时也恢复 PoseStack；不推进 tick、动画或物理时间。
先完成共享的动画/IK/物理姿态求值，再画模型和装备，避免多机位重复推进模拟。

## 验证入口

```sh
./gradlew :modules:modelling:modelUnitTest
./gradlew :modules:modelling:runClientHeadless -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=fixture -PkasugaTestAnchorEquipment=true
```

成功标识为 `ANCHOR_EQUIPMENT_SMOKE_PASS`，报告在 `debug/anchor-equipment.json`。
客户端测试使用无几何的中性骨架和原生剑、镐、南瓜，验证绘制、惯用手切换和锚点运动；不展示用户素体。
