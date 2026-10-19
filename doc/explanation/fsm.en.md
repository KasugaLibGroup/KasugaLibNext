# How the layered animation state machine works

For the data schema, action / guard references, and binding see the [API reference](../reference/fsm.md).

## Definitions and instances

A state machine definition describes variables, parallel layers, states, and transitions, and is loaded into the definition registry during a resource reload. A running instance holds the current state,
the state duration, variable values, and blend progress, and none of that state may be written back into the shared definition.
The file identity is independent of the machine's logical identity, and the host binds an instance by the logical identity.

## State advancement and actions

Each layer has its own state graph; a tick selects a transition based on guards, triggers, and completion conditions, and runs the entry, exit,
update, and transition actions at the corresponding stages. Content references function ids registered on the code side, which avoids executing arbitrary code at data-load time.
Ephemeral triggers are cleared at the end of the tick cycle, while ordinary variables stay in the instance.

## Pose blending

Multiple layers composite the pose in a deterministic order. The base layer provides the baseline, additive layers stack local motion on top, and override layers replace the specified range according to the bone mask.
During a transition the pose is blended over time between the poses of the previous and next states; render frames sample smoothly with the partial tick rather than advancing the logical state any further.
The result contains bone local transforms, morph weights, and material frames, and then enters the unified bone, IK, and physics flow.

The state machine owns action timing; the selection from a simple context to a model variant can be handled by [Multiplexer](multiplexer.md).
The two can be combined: the model variant decides which instance is used, and the state machine decides how that instance moves.
