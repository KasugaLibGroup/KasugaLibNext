# Anchor and Minecraft equipment attachment

For the principles see [model subsystems](../explanation/model-subsystems.md). The core Anchor depends only on the generic skeleton and Transform,
and the Minecraft item integration lives in `lib.kasuga.rendering.models.mc.api.McModelEquipment`.

## Two kinds of attachment

Objects that move as a whole, such as tools, weapons, hats and accessories, use an Anchor. Clothing that needs several bones of the arm, leg or body to deform together
uses [Model Assembly](model-assembly.md) and keeps its own skeleton, morph and secondary motion.
Outfit management can use logical slots uniformly, but clothing geometry is still bound by skin weights; a single anchor does not automatically retarget a clothing skeleton or fit the body.
Assembly remaps and preserves the source Anchors; body names are kept, and part names get a part prefix.

## Defining and reading an Anchor

```java
Skeleton skeleton = model.getSkeleton();
skeleton.defineAnchor("right_grip", skeleton.getBoneMap().get("right_hand"),
        new Transform().translate(0, 0.05f, 0));
skeleton.defineAnchor("left_grip", skeleton.getBoneMap().get("left_hand"), new Transform());
skeleton.defineAnchor("head", skeleton.getBoneMap().get("head"), new Transform());
```

`Skeleton.defineAnchor(String name, Bone bone, Transform localOffset)` returns an Anchor, converting the bone-local offset
into the model bind space with weight 1 for that bone, and copies the offset. The name must be non-empty and unique, and bone must belong to that Skeleton.
Define anchors during resource construction, before instances are created and assembly caches are built; after modifying a shared resource you have to update the revision/invalidate the cache.
The original `Anchor(name, BoneBinding, Transform authoredBindPose, AnchorData)` still supports linear weighting over several bones.

Evaluation uses `currentBone * inverseBind * authoredAnchor`, consistent with the production skinning path.

| Interface | Purpose |
| --- | --- |
| `ModelInstance.anchorTransform(String name)` | The old float world transform; returns null when the Anchor is missing |
| `SkeletonInstance.anchorTransformRelative(String name, Vector3d origin)` | Transform relative to the given double origin, suited to camera-space rendering |
| `ModelInstance.attachToAnchor(String name, AnchorModule.Attachment callback)` | Publishes the transform after the final tick pose; passes null when it cannot be resolved |
| `ModelInstance.detachFromAnchor(String name, AnchorModule.Attachment callback)` | Unbinds using the original callback object |
| `McModelHandle.attachToAnchor / detachFromAnchor(String, BiConsumer<String, Transform>)` | MC handle adapter; unbinds using the original receiver object |

The same callback can be attached to several Anchors, and duplicate bindings to the same Anchor are merged; unbinding inside the callback is allowed.
Old callback interfaces are released after the model is unmounted, reloaded or switched, and are reconfigured for the new instance through `onInstanceChanged`.
Precision-sensitive rendering uses the relative interface, subtracting the origin in double first and then converting to float; there is no need to obtain a float world position first.

## McModelEquipment

```java
McModelEquipment equipment = new McModelEquipment(handle, () -> entity)
        .bindHands("right_grip", "left_grip")
        .bindHeadItem("head", new Transform());

// Use a camera-relative world PoseStack after the final pose of the model's current frame has been evaluated.
int count = equipment.render(poseStack, bufferSource, camera.getPosition(), packedLight, packedOverlay);

equipment.unbind(EquipmentSlot.OFFHAND);
equipment.close();
```

`bindHands` reads the entity's current main/off hand ItemStack and, following the entity's dominant hand, selects the physical hand anchor and the
`THIRD_PERSON_LEFT_HAND / THIRD_PERSON_RIGHT_HAND` display transform. You can pass two Transforms, for the left and right hands, to
adjust the tool's hold position, rotation and scale; they are copied on binding.
`bindHeadItem` uses the HEAD display context, suited to head-worn items such as pumpkins.
The vanilla item renderer handles the item model, display context, color and enchantment effects, using the passed lighting and overlay.

```java
equipment.bind(EquipmentSlot.MAINHAND, "right_grip", () -> weapon,
        ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false, localOffset);

equipment.bind(EquipmentSlot.CHEST, "torso", () -> entity.getItemBySlot(EquipmentSlot.CHEST),
        ItemDisplayContext.FIXED, false, localOffset, context -> {
            customArmorRenderer.render(context);
        });
```

`bind(EquipmentSlot, String anchor, Supplier<ItemStack>, ItemDisplayContext, boolean leftHand, Transform offset)`
uses the vanilla ItemRenderer; an additional `Renderer` parameter can integrate custom equipment, armor layers or other display objects.
The Context of `Renderer.render(Context)` contains slot, stack, displayContext, leftHand, entity, poseStack,
buffers, level, light, overlay, seed. The ItemStack is borrowed for this frame and is not modified by the attach adapter.
Humanoid armor layer/skinning adaptation for fitted armor such as chestplates and leggings is provided by a custom renderer or an assembly entry point;
the vanilla item renderer itself does not turn an item icon into fitted armor.

A new binding on a slot replaces the old one; an empty ItemStack, a missing Anchor, or an unmounted/invisible model is skipped.
A name binding queries the current instance on every render, so after a resource reload and a swap it automatically uses the new Skeleton; it does not keep an old ModelInstance.
close releases the bindings and the entity supplier, and does not close the model, the item renderer or the buffers.

Calls run on the render thread, and the PoseStack must be in camera-relative world space, without duplicating the entity translation.
The attach adapter pushes/pops the matrix of each item and restores the PoseStack even when rendering throws; it does not advance tick, animation or physics time.
Finish the shared animation/IK/physics pose evaluation first, then draw the model and the equipment, to avoid advancing the simulation repeatedly across camera views.

## Verification entry

```sh
./gradlew :modules:modelling:modelUnitTest
./gradlew :modules:modelling:runClientHeadless -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=fixture -PkasugaTestAnchorEquipment=true
```

The success marker is `ANCHOR_EQUIPMENT_SMOKE_PASS`, and the report is in `debug/anchor-equipment.json`.
The client test uses a geometry-free neutral skeleton and a vanilla sword, pickaxe and pumpkin to verify drawing, dominant-hand switching and anchor motion; it does not display the user's base body.
