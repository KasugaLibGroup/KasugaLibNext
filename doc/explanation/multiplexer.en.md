# How model variant selection works

For the Java DSL, Builder, and runtime methods see the [API reference](../reference/multiplexer.md).

## Shared definition and instance state

Multiplexer describes the choice from context to appearance with variant nodes and guarded transition edges. The definition itself holds no runtime state;
the same content type shares a definition, and each host instance keeps its own current variant, target variant, and cross-fade progress.
The context delivers information such as block properties and redstone state as a snapshot, and the selector does not depend on specific Minecraft objects.

## Selection and advancing

Read-only selection evaluates the available variants against the current context; advancing updates both the instance's transition progress and the selected state.
A guard hit starts a transition; during the cross-fade the current output corresponds to the target variant, and the instance information the blending process needs is kept.
Each host advances independently, so the same definition can select different appearances in different scenes.

## Division of responsibility with the state machine

Context-driven switches, gears, and model variants suit the variant selector; behavior that depends on duration, actions, parallel layers, and animation sequences
suits the [layered state machine](fsm.md). Variant selection decides which model to use, and the state machine keeps driving the pose and action timing of the selected model.
