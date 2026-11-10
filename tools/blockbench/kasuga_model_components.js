/* Kasuga Model Components — one-file Blockbench plugin and testable authoring core. */
(function () {
  "use strict";
  const clone = (value) => JSON.parse(JSON.stringify(value));
  const add = (a, b) => a.map((v, i) => v + b[i]),
    sub = (a, b) => a.map((v, i) => v - b[i]);
  const mul = (a, s) => a.map((v) => v * s),
    dot = (a, b) => a.reduce((s, v, i) => s + v * b[i], 0);
  const cross = (a, b) => [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
  ];
  const length = (a) => Math.hypot(...a),
    distance = (a, b) => length(sub(a, b));
  const norm = (a) => (length(a) > 1e-10 ? mul(a, 1 / length(a)) : [0, 0, 0]);
  function rotate(v, axis, angle) {
    const c = Math.cos(angle),
      s = Math.sin(angle);
    return add(
      add(mul(v, c), mul(cross(axis, v), s)),
      mul(axis, dot(axis, v) * (1 - c)),
    );
  }
  function finite(value, path, min = -Infinity, max = Infinity) {
    if (
      typeof value !== "number" ||
      !Number.isFinite(value) ||
      value < min ||
      value > max
    )
      throw Error(`${path}: expected finite number in [${min}, ${max}]`);
  }
  function vector(v, path) {
    if (!Array.isArray(v) || v.length !== 3)
      throw Error(`${path}: expected vector3`);
    v.forEach((n, i) => finite(n, `${path}[${i}]`));
  }
  function validate(data) {
    if (!data || data.version !== 1)
      throw Error("kasuga_lib.version: only version 1 is editable");
    if (
      !Array.isArray(data.bones) ||
      !Array.isArray(data.emitters) ||
      !Array.isArray(data.bindings)
    )
      throw Error("kasuga_lib: bones, bindings and emitters must be arrays");
    const bones = new Map(),
      names = new Set(),
      ids = new Set();
    for (const b of data.bones) {
      if (!b.id || ids.has(b.id) || !b.name || names.has(b.name))
        throw Error("bones: duplicate/empty id or name");
      ids.add(b.id);
      names.add(b.name);
      bones.set(b.id, b);
      vector(b.pivot, `bones.${b.id}.pivot`);
    }
    const ref = (id, path) => {
      if (!bones.has(id)) throw Error(`${path}: missing bone ${id}`);
      return bones.get(id);
    };
    // Check the whole hierarchy before following any IK target's ancestors.
    for (const bone of data.bones) {
      const seen = new Set([bone.id]);
      for (
        let parent = bone.parent;
        parent != null;
        parent = bones.get(parent).parent
      ) {
        ref(parent, `bones.${bone.id}.parent`);
        if (seen.has(parent)) throw Error(`bones.${bone.id}: parent cycle`);
        seen.add(parent);
      }
    }
    for (const b of data.bones) {
      const seen = new Set([b.id]);
      let p = b.parent;
      while (p != null) {
        ref(p, `bones.${b.id}.parent`);
        if (seen.has(p)) throw Error(`bones.${b.id}: parent cycle`);
        seen.add(p);
        p = bones.get(p).parent;
      }
      const c = b.components || {};
      if (c.ik) {
        const k = c.ik;
        ref(k.target, `ik.${b.id}.target`);
        if (seen.has(k.target))
          throw Error(`ik.${b.id}: target must be outside driven chain`);
        if (!Array.isArray(k.links) || !k.links.length)
          throw Error(`ik.${b.id}.links: empty`);
        let child = b;
        for (const l of [...k.links].reverse()) {
          const link = ref(l.bone, `ik.${b.id}.links`);
          if (child.parent !== link.id)
            throw Error(
              `ik.${b.id}: links must be contiguous base to tip ancestors`,
            );
          child = link;
          if (l.min || l.max) {
            vector(l.min, "ik.min");
            vector(l.max, "ik.max");
            if (l.min.some((v, i) => v > l.max[i]))
              throw Error("ik: reversed limits");
          }
        }
        let targetAncestor = bones.get(k.target);
        while (targetAncestor) {
          if (targetAncestor.id === child.id)
            throw Error(`ik.${b.id}: target cannot descend from a driven link`);
          targetAncestor = bones.get(targetAncestor.parent);
        }
        finite(k.iterations ?? 32, "ik.iterations", 1, 256);
        if (!Number.isInteger(k.iterations ?? 32))
          throw Error("ik.iterations: integer required");
        finite(k.tolerance ?? 0.01, "ik.tolerance", 0.000001, 16);
        finite(k.max_step ?? 45, "ik.max_step", 0, 180);
      }
      if (c.physics) {
        const p = c.physics;
        if (!["sphere", "box", "capsule"].includes(p.shape ?? "capsule"))
          throw Error("physics.shape");
        if (
          !["kinematic", "dynamic", "rotation"].includes(p.mode ?? "rotation")
        )
          throw Error("physics.mode");
        vector(p.size ?? [1, 4, 1], "physics.size");
        if ((p.size ?? [1, 4, 1]).some((v) => v <= 0))
          throw Error("physics.size: positive dimensions required");
        vector(p.position ?? [0, 0, 0], "physics.position");
        vector(p.rotation ?? [0, 0, 0], "physics.rotation");
        vector(p.gravity ?? [0, -9.8, 0], "physics.gravity");
        finite(p.mass ?? 1, "physics.mass", 0, 1e6);
        finite(p.linear_damping ?? 0.2, "physics.linear_damping", 0, 1);
        finite(p.angular_damping ?? 0.2, "physics.angular_damping", 0, 1);
        finite(p.stiffness ?? 80, "physics.stiffness", 0, 10000);
        finite(p.friction ?? 0.5, "physics.friction", 0, 10);
        finite(p.restitution ?? 0, "physics.restitution", 0, 1);
        finite(p.collision_group ?? 0, "physics.collision_group", 0, 63);
        finite(
          p.non_collision_mask ?? 0,
          "physics.non_collision_mask",
          -2147483648,
          2147483647,
        );
        if (
          !Number.isInteger(p.collision_group ?? 0) ||
          !Number.isInteger(p.non_collision_mask ?? 0)
        )
          throw Error("physics: integer collision filter required");
        if (p.parent_body) {
          const parent = ref(p.parent_body, "physics.parent_body");
          if (!parent.components?.physics)
            throw Error("physics.parent_body: missing rigid body");
          if (parent === b) throw Error("physics.parent_body: self joint");
        }
        if (p.joint) {
          for (const key of ["rotation_min", "rotation_max", "rotation_spring"])
            vector(p.joint[key] ?? [0, 0, 0], `physics.joint.${key}`);
          if (
            (p.joint.rotation_min ?? [0, 0, 0]).some(
              (v, i) => v > (p.joint.rotation_max ?? [0, 0, 0])[i],
            )
          )
            throw Error("physics.joint: reversed limits");
        }
      }
    }
    const bindingIndices = new Set();
    for (const b of data.bindings) {
      if (
        !Number.isInteger(b.element) ||
        b.element < 0 ||
        bindingIndices.has(b.element)
      )
        throw Error("bindings: duplicate/invalid element");
      bindingIndices.add(b.element);
      if (b.bone != null) ref(b.bone, "bindings.bone");
    }
    for (const e of data.emitters) {
      if (!e.id || ids.has(e.id)) throw Error("emitters: duplicate/empty id");
      ids.add(e.id);
      if (e.bone != null) ref(e.bone, `emitters.${e.id}.bone`);
      vector(e.position, "emitter.position");
      vector(e.rotation, "emitter.rotation");
      finite(e.rate ?? 20, "emitter.rate", 0, 10000);
      finite(e.max_particles ?? 256, "emitter.max_particles", 1, 4096);
      if (!Number.isInteger(e.max_particles ?? 256))
        throw Error("emitter.max_particles: integer");
      if (!["local", "world"].includes(e.space ?? "world"))
        throw Error("emitter.space");
      const p = e.particle || {};
      finite(p.lifetime ?? 1, "particle.lifetime", 0.01, 60);
      finite(p.speed ?? 8, "particle.speed", 0, 10000);
      finite(p.spread ?? 15, "particle.spread", 0, 180);
      finite(p.size ?? 1, "particle.size", 0.01, 256);
      vector(p.gravity ?? [0, 0, 0], "particle.gravity");
      if (!/^#[0-9a-f]{6}$/i.test(p.color ?? "#ff8844"))
        throw Error("particle.color: hex RGB");
    }
    return data;
  }
  const DEFAULT_IK = {
      iterations: 32,
      tolerance: 0.01,
      max_step: 45,
    },
    DEFAULT_PHYSICS = {
      mode: "rotation",
      shape: "capsule",
      size: [1, 4, 1],
      position: [0, 4, 0],
      rotation: [0, 0, 0],
      gravity: [0, -156.8, 0],
      mass: 1,
      linear_damping: 0.2,
      angular_damping: 0.2,
      stiffness: 80,
      friction: 0.5,
      restitution: 0,
      collision_group: 0,
      non_collision_mask: 0,
    };
  function applyComponentBatch(data, boneIds, mode, change) {
    const result = clone(data),
      ids = new Set(boneIds || []),
      fields = change?.fields || {},
      values = change?.values || {},
      bones = new Map(result.bones.map((bone) => [bone.id, bone]));
    if (!ids.size) throw Error("至少选择一个骨骼");
    for (const id of ids) {
      const bone = bones.get(id);
      if (!bone) throw Error(`batch: missing bone ${id}`);
      bone.components ||= {};
      if (fields.parent_group) bone.parent = values.parent_group || null;
      if (fields.pivot) {
        vector(values.pivot, "bone.pivot");
        bone.pivot = clone(values.pivot);
      }
      if (fields.target_position) {
        vector(values.target_position, "ik.target_position");
        const target = bones.get(
          fields.target ? values.target : bone.components.ik?.target,
        );
        if (!target) throw Error("请先设置 IK 目标");
        target.pivot = clone(values.target_position);
      }
      const componentFields = Object.keys(fields).filter(
        (key) => !["parent_group", "pivot", "target_position"].includes(key),
      );
      if (mode === "physics") {
        if (fields.enabled && values.enabled === false) {
          delete bone.components.physics;
          continue;
        }
        if (
          fields.enabled ||
          componentFields.some((key) => key !== "enabled" && fields[key])
        )
          bone.components.physics = {
            ...clone(DEFAULT_PHYSICS),
            ...clone(bone.components.physics || {}),
          };
        const physics = bone.components.physics;
        if (!physics) continue;
        for (const key of Object.keys(DEFAULT_PHYSICS))
          if (fields[key]) physics[key] = clone(values[key]);
        if (fields.parent_body) {
          if (values.parent_body) physics.parent_body = values.parent_body;
          else delete physics.parent_body;
        }
        if (fields.joint_min || fields.joint_max) {
          physics.joint = {
            ...(physics.joint || {}),
            ...(fields.joint_min
              ? { rotation_min: clone(values.joint_min) }
              : {}),
            ...(fields.joint_max
              ? { rotation_max: clone(values.joint_max) }
              : {}),
          };
        }
      } else if (mode === "ik") {
        if (fields.enabled && values.enabled === false) {
          delete bone.components.ik;
          continue;
        }
        if (
          fields.enabled ||
          componentFields.some((key) => key !== "enabled" && fields[key])
        )
          bone.components.ik = {
            ...DEFAULT_IK,
            ...clone(bone.components.ik || {}),
          };
        const ik = bone.components.ik;
        if (!ik) continue;
        if (fields.target) ik.target = values.target;
        if (fields.iterations) ik.iterations = values.iterations;
        if (fields.tolerance) ik.tolerance = values.tolerance;
        if (fields.max_step) ik.max_step = values.max_step;
        if (fields.root) {
          const links = [],
            ancestors = [],
            seen = new Set([bone.id]);
          for (
            let parent = bones.get(bone.parent);
            parent;
            parent = bones.get(parent.parent)
          ) {
            if (seen.has(parent.id))
              throw Error(`bones.${bone.id}: parent cycle`);
            seen.add(parent.id);
            ancestors.push(parent);
          }
          const rootIndex = ancestors.findIndex(
            (ancestor) => ancestor.id === values.root,
          );
          if (rootIndex < 0)
            throw Error(`ik.${id}: 链根必须是当前骨骼的父级骨骼`);
          for (let index = rootIndex; index >= 0; index--)
            links.push(
              clone(
                ik.links?.find((link) => link.bone === ancestors[index].id) || {
                  bone: ancestors[index].id,
                },
              ),
            );
          ik.links = links;
        }
        if (fields.minimum || fields.maximum) {
          const minimum = values.minimum || [-180, -180, -180],
            maximum = values.maximum || [180, 180, 180];
          ik.links = (ik.links || []).map((link) => ({
            ...link,
            min: clone(
              fields.minimum ? minimum : (link.min ?? [-180, -180, -180]),
            ),
            max: clone(
              fields.maximum ? maximum : (link.max ?? [180, 180, 180]),
            ),
          }));
        }
      } else {
        throw Error(`batch: unsupported component mode ${mode}`);
      }
    }
    validate(result);
    return result;
  }
  function writeLivePack(fileSystem, pathModule, config, model) {
    const match =
      /^([a-z0-9_.-]+):(models\/[a-z0-9_./-]+\.(?:bbmodel|json))$/.exec(
        config.resource || "",
      );
    if (
      !match ||
      match[2].split("/").some((part) => part === "." || part === ".." || !part)
    )
      throw Error(
        "模型资源 ID 必须形如 namespace:models/path/model.bbmodel（小写）",
      );
    if (!config.directory || !pathModule.isAbsolute(config.directory))
      throw Error("请选择预览资源包的绝对路径");
    const root = pathModule.resolve(config.directory),
      resolve = (relative) => {
        const result = pathModule.resolve(root, relative);
        if (!result.startsWith(root + pathModule.sep))
          throw Error("同步路径超出资源包");
        // Never follow existing symlinks out of the selected pack.
        let cursor = result;
        while (cursor !== root) {
          if (
            fileSystem.existsSync(cursor) &&
            fileSystem.lstatSync(cursor).isSymbolicLink()
          )
            throw Error("同步目标含符号链接，请选择普通目录");
          cursor = pathModule.dirname(cursor);
        }
        return result;
      };
    fileSystem.mkdirSync(root, { recursive: true });
    const atomicWrite = (relative, content) => {
      const destination = resolve(relative);
      fileSystem.mkdirSync(pathModule.dirname(destination), {
        recursive: true,
      });
      const temporary = destination + ".kasuga-tmp";
      // The temporary path itself must pass the same symlink checks.
      resolve(relative + ".kasuga-tmp");
      try {
        fileSystem.writeFileSync(temporary, content);
        fileSystem.renameSync(temporary, destination);
      } finally {
        if (fileSystem.existsSync(temporary)) fileSystem.unlinkSync(temporary);
      }
    };
    const metadata = resolve("pack.mcmeta");
    if (!fileSystem.existsSync(metadata))
      atomicWrite(
        "pack.mcmeta",
        JSON.stringify(
          {
            pack: {
              pack_format: 34,
              description: "Kasuga Blockbench live preview",
            },
          },
          null,
          2,
        ),
      );
    const proxyPath = "assets/kasuga_live/models/model_proxy.json",
      proxyFile = resolve(proxyPath),
      proxy = fileSystem.existsSync(proxyFile)
        ? JSON.parse(fileSystem.readFileSync(proxyFile, "utf8"))
        : {};
    const [_, namespace, resourcePath] = match;
    if (proxy[namespace] && !Array.isArray(proxy[namespace]))
      throw Error("预览包 model_proxy.json 格式不正确");
    proxy[namespace] ||= [];
    if (!proxy[namespace].includes(resourcePath))
      proxy[namespace].push(resourcePath);
    atomicWrite(
      `assets/${namespace}/${resourcePath}`,
      typeof model === "string" ? model : JSON.stringify(model, null, 2),
    );
    atomicWrite(proxyPath, JSON.stringify(proxy, null, 2));
    atomicWrite(
      ".kasuga-sync.json",
      JSON.stringify({
        version: 1,
        resource: config.resource,
        generation: `${Date.now()}-${Math.random()}`,
      }),
    );
    return pathModule.join(root, "assets", namespace, resourcePath);
  }
  /* CCD positions are immutable input, radians; segment lengths are preserved. */
  function solveCCD(
    input,
    target,
    iterations = 32,
    tolerance = 0.01,
    maxStep = Math.PI / 4,
  ) {
    const points = input.map((p) => p.slice());
    for (
      let n = 0;
      n < iterations && distance(points.at(-1), target) > tolerance;
      n++
    )
      for (let i = points.length - 2; i >= 0; i--) {
        const a = norm(sub(points.at(-1), points[i])),
          b = norm(sub(target, points[i]));
        if (length(a) < 1e-10 || length(b) < 1e-10) continue;
        let axis = norm(cross(a, b)),
          angle = Math.acos(Math.max(-1, Math.min(1, dot(a, b))));
        if (length(axis) < 1e-10) {
          if (angle < 1e-8) continue;
          axis = norm(cross(a, Math.abs(a[0]) < 0.9 ? [1, 0, 0] : [0, 1, 0]));
        }
        angle = Math.min(maxStep, angle);
        for (let j = i + 1; j < points.length; j++)
          points[j] = add(
            points[i],
            rotate(sub(points[j], points[i]), axis, angle),
          );
      }
    return points;
  }
  function springStep(state, target, p, dt) {
    const stiffness = (p.stiffness ?? 80) / Math.max(0.001, p.mass ?? 1);
    const denominator =
      1 + (p.linear_damping ?? 0.2) * 30 * dt + stiffness * dt * dt;
    for (let i = 0; i < 3; i++) {
      state.velocity[i] =
        (state.velocity[i] +
          dt *
            (stiffness * (target[i] - state.position[i]) +
              (p.gravity ?? [0, -9.8, 0])[i])) /
        denominator;
      state.position[i] += state.velocity[i] * dt;
    }
  }
  const particleState = () => ({ particles: [], remainder: 0 });
  function stepParticles(
    state,
    emitter,
    dt,
    random = Math.random,
    spawn = null,
  ) {
    dt = Math.max(0, Math.min(dt, 0.1));
    const spec = emitter.particle || {},
      gravity = spec.gravity ?? [0, 0, 0];
    state.particles = state.particles.filter((p) => {
      p.age += dt;
      if (p.age >= p.lifetime) return false;
      for (let i = 0; i < 3; i++) {
        p.velocity[i] += p.gravity[i] * dt;
        p.position[i] += p.velocity[i] * dt;
      }
      return true;
    });
    if (emitter.enabled === false) return state;
    state.remainder += (emitter.rate ?? 20) * dt;
    const count = Math.floor(state.remainder + 1e-9);
    state.remainder -= count;
    const available = Math.min(
      count,
      (emitter.max_particles ?? 256) - state.particles.length,
    );
    for (let n = 0; n < available; n++) {
      const theta = random() * Math.PI * 2,
        c =
          1 - random() * (1 - Math.cos(((spec.spread ?? 15) * Math.PI) / 180)),
        s = Math.sqrt(Math.max(0, 1 - c * c));
      let p = {
        position: [0, 0, 0],
        velocity: mul(
          [s * Math.cos(theta), c, s * Math.sin(theta)],
          spec.speed ?? 8,
        ),
        gravity: gravity.slice(),
        age: 0,
        lifetime: spec.lifetime ?? 1,
      };
      if (spawn) p = spawn(p);
      state.particles.push(p);
    }
    return state;
  }
  function attachExtension(model, data, kind) {
    const destination =
      kind === "bedrock" ? model["minecraft:geometry"][0] : model;
    destination.kasuga_lib = clone(data);
    return model;
  }
  const api = {
    validate,
    applyComponentBatch,
    writeLivePack,
    solveCCD,
    springStep,
    particleState,
    stepParticles,
    distance,
    attachExtension,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof Plugin === "undefined") return;
  let actions = [],
    properties = [],
    listeners = [],
    preview = null;
  let originalJavaLocators;
  const groupList = () => Group.all;
  const locatorList = () => Locator.all;
  const sourceGroup = (node) =>
    node?.parent instanceof Group ? node.parent : null;
  const boneOptions = (allowNone = false) =>
    Object.fromEntries([
      ...(allowNone ? [["", "无"]] : []),
      ...groupList().map((g) => [g.uuid, g.name]),
    ]);
  function capture() {
    const groups = groupList().filter((g) => g.export !== false),
      ids = new Set(groups.map((g) => g.uuid));
    const bones = groups.map((g) => ({
      ...clone(g.kasuga_bone || {}),
      id: g.uuid,
      name: g.name,
      parent: ids.has(g.parent?.uuid) ? g.parent.uuid : null,
      pivot: g.origin.slice(),
      rotation: g.rotation.slice(),
      components: clone(g.kasuga_components || {}),
    }));
    const emitters = locatorList()
      .filter((l) => l.kasuga_emitter?.particle && l.export !== false)
      .map((l) => {
        const parent = sourceGroup(l);
        return {
          ...clone(l.kasuga_emitter),
          id: l.uuid,
          name: l.name,
          bone: parent?.uuid ?? null,
          position: sub(l.position, parent?.origin ?? [0, 0, 0]),
          rotation: l.rotation.slice(),
        };
      });
    return {
      ...clone(Project.kasuga_metadata || {}),
      version: Project.kasuga_metadata?.version ?? 1,
      bones,
      emitters,
      bindings: [],
    };
  }
  function active(data) {
    return (
      data.emitters.length ||
      data.bones.some((b) => Object.keys(b.components).length) ||
      Object.keys(Project.kasuga_metadata || {}).length
    );
  }
  function report(error) {
    console.error("[Kasuga Model Components]", error);
    Blockbench.showMessageBox({
      title: "Kasuga 模型组件",
      message: String(error.message || error),
    });
  }
  function guarded(fn) {
    return function (...args) {
      try {
        return fn.apply(this, args);
      } catch (error) {
        stopPreview();
        report(error);
      }
    };
  }
  function saveNode(node, property, value, label) {
    stopPreview();
    const data = capture();
    if (property === "kasuga_components")
      data.bones.find((b) => b.id === node.uuid).components = clone(value);
    else
      Object.assign(
        data.emitters.find((e) => e.id === node.uuid),
        value,
      );
    validate(data);
    Undo.initEdit(
      node instanceof Group ? { groups: [node] } : { elements: [node] },
    );
    node[property] = clone(value);
    Undo.finishEdit(label);
    Project.saved = false;
  }
  function hydrate(data, kind) {
    stopPreview();
    Project.kasuga_metadata = clone(data);
    try {
      validate(data);
    } catch (error) {
      report(error);
      return;
    }
    const mapping = new Map(),
      available = groupList().slice();
    for (const b of data.bones) {
      let g =
        available.find((g) => g.uuid === b.id) ||
        available.find((g) => g.name === b.name);
      if (!g)
        g = new Group(
          {
            name: b.name,
            origin: b.pivot,
            rotation: kind === "java" ? [0, 0, 0] : b.rotation || [0, 0, 0],
          },
          b.id,
        ).init();
      mapping.set(b.id, g);
    }
    const remap = (id) => mapping.get(id)?.uuid ?? id;
    for (const b of data.bones) {
      const g = mapping.get(b.id);
      g.kasuga_bone = clone(b);
      g.kasuga_components = clone(b.components || {});
      const c = g.kasuga_components;
      if (c.ik) {
        c.ik.target = remap(c.ik.target);
        c.ik.links.forEach((l) => (l.bone = remap(l.bone)));
      }
      if (c.physics?.parent_body)
        c.physics.parent_body = remap(c.physics.parent_body);
      if (kind === "java")
        g.addTo(b.parent == null ? "root" : mapping.get(b.parent));
    }
    if (kind === "java") {
      const cubes = Cube.all.slice();
      for (const binding of data.bindings) {
        if (binding.element >= cubes.length)
          throw Error("bindings: imported element index out of range");
        cubes[binding.element].addTo(
          binding.bone == null ? "root" : mapping.get(binding.bone),
        );
      }
    }
    for (const e of data.emitters) {
      const parent = mapping.get(e.bone),
        position = add(e.position, parent?.origin ?? [0, 0, 0]);
      let l =
        locatorList().find((l) => l.uuid === e.id) ||
        locatorList().find(
          (l) => l.name === e.name && sourceGroup(l) === parent,
        );
      if (!l)
        l = new Locator({ name: e.name, position, rotation: e.rotation }, e.id)
          .addTo(parent || "root")
          .init();
      l.kasuga_emitter = clone(e);
    }
    Canvas.updateAll();
  }
  function exportModel(kind, event) {
    stopPreview();
    let data = capture();
    if (!active(data)) return;
    if (data.version !== 1) {
      if (kind === "bedrock")
        event.model["minecraft:geometry"][0].kasuga_lib = clone(
          Project.kasuga_metadata,
        );
      else event.model.kasuga_lib = clone(Project.kasuga_metadata);
      return;
    }
    if (event.options?.collection_only || event.options?.collection)
      throw Error("Kasuga 组件请导出完整模型，集合导出会截断骨骼引用。");
    if (kind === "java") {
      const cubes = [];
      const walk = (nodes) =>
        nodes.forEach((node) => {
          if (node instanceof Group) walk(node.children);
          else if (
            node instanceof Cube &&
            node.export !== false &&
            Object.values(node.faces).some((f) => f.texture !== null)
          )
            cubes.push(node);
        });
      walk(Outliner.root);
      if (cubes.length !== (event.model.elements || []).length)
        throw Error(
          "Java codec element order changed; cannot safely bind bones",
        );
      data.bindings = cubes.map((cube, i) => ({
        element: i,
        bone: sourceGroup(cube)?.uuid ?? null,
      }));
    }
    validate(data);
    if (kind === "bedrock") {
      const geometry = event.model["minecraft:geometry"][0];
      // All control bones remain in the extension; native geometry stays untouched.
      attachExtension(event.model, data, "bedrock");
    } else attachExtension(event.model, data, kind);
  }
  function editIk() {
    enterConfigMode("ik");
  }
  function editPhysics() {
    enterConfigMode("physics");
  }
  let configPanel,
    configMode = "ik",
    editor = null,
    picking = null,
    drag = null;
  const modes = [],
    configDisposers = [],
    syncConfigs = new WeakMap();
  let panelState = { values: {}, fields: {}, bones: [] },
    selectionKey = "";
  const inConfigMode = () =>
    !!Project && /^kasuga_(ik|physics)_mode$/.test(Modes.id);
  const selectedBones = () =>
    (Group.multi_selected || []).filter((g) => !g.locked && g.export !== false);
  const configFields = {
    ik: [
      ["parent_group", "父级骨骼（组层级）", "bone", ""],
      ["pivot", "骨骼枢轴 / px", "vector", [0, 0, 0]],
      ["target_position", "IK 目标枢轴 / px", "vector", [0, 0, 0]],
      ["enabled", "启用 IK", "boolean", false],
      ["target", "目标骨骼", "bone", ""],
      ["root", "链根骨骼", "bone", ""],
      ["iterations", "迭代次数", "number", 32],
      ["tolerance", "误差 / px", "number", 0.01],
      ["max_step", "每步旋转 / °", "number", 45],
      ["minimum", "链节最小角 / °", "vector", [-180, -180, -180]],
      ["maximum", "链节最大角 / °", "vector", [180, 180, 180]],
    ],
    physics: [
      ["parent_group", "父级骨骼（组层级）", "bone", ""],
      ["pivot", "骨骼枢轴 / px", "vector", [0, 0, 0]],
      ["enabled", "启用刚体", "boolean", false],
      ["parent_body", "父级刚体骨骼", "bone", ""],
      [
        "mode",
        "运动方式",
        "select",
        "rotation",
        {
          kinematic: "跟随骨骼",
          rotation: "动态旋转",
          dynamic: "动态位置和旋转",
        },
      ],
      [
        "shape",
        "碰撞体",
        "select",
        "capsule",
        { capsule: "胶囊", box: "盒体", sphere: "球体" },
      ],
      ["position", "局部位置 / px", "vector", [0, 4, 0]],
      ["rotation", "局部旋转 / °", "vector", [0, 0, 0]],
      [
        "size",
        "尺寸 / px（盒半长、球半径、胶囊半径及柱高）",
        "vector",
        [1, 4, 1],
      ],
      ["mass", "质量", "number", 1],
      ["linear_damping", "线性阻尼", "number", 0.2],
      ["angular_damping", "角阻尼", "number", 0.2],
      ["friction", "摩擦", "number", 0.5],
      ["restitution", "恢复系数", "number", 0],
      ["joint_min", "关节最小角 / °", "vector", [-45, -45, -45]],
      ["joint_max", "关节最大角 / °", "vector", [45, 45, 45]],
      ["collision_group", "碰撞组 0–63", "number", 0],
      ["non_collision_mask", "排除组掩码", "number", 0],
      ["stiffness", "BB 预览弹性", "number", 80],
      ["gravity", "BB 预览重力", "vector", [0, -156.8, 0]],
    ],
  };
  let handleMode = "translate";
  let focusBones = false;
  function element(tag, attrs = {}, content) {
    const node = document.createElement(tag);
    Object.assign(node, attrs);
    if (content != null) node.textContent = content;
    return node;
  }
  function button(label, fn, parent, id) {
    const node = element(
      "button",
      { type: "button", onclick: () => panelGuard(fn) },
      label,
    );
    if (id) node.dataset.action = id;
    parent.append(node);
    return node;
  }
  function panelGuard(fn) {
    try {
      fn();
    } catch (error) {
      if (configPanel)
        configPanel.node.querySelector(".kasuga-status").textContent =
          error.message;
      else report(error);
    }
  }
  function currentField(bone, key, fallback) {
    const c = bone.kasuga_components?.[configMode];
    if (key === "parent_group")
      return bone.parent instanceof Group ? bone.parent.uuid : "";
    if (key === "pivot") return bone.origin.slice();
    if (key === "target_position")
      return (
        groupList()
          .find((g) => g.uuid === c?.target)
          ?.origin.slice() ?? fallback
      );
    if (key === "enabled") return !!c;
    if (key === "root") return c?.links?.[0]?.bone ?? fallback;
    if (key === "minimum") return c?.links?.[0]?.min ?? fallback;
    if (key === "maximum") return c?.links?.[0]?.max ?? fallback;
    if (key === "joint_min") return c?.joint?.rotation_min ?? fallback;
    if (key === "joint_max") return c?.joint?.rotation_max ?? fallback;
    return c?.[key] ?? fallback;
  }
  function refreshConfig(force = false) {
    if (!configPanel || !Project) return;
    const bones = selectedBones(),
      key =
        Project.uuid + ":" + configMode + ":" + bones.map((b) => b.uuid).join();
    if (!force && key === selectionKey) return;
    selectionKey = key;
    panelState = { bones: bones.map((b) => b.uuid), values: {}, fields: {} };
    const root = configPanel.node;
    root.replaceChildren();
    const tabs = element("div", { className: "kasuga-row" });
    button("IK 配置", () => enterConfigMode("ik"), tabs, "ik-mode");
    button("物理配置", () => enterConfigMode("physics"), tabs, "physics-mode");
    root.append(tabs);
    const status = element(
      "p",
      { className: "kasuga-status" },
      `已选择 ${bones.length} 根骨骼：${bones.map((b) => b.name).join("、") || "点击菱形骨骼或组"}`,
    );
    root.append(status);
    const hint = element(
      "p",
      { className: "kasuga-hint" },
      "Ctrl / ⌘ 或 Shift 多选。仅应用勾选的字段；混合值不自动覆盖。关系可在模型或骨骼树中点选。Esc 取消拾取 / 拖动。",
    );
    root.append(hint);
    const focusLabel = element("label", { className: "kasuga-label" }),
      focus = element("input", { type: "checkbox", checked: focusBones });
    focus.dataset.action = "focus-bones";
    focus.onchange = () => {
      focusBones = focus.checked;
      rebuildEditor();
    };
    focusLabel.append(focus, document.createTextNode(" 只显示所选与关联骨骼"));
    root.append(focusLabel);
    const tools = element("div", { className: "kasuga-row" });
    for (const [id, label] of [
      ["translate", "移动"],
      ["rotate", "旋转"],
      ["scale", "尺寸"],
    ])
      button(
        label,
        () => {
          handleMode = id;
          rebuildEditor();
        },
        tools,
        id,
      );
    button(
      preview ? "停止预览" : "开始预览",
      () => {
        startPreview();
        refreshConfig(true);
        rebuildEditor();
      },
      tools,
      "preview",
    );
    root.append(tools);
    if (configMode === "ik") {
      const helpers = element("div", { className: "kasuga-row" });
      button(
        "为连杆创建 IK 控制点",
        createRodControls,
        helpers,
        "rod-controls",
      );
      button(
        "移动 IK 目标",
        () => {
          handleMode = "target";
          rebuildEditor();
        },
        helpers,
        "move-target",
      );
      root.append(helpers);
    }
    for (const [key, label, type, fallback, options] of configFields[
      configMode
    ]) {
      const values = bones.map((bone) => currentField(bone, key, fallback)),
        value = values[0] ?? fallback,
        mixed = values.some((v) => JSON.stringify(v) !== JSON.stringify(value)),
        row = element("div", { className: "kasuga-field" });
      row.dataset.field = key;
      const heading = element("label", { className: "kasuga-label" }),
        enabled = element("input", {
          type: "checkbox",
          title: "把此字段应用到所选骨骼",
        });
      enabled.onchange = () => {
        panelState.fields[key] = enabled.checked;
      };
      heading.append(
        enabled,
        document.createTextNode(label + (mixed ? " · 多个不同值" : "")),
      );
      row.append(heading);
      panelState.values[key] = clone(value);
      const mark = (v) => {
        enabled.checked = true;
        panelState.fields[key] = true;
        panelState.values[key] = v;
      };
      if (type === "bone") {
        const select = element("select");
        select.append(element("option", { value: "" }, "无 / 尚未指定"));
        for (const bone of groupList())
          select.append(element("option", { value: bone.uuid }, bone.name));
        select.value = value;
        select.onchange = () => mark(select.value);
        row.append(select);
        button("点选骨骼", () => beginBonePick(key), row, `pick-${key}`);
      } else if (type === "vector") {
        const vectorRow = element("div", { className: "kasuga-row" });
        value.forEach((v, i) => {
          const input = element("input", {
            type: "number",
            value: String(v),
            step: "any",
            title: ["X", "Y", "Z"][i],
          });
          input.oninput = () => {
            const next = panelState.values[key].slice();
            next[i] = input.valueAsNumber;
            mark(next);
          };
          vectorRow.append(input);
        });
        row.append(vectorRow);
      } else if (type === "select") {
        const select = element("select");
        for (const [v, name] of Object.entries(options))
          select.append(element("option", { value: v }, name));
        select.value = value;
        select.onchange = () => mark(select.value);
        row.append(select);
      } else {
        const input = element(
          "input",
          type === "boolean"
            ? { type: "checkbox", checked: value }
            : { type: "number", value: String(value), step: "any" },
        );
        input.onchange = input.oninput = () =>
          mark(type === "boolean" ? input.checked : input.valueAsNumber);
        row.append(input);
      }
      root.append(row);
    }
    const apply = element("div", { className: "kasuga-row kasuga-apply" });
    button(
      `应用到 ${bones.length} 根骨骼`,
      () => commitConfig(),
      apply,
      "apply",
    ).disabled = !bones.length;
    button("清除勾选", () => refreshConfig(true), apply, "reset");
    root.append(apply);
    const sync = element("div", { className: "kasuga-row" });
    button("自动同步设置", configureSync, sync, "sync-config");
    button("立即同步", () => syncToGame(Project), sync, "sync-now");
    root.append(sync);
    root.append(
      element(
        "p",
        { className: "kasuga-hint" },
        syncConfigs.get(Project)?.enabled
          ? "保存后自动同步已开启"
          : "同步未开启 · 游戏侧 /kasuga_models live start",
      ),
    );
    rebuildEditor();
  }
  function commitConfig(
    change = panelState,
    ids = panelState.bones,
    mode = configMode,
  ) {
    if (!Object.values(change.fields).some(Boolean))
      throw Error("请勾选要应用的参数");
    stopPreview();
    const before = capture(),
      data = applyComponentBatch(before, ids, mode, change),
      changed = data.bones.filter(
        (bone) =>
          JSON.stringify(bone) !==
          JSON.stringify(before.bones.find((old) => old.id === bone.id)),
      ),
      groups = changed.map((bone) =>
        groupList().find((g) => g.uuid === bone.id),
      );
    if (groups.some((group) => !group)) throw Error("骨骼已删除，请重新选择");
    Undo.initEdit({ groups, outliner: !!change.fields.parent_group });
    for (const group of groups) {
      const bone = data.bones.find((b) => b.id === group.uuid);
      group.kasuga_components = clone(bone.components || {});
      group.origin.splice(0, 3, ...bone.pivot);
      if (change.fields.parent_group && ids.includes(group.uuid))
        group.addTo(
          bone.parent == null
            ? "root"
            : groupList().find((g) => g.uuid === bone.parent),
        );
    }
    Undo.finishEdit(`批量编辑 Kasuga ${mode === "ik" ? "IK" : "物理"}`);
    Project.saved = false;
    Canvas.updateAll();
    refreshConfig(true);
  }
  function beginBonePick(field) {
    if (!panelState.bones.length) throw Error("先选中要配置的骨骼");
    picking = { field, bones: panelState.bones.slice(), project: Project };
    rebuildEditor();
    configPanel.node.querySelector(".kasuga-status").textContent =
      "请点击目标骨骼的菱形、体块或骨骼树；Esc 取消";
  }
  function acceptBonePick(bone) {
    if (!picking || picking.project !== Project) return false;
    const { field, bones } = picking;
    picking = null;
    if (
      ["parent_group", "parent_body"].includes(field) &&
      bones.includes(bone.uuid)
    )
      throw Error("不能把自己设为父级");
    panelState.values[field] = bone.uuid;
    panelState.fields[field] = true;
    const row = configPanel.node.querySelector(`[data-field="${field}"]`);
    row.querySelector("select").value = bone.uuid;
    row.querySelector("input").checked = true;
    selectBoneSet(bones);
    selectionKey = Project.uuid + ":" + configMode + ":" + bones.join();
    configPanel.node.querySelector(".kasuga-status").textContent =
      `已拾取 ${bone.name}；点击“应用”写入 ${bones.length} 根骨骼`;
    rebuildEditor();
    return true;
  }
  function selectBoneSet(ids) {
    unselectAllElements();
    Group.multi_selected.splice(0);
    for (const g of groupList()) g.selected = false;
    for (const id of ids) {
      const g = groupList().find((bone) => bone.uuid === id);
      if (g) {
        g.selected = true;
        Group.multi_selected.push(g);
      }
    }
    updateSelection();
  }
  function enterConfigMode(mode) {
    configMode = mode;
    Modes.options[`kasuga_${mode}_mode`].select();
    configPanel.folded = false;
    configPanel.update();
    refreshConfig(true);
  }
  function createRodControls() {
    const rod = selectedBones()[0];
    if (!rod) throw Error("先选中作为连杆的组");
    stopPreview();
    validate(capture());
    const elements = [],
      groups = [rod],
      points = [];
    rod.forEachChild((node) => {
      if (node instanceof Cube) points.push(node.from, node.to);
      else if (node.vertices) {
        const rotation = new THREE.Euler(
          ...node.rotation.map((v) => (v * Math.PI) / 180),
          "ZYX",
        );
        for (const point of Object.values(node.vertices))
          points.push(
            new THREE.Vector3()
              .fromArray(point)
              .applyEuler(rotation)
              .add(new THREE.Vector3().fromArray(node.origin))
              .toArray(),
          );
      }
    });
    if (!points.length) throw Error("该组没有几何体；请手动创建杆端并设置枢轴");
    const tipPosition = points.reduce(
      (best, p) =>
        distance(p, rod.origin) > distance(best, rod.origin) ? p : best,
      rod.origin,
    );
    if (distance(tipPosition, rod.origin) < 0.001)
      throw Error("杆长为零，请先设置连杆枢轴");
    // The estimated endpoint is editable with the viewport gizmo; the original geometry is preserved.
    Undo.initEdit({ groups, elements, outliner: true });
    const unique = (base) => {
      let name = base,
        i = 2;
      while (groupList().some((g) => g.name === name)) name = `${base}_${i++}`;
      return name;
    };
    const tip = new Group({
      name: unique(`${rod.name}_杆端`),
      origin: tipPosition.slice(),
    })
      .addTo(rod)
      .init();
    const target = new Group({
      name: unique(`${rod.name}_IK目标`),
      origin: tipPosition.slice(),
    }).init();
    groups.push(tip, target);
    tip.kasuga_components = {
      ik: { ...DEFAULT_IK, target: target.uuid, links: [{ bone: rod.uuid }] },
    };
    Undo.finishEdit("创建连杆 IK 控制点（杆端需校准）");
    Project.saved = false;
    selectBoneSet([tip.uuid]);
    refreshConfig(true);
    configPanel.node.querySelector(".kasuga-status").textContent =
      "已创建杆端和目标。请先把杆端菱形移到真实销轴中心，再拖目标预览；刚性连杆不自动伸缩。";
  }
  function disposeEditor() {
    if (!editor) return;
    editor.overlay.parent?.remove(editor.overlay);
    editor.overlay.traverse((object) => {
      object.geometry?.dispose();
      object.material?.dispose();
    });
    editor = null;
  }
  function bodyGeometry(shape, size) {
    if (shape === "sphere") return new THREE.SphereGeometry(size[0], 20, 12);
    if (shape !== "capsule")
      return new THREE.BoxGeometry(...size.map((v) => v * 2));
    const profile = [];
    for (let i = 0; i <= 8; i++) {
      const angle = -Math.PI / 2 + (i * Math.PI) / 16;
      profile.push(
        new THREE.Vector2(
          size[0] * Math.cos(angle),
          -size[1] / 2 + size[0] * Math.sin(angle),
        ),
      );
    }
    for (let i = 0; i <= 8; i++) {
      const angle = (i * Math.PI) / 16;
      profile.push(
        new THREE.Vector2(
          size[0] * Math.cos(angle),
          size[1] / 2 + size[0] * Math.sin(angle),
        ),
      );
    }
    return new THREE.LatheGeometry(profile, 20);
  }
  function rebuildEditor() {
    if (drag) return;
    disposeEditor();
    if (!inConfigMode()) return;
    const overlay = new THREE.Group();
    overlay.name = "Kasuga configuration handles";
    overlay.no_export = true;
    Canvas.scene.add(overlay);
    editor = {
      overlay,
      project: Project,
      bones: [],
      bodies: [],
      handles: [],
      handleRoot: new THREE.Group(),
    };
    const makeMesh = (geometry, color, opacity = 1, wireframe = false) => {
      const mesh = new THREE.Mesh(
        geometry,
        new THREE.MeshBasicMaterial({
          color,
          transparent: true,
          opacity,
          wireframe,
          depthTest: false,
          depthWrite: false,
        }),
      );
      mesh.renderOrder = 1000;
      return mesh;
    };
    const related = new Set(selectedBones());
    for (const selected of selectedBones()) {
      if (selected.parent instanceof Group) related.add(selected.parent);
      for (const child of selected.children)
        if (child instanceof Group) related.add(child);
      const ids = [
        selected.kasuga_components?.ik?.target,
        selected.kasuga_components?.physics?.parent_body,
        ...(selected.kasuga_components?.ik?.links || []).map(
          (link) => link.bone,
        ),
      ];
      ids.forEach((id) => {
        const match = groupList().find((g) => g.uuid === id);
        if (match) related.add(match);
      });
    }
    for (const bone of groupList().filter(
      (g) =>
        g.visibility !== false &&
        g.export !== false &&
        (!focusBones || picking || related.has(g)),
    )) {
      const mesh = makeMesh(
        new THREE.OctahedronGeometry(1),
        selectedBones().includes(bone) ? 0xffc857 : 0x6bd6ee,
        0.72,
      );
      mesh.userData = { kind: "bone", bone };
      overlay.add(mesh);
      editor.bones.push(mesh);
      const p = bone.kasuga_components?.physics;
      if (configMode === "physics" && p) {
        const body = makeMesh(
          bodyGeometry(p.shape ?? "capsule", p.size ?? [1, 4, 1]),
          0x5ce4ac,
          0.85,
          true,
        );
        body.userData = { kind: "body", bone };
        overlay.add(body);
        editor.bodies.push(body);
      }
    }
    const selected = selectedBones(),
      first = selected[0];
    let controlled = first;
    if (configMode === "ik" && (handleMode === "target" || preview))
      controlled =
        groupList().find(
          (g) => g.uuid === first?.kasuga_components?.ik?.target,
        ) || first;
    editor.controlled = controlled;
    overlay.add(editor.handleRoot);
    if (controlled) {
      const colors = [0xf05e5e, 0x63d673, 0x60a0ff];
      for (let axis = 0; axis < 3; axis++) {
        const direction = new THREE.Vector3().setComponent(axis, 1),
          rotating = configMode === "physics" && handleMode === "rotate",
          mesh = makeMesh(
            rotating
              ? new THREE.TorusGeometry(4.2, 0.16, 8, 48)
              : new THREE.ConeGeometry(0.45, 1.2, 12),
            colors[axis],
          );
        mesh.quaternion.setFromUnitVectors(
          rotating ? new THREE.Vector3(0, 0, 1) : new THREE.Vector3(0, 1, 0),
          direction,
        );
        if (!rotating) {
          mesh.position.copy(direction).multiplyScalar(5);
          const shaft = makeMesh(
            new THREE.CylinderGeometry(0.09, 0.09, 4.5, 8),
            colors[axis],
          );
          shaft.position.copy(direction).multiplyScalar(2.25);
          shaft.quaternion.copy(mesh.quaternion);
          shaft.userData = { kind: "handle", axis };
          editor.handleRoot.add(shaft);
          editor.handles.push(shaft);
        }
        mesh.userData = { kind: "handle", axis };
        editor.handleRoot.add(mesh);
        editor.handles.push(mesh);
      }
    }
    updateEditor();
  }
  const worldPoint = (bone) => bone.mesh.getWorldPosition(new THREE.Vector3());
  function updateEditor() {
    if (!editor) return;
    if (!inConfigMode() || editor.project !== Project) {
      disposeEditor();
      return;
    }
    Canvas.scene.updateMatrixWorld(true);
    const camera = Preview.selected;
    for (const mesh of editor.bones) {
      const bone = mesh.userData.bone;
      if (!bone.mesh) continue;
      const start = worldPoint(bone),
        child = bone.children.find(
          (c) =>
            c instanceof Group &&
            c.mesh &&
            distance(c.origin, bone.origin) > 0.1,
        ),
        end = child
          ? worldPoint(child)
          : start
              .clone()
              .add(
                new THREE.Vector3(0, 1.5, 0).applyQuaternion(
                  bone.mesh.getWorldQuaternion(new THREE.Quaternion()),
                ),
              ),
        diff = end.clone().sub(start),
        len = Math.max(0.2, diff.length()),
        width = Math.max(0.35, Math.min(1.6, len * 0.13));
      mesh.position.copy(start).add(end).multiplyScalar(0.5);
      mesh.scale.set(width, len / 2, width);
      if (diff.lengthSq() > 1e-9)
        mesh.quaternion.setFromUnitVectors(
          new THREE.Vector3(0, 1, 0),
          diff.normalize(),
        );
    }
    for (const body of editor.bodies) {
      const bone = body.userData.bone,
        p = bone.kasuga_components.physics;
      body.position.copy(
        bone.mesh.localToWorld(
          new THREE.Vector3().fromArray(p.position ?? [0, 4, 0]),
        ),
      );
      body.quaternion
        .copy(bone.mesh.getWorldQuaternion(new THREE.Quaternion()))
        .multiply(
          new THREE.Quaternion().setFromEuler(
            new THREE.Euler(
              ...(p.rotation ?? [0, 0, 0]).map((v) => (v * Math.PI) / 180),
              "ZYX",
            ),
          ),
        );
    }
    const controlled = editor.controlled;
    if (controlled?.mesh) {
      const p =
        configMode === "physics" ? controlled.kasuga_components?.physics : null;
      editor.handleRoot.position.copy(
        p
          ? controlled.mesh.localToWorld(
              new THREE.Vector3().fromArray(p.position ?? [0, 4, 0]),
            )
          : worldPoint(controlled),
      );
      editor.handleRoot.quaternion.copy(
        configMode === "physics"
          ? controlled.mesh.getWorldQuaternion(new THREE.Quaternion())
          : new THREE.Quaternion(),
      );
      editor.handleRoot.scale.setScalar(
        camera
          ? Math.max(
              0.3,
              camera.calculateControlScale(editor.handleRoot.position) * 1.35,
            )
          : 1,
      );
    }
    editor.overlay.updateMatrixWorld(true);
  }
  function previewAtEvent(event) {
    return Object.values(Preview.all).find(
      (p) => p.canvas === event.target || p.canvas.contains(event.target),
    );
  }
  function rayForEvent(view, event) {
    const rect = view.canvas.getBoundingClientRect(),
      raycaster = new THREE.Raycaster();
    raycaster.setFromCamera(
      new THREE.Vector2(
        ((event.clientX - rect.left) / rect.width) * 2 - 1,
        (-(event.clientY - rect.top) / rect.height) * 2 + 1,
      ),
      view.camera,
    );
    if (view.isOrtho)
      raycaster.ray.origin
        .set(
          ((event.clientX - rect.left) / rect.width) * 2 - 1,
          (-(event.clientY - rect.top) / rect.height) * 2 + 1,
          -1,
        )
        .unproject(view.camera);
    return raycaster;
  }
  function pointerDown(event) {
    if (!inConfigMode() || event.button !== 0 || drag) return;
    const view = previewAtEvent(event);
    if (!view || !editor) return;
    updateEditor();
    const ray = rayForEvent(view, event),
      handles = picking ? [] : ray.intersectObjects(editor.handles, false),
      hits = ray.intersectObjects([...editor.bones, ...editor.bodies], false),
      hit = handles[0] || hits[0];
    let bone = hit?.object.userData.bone;
    if (!hit) {
      const data = view.raycast(event);
      bone = sourceGroup(data?.element);
    }
    if (!hit && !bone) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    Preview.selected = view;
    if (picking) {
      if (bone) acceptBonePick(bone);
      return;
    }
    if (!handles.length && bone) {
      const ids =
        event.ctrlKey || event.metaKey || event.shiftKey
          ? selectedBones().map((g) => g.uuid)
          : [];
      if (ids.includes(bone.uuid)) ids.splice(ids.indexOf(bone.uuid), 1);
      else ids.push(bone.uuid);
      selectBoneSet(ids);
      refreshConfig(true);
      if (
        event.ctrlKey ||
        event.metaKey ||
        event.shiftKey ||
        configMode === "physics"
      )
        return;
    }
    const controlled = handles.length ? editor.controlled : bone;
    if (!controlled) return;
    beginDrag(
      controlled,
      view,
      event,
      handles.length ? hit.object.userData.axis : -1,
    );
  }
  function beginDrag(bone, view, event, axis) {
    const wasPreview = !!preview;
    stopPreview();
    updateEditor();
    const physics = configMode === "physics";
    if (physics && !bone.kasuga_components?.physics)
      throw Error("先启用该骨骼的刚体");
    const groups = physics ? selectedBones() : [bone],
      origin = physics
        ? bone.mesh.localToWorld(
            new THREE.Vector3().fromArray(
              bone.kasuga_components.physics.position ?? [0, 4, 0],
            ),
          )
        : worldPoint(bone),
      normal = new THREE.Vector3(),
      worldAxis = axis < 0 ? null : new THREE.Vector3().setComponent(axis, 1);
    if (physics && groups.some((group) => !group.kasuga_components?.physics))
      throw Error("批量拖动前，请为所有选中骨骼启用刚体");
    if (physics && worldAxis)
      worldAxis.applyQuaternion(
        bone.mesh.getWorldQuaternion(new THREE.Quaternion()),
      );
    view.camera.getWorldDirection(normal);
    const rotating = physics && handleMode === "rotate";
    if (worldAxis && !rotating) {
      normal.addScaledVector(worldAxis, -normal.dot(worldAxis));
      if (normal.lengthSq() < 1e-6) {
        normal.set(0, 1, 0);
        if (Math.abs(normal.dot(worldAxis)) > 0.9) normal.set(1, 0, 0);
      }
    }
    if (rotating && worldAxis) normal.copy(worldAxis);
    normal.normalize();
    const plane = new THREE.Plane().setFromNormalAndCoplanarPoint(
        normal,
        origin,
      ),
      start = rayForEvent(view, event).ray.intersectPlane(
        plane,
        new THREE.Vector3(),
      );
    if (!start) return;
    Undo.initEdit({ groups });
    drag = {
      bone,
      view,
      axis,
      worldAxis,
      plane,
      start,
      origin,
      physics,
      rotating,
      project: Project,
      wasPreview,
      groups,
      originals: groups.map((g) => ({
        group: g,
        origin: g.origin.slice(),
        components: clone(g.kasuga_components || {}),
      })),
      moved: false,
    };
  }
  function pointerMove(event) {
    if (!drag) return;
    if (Project !== drag.project) {
      endDrag(false);
      return;
    }
    const d = drag,
      point = rayForEvent(d.view, event).ray.intersectPlane(
        d.plane,
        new THREE.Vector3(),
      );
    if (!point) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    let delta = point.clone().sub(d.start);
    if (d.worldAxis && !d.rotating)
      delta.copy(d.worldAxis).multiplyScalar(delta.dot(d.worldAxis));
    d.moved ||= delta.lengthSq() > 0.000001;
    for (const original of d.originals) {
      const bone = original.group;
      if (d.physics) {
        const p = clone(original.components.physics),
          localDelta = delta
            .clone()
            .applyQuaternion(
              bone.mesh.getWorldQuaternion(new THREE.Quaternion()).invert(),
            );
        if (d.rotating) {
          const a = d.start.clone().sub(d.origin).normalize(),
            b = point.clone().sub(d.origin).normalize(),
            angle = Math.atan2(d.worldAxis.dot(a.clone().cross(b)), a.dot(b));
          p.rotation ||= [0, 0, 0];
          p.rotation[d.axis] += (angle * 180) / Math.PI;
          d.moved ||= Math.abs(angle) > 0.00001;
        } else if (handleMode === "scale") {
          p.size ||= [1, 4, 1];
          if (d.axis >= 0)
            p.size[d.axis] = Math.max(
              0.01,
              p.size[d.axis] + localDelta.getComponent(d.axis),
            );
        } else p.position = add(p.position ?? [0, 4, 0], localDelta.toArray());
        bone.kasuga_components = { ...clone(original.components), physics: p };
      } else {
        const parentQ =
          bone.mesh.parent?.getWorldQuaternion(new THREE.Quaternion()) ??
          new THREE.Quaternion();
        bone.origin.splice(
          0,
          3,
          ...add(
            original.origin,
            delta.clone().applyQuaternion(parentQ.invert()).toArray(),
          ),
        );
      }
    }
    Canvas.updateAll();
    updateEditor();
  }
  function endDrag(commit = true) {
    if (!drag) return;
    const d = drag;
    drag = null;
    let failure;
    try {
      if (commit && d.moved) validate(capture());
    } catch (error) {
      failure = error;
      commit = false;
    }
    if (!commit || !d.moved) Undo.cancelEdit(true);
    else {
      Undo.finishEdit("拖动 Kasuga 配置手柄");
      Project.saved = false;
    }
    Canvas.updateAll();
    refreshConfig(true);
    rebuildEditor();
    if (d.wasPreview && commit) startPreview();
    if (failure) throw failure;
  }
  function configureSync() {
    if (!isApp) throw Error("自动同步需要 Blockbench 桌面版的本地文件权限");
    const project = Project,
      previous = syncConfigs.get(project) || {},
      directory = Blockbench.pickDirectory({
        title: "选择游戏 resourcepacks/kasuga_live 目录",
        startpath: previous.directory,
      });
    if (!directory) return;
    new Dialog({
      id: "kasuga_live_sync",
      title: "保存后同步到游戏",
      form: {
        enabled: {
          type: "checkbox",
          label: "保存后自动同步",
          value: previous.enabled !== false,
        },
        resource: {
          type: "text",
          label: "游戏模型资源 ID",
          value: previous.resource || "kasuga_live:models/preview.bbmodel",
        },
        format: {
          type: "select",
          label: "同步格式",
          value: previous.format || "project",
          options: {
            project: "bbmodel（推荐，纹理内嵌）",
            java_block: "Java 方块 / 物品 JSON",
            bedrock: "基岩版 .geo.json",
          },
        },
      },
      onConfirm(result) {
        guarded(() => {
          if (Project !== project) throw Error("项目已切换，请重新配置");
          const config = { ...result, directory };
          syncToGame(project, config);
          syncConfigs.set(project, config);
          persistSync(project);
          this.hide();
          refreshConfig(true);
        })();
      },
    }).show();
  }
  function syncKey(project) {
    return project?.save_path ? "kasuga_live:" + project.save_path : null;
  }
  function persistSync(project) {
    const key = syncKey(project),
      config = syncConfigs.get(project);
    if (key && config) localStorage.setItem(key, JSON.stringify(config));
  }
  function restoreSync() {
    if (!Project || syncConfigs.has(Project) || !isApp) return;
    const key = syncKey(Project);
    if (!key) return;
    try {
      const config = JSON.parse(localStorage.getItem(key) || "null");
      if (config) syncConfigs.set(Project, config);
    } catch (error) {
      console.warn("[Kasuga] Invalid local sync settings", error);
    }
  }
  function syncToGame(project, config = syncConfigs.get(project)) {
    if (!config) throw Error("先设置自动同步目录和游戏模型资源 ID");
    if (project !== Project) return;
    validate(capture());
    const format = config.format || "project";
    if (
      format === "project"
        ? !config.resource.endsWith(".bbmodel")
        : !config.resource.endsWith(".json")
    )
      throw Error("模型资源 ID 的扩展名与同步格式不一致");
    if (format === "java_block" && Format.id !== "java_block")
      throw Error("Java 同步需要 Java 方块 / 物品项目");
    if (format === "bedrock" && !Format.id.startsWith("bedrock"))
      throw Error("基岩同步需要基岩版项目");
    const nodeFs = require("fs"),
      nodePath = require("path"),
      model = Codecs[format].compile({ raw: true });
    const path = writeLivePack(nodeFs, nodePath, config, model);
    Blockbench.showQuickMessage(`已同步 ${config.resource}`);
    return path;
  }
  function installConfigUI() {
    const style = Blockbench.addCSS(`
      #panel_kasuga_components {padding:8px;overflow:auto;min-width:230px}
      #panel_kasuga_components .kasuga-row {display:flex;gap:5px;flex-wrap:wrap;margin:6px 0}
      #panel_kasuga_components .kasuga-row input {width:29%;min-width:0;flex:1}
      #panel_kasuga_components .kasuga-field {padding:6px 0;border-top:1px solid var(--color-border)}
      #panel_kasuga_components .kasuga-label {display:block;margin-bottom:5px;font-size:12px}
      #panel_kasuga_components .kasuga-label input {margin-right:6px}
      #panel_kasuga_components .kasuga-status {color:var(--color-accent);padding:6px 0;white-space:normal}
      #panel_kasuga_components .kasuga-hint {font-size:11px;opacity:.72;white-space:normal}
      #panel_kasuga_components select {max-width:100%;margin-right:5px}
      #panel_kasuga_components .kasuga-apply {position:sticky;bottom:0;background:var(--color-back);padding:6px 0}
      .kasuga-particle-palette .sp-palette {width:188px;overflow:visible;display:grid;grid-template-columns:repeat(6,28px);gap:2px}
      .kasuga-particle-palette .sp-palette-row {display:flex;clear:both}
      .kasuga-particle-palette .sp-thumb-el {position:relative;flex:none;float:none}
    `);
    configDisposers.push(() => style.delete());
    const tool = new Tool("kasuga_config_tool", {
      name: "Kasuga 骨骼配置",
      icon: "account_tree",
      modes: ["kasuga_ik_mode", "kasuga_physics_mode"],
      transformerMode: "hidden",
      selectElements: false,
    });
    configDisposers.push(() => tool.delete());
    for (const mode of ["ik", "physics"])
      modes.push(
        new Mode(`kasuga_${mode}_mode`, {
          name: mode === "ik" ? "IK 配置模式" : "物理配置模式",
          icon: mode === "ik" ? "account_tree" : "sports_baseball",
          default_tool: tool.id,
          selectElements: false,
          condition: () => !!Project && !Format.image_editor,
          onSelect() {
            configMode = mode;
            refreshConfig(true);
          },
          onUnselect() {
            endDrag(false);
            picking = null;
            stopPreview();
            disposeEditor();
          },
        }),
      );
    // Runtime plugin modes are added after Vue has observed the native mode list.
    if (Modes.vue)
      for (const mode of modes) {
        delete Modes.options[mode.id];
        Modes.vue.$set(Modes.options, mode.id, mode);
      }
    const outliner = Interface.Panels.outliner,
      oldCondition = outliner.condition;
    outliner.condition = () => inConfigMode() || Condition(oldCondition);
    configDisposers.push(() => {
      outliner.condition = oldCondition;
    });
    configPanel = new Panel("kasuga_components", {
      name: "Kasuga 骨骼组件",
      icon: "tune",
      condition: () => !!Project && (inConfigMode() || Modes.edit),
      resizable: true,
      growable: true,
      expand_button: true,
      default_position: { slot: "left_bar", height: 600, sidebar_index: 0 },
      min_height: 240,
    });
    const addDomListener = (type, fn) => {
      document.addEventListener(type, fn, true);
      configDisposers.push(() => document.removeEventListener(type, fn, true));
    };
    addDomListener("pointerdown", (event) =>
      panelGuard(() => pointerDown(event)),
    );
    addDomListener("pointermove", (event) =>
      panelGuard(() => pointerMove(event)),
    );
    addDomListener("pointerup", (event) => {
      if (drag) {
        event.preventDefault();
        event.stopImmediatePropagation();
        panelGuard(() => endDrag(true));
      }
    });
    addDomListener("pointercancel", () => panelGuard(() => endDrag(false)));
    addDomListener("keydown", (event) => {
      if (event.key === "Escape" && (picking || drag)) {
        event.preventDefault();
        event.stopImmediatePropagation();
        picking = null;
        endDrag(false);
        refreshConfig(true);
      }
    });
    const blur = () => panelGuard(() => endDrag(false));
    window.addEventListener("blur", blur);
    configDisposers.push(() => window.removeEventListener("blur", blur));
    listen(Blockbench, "update_selection", () => {
      if (!Project || drag) return;
      if (picking) {
        const candidate = selectedBones().find(
          (g) => !picking.bones.includes(g.uuid),
        );
        if (candidate) {
          acceptBonePick(candidate);
          return;
        }
      }
      refreshConfig();
    });
    for (const event of [
      "finished_edit",
      "undo",
      "redo",
      "select_project",
      "load_project",
    ])
      listen(Blockbench, event, () => {
        if (!drag && !picking) {
          restoreSync();
          refreshConfig(true);
        }
      });
    for (const event of ["unselect_project", "close_project"])
      listen(Blockbench, event, () => {
        picking = null;
        endDrag(false);
        disposeEditor();
        selectionKey = "";
      });
    for (const codec of [
      Codecs.project,
      Codecs.java_block,
      Codecs.bedrock,
    ].filter(Boolean)) {
      const old = codec.afterSave;
      const wrapped = function (...args) {
        const result = old.apply(this, args),
          project = Project;
        restoreSync();
        persistSync(project);
        if (syncConfigs.get(project)?.enabled)
          guarded(() => syncToGame(project))();
        return result;
      };
      codec.afterSave = wrapped;
      configDisposers.push(() => {
        if (codec.afterSave === wrapped) codec.afterSave = old;
      });
    }
    refreshConfig(true);
  }
  let emitterDialog;
  function editEmitter() {
    const node = Locator.selected?.find((l) => l.kasuga_emitter?.particle);
    if (!node) throw Error("请先选中 Kasuga 粒子源定位点。");
    stopPreview();
    validate(capture());
    const e = node.kasuga_emitter,
      p = e.particle || {};
    emitterDialog?.hide();
    emitterDialog?.delete();
    const colorpicker = new ColorPicker({
      id: "kasuga_particle_color",
      name: "粒子颜色",
      private: true,
      palette: true,
      value: p.color ?? "#ff8844",
    });
    colorpicker.jq.spectrum("option", "showAlpha", false);
    colorpicker.jq.spectrum(
      "option",
      "containerClassName",
      "kasuga-particle-palette",
    );
    colorpicker.jq.spectrum("option", "palette", [
      ["#ffffff", "#aaaaaa", "#333333", "#ff8844", "#ff4444", "#ffdd55"],
      ["#55dd88", "#22aaff", "#6666ff", "#aa55ff", "#ff55cc", "#88eeee"],
    ]);
    const paletteNode = colorpicker.jq
      .spectrum("container")[0]
      .querySelector(".sp-palette");
    const flattenPalette = () => {
      const swatches = [...paletteNode.querySelectorAll(".sp-thumb-el")];
      if (swatches.every((swatch) => swatch.parentNode === paletteNode)) return;
      // Some host Spectrum builds parse their self-closing span template as nested swatches.
      // Normalize only our picker, using actual DOM nodes so every preset remains clickable.
      paletteNode.replaceChildren(
        ...swatches.map((swatch) => {
          const node = element("span", {
            className: swatch.className,
            title: swatch.title,
          });
          node.dataset.color = swatch.dataset.color;
          node.dataset.index = swatch.dataset.index;
          const inner = element("span", { className: "sp-thumb-inner" });
          inner.style.backgroundColor = swatch.dataset.color;
          node.append(inner);
          return node;
        }),
      );
    };
    const paletteObserver = new MutationObserver(flattenPalette);
    paletteObserver.observe(paletteNode, { childList: true });
    flattenPalette();
    emitterDialog = new Dialog({
      id: "kasuga_emitter",
      title: `粒子源 · ${node.name}`,
      form: {
        enabled: {
          label: "启用",
          type: "checkbox",
          value: e.enabled !== false,
        },
        rate: {
          label: "每秒发射数量",
          type: "number",
          value: e.rate ?? 20,
          min: 0,
          max: 10000,
        },
        max_particles: {
          label: "最大存活数",
          type: "number",
          value: e.max_particles ?? 256,
          min: 1,
          max: 4096,
        },
        space: {
          label: "粒子运动空间",
          type: "select",
          options: { world: "发射后独立运动", local: "跟随粒子源" },
          value: e.space ?? "world",
        },
        texture: {
          label: "游戏粒子纹理资源 ID",
          type: "text",
          value: p.texture ?? "",
        },
        lifetime: {
          label: "寿命（秒）",
          type: "number",
          value: p.lifetime ?? 1,
          min: 0.01,
          max: 60,
        },
        speed: {
          label: "速度（像素/秒）",
          type: "number",
          value: p.speed ?? 8,
          min: 0,
          max: 10000,
        },
        spread: {
          label: "喷射锥半角（度，+Y 方向）",
          type: "number",
          value: p.spread ?? 15,
          min: 0,
          max: 180,
        },
        size: {
          label: "粒子尺寸（像素）",
          type: "number",
          value: p.size ?? 1,
          min: 0.01,
          max: 256,
        },
        color: {
          label: "颜色 / 调色板（也可输入 HEX）",
          type: "color",
          colorpicker,
          value: p.color ?? "#ff8844",
        },
        gravity: {
          label: "加速度（像素/秒²）",
          type: "vector",
          dimensions: 3,
          value: p.gravity ?? [0, 0, 0],
        },
      },
      onConfirm(result) {
        guarded(() => {
          const { enabled, rate, max_particles, space, ...particle } = result;
          particle.color =
            typeof particle.color === "string"
              ? particle.color
              : particle.color.toHexString();
          saveNode(
            node,
            "kasuga_emitter",
            {
              ...e,
              enabled,
              rate,
              max_particles,
              space,
              particle: { ...p, ...particle },
            },
            "编辑 Kasuga 粒子源",
          );
          this.hide();
        })();
      },
    });
    const oldDelete = emitterDialog.delete;
    emitterDialog.delete = function () {
      paletteObserver.disconnect();
      colorpicker.delete();
      oldDelete.call(this);
    };
    emitterDialog.show();
  }
  function addEmitter() {
    stopPreview();
    validate(capture());
    const parent = Group.first_selected,
      objects = [];
    Undo.initEdit({ elements: objects, outliner: true });
    const l = new Locator({
      name: "particle_emitter",
      position: parent?.origin.slice() ?? [0, 0, 0],
      kasuga_emitter: {
        enabled: true,
        rate: 20,
        max_particles: 256,
        space: "world",
        particle: {
          lifetime: 1,
          speed: 8,
          spread: 15,
          size: 1,
          color: "#ff8844",
          gravity: [0, 0, 0],
        },
      },
    })
      .addTo(parent || "root")
      .init();
    l.select();
    objects.push(l);
    Undo.finishEdit("新增 Kasuga 粒子源");
    editEmitter();
  }
  function editJson() {
    stopPreview();
    const node = Group.first_selected || Locator.selected?.[0];
    if (!node) throw Error("请先选择骨骼或粒子源。");
    const property =
      node instanceof Group ? "kasuga_components" : "kasuga_emitter";
    new Dialog({
      id: "kasuga_json",
      title: "组件 JSON",
      form: {
        json: {
          type: "textarea",
          label: "高级参数（保留未知字段）",
          value: JSON.stringify(node[property] || {}, null, 2),
        },
      },
      onConfirm(result) {
        guarded(() => {
          saveNode(
            node,
            property,
            JSON.parse(result.json),
            "编辑 Kasuga 组件 JSON",
          );
          this.hide();
        })();
      },
    }).show();
  }
  function stopPreview() {
    if (!preview) return;
    const old = preview;
    preview = null;
    old.restore();
    old.restoreHierarchy();
    old.overlay.removeFromParent
      ? old.overlay.removeFromParent()
      : old.overlay.parent?.remove(old.overlay);
    old.overlay.traverse((obj) => {
      obj.geometry?.dispose();
      if (obj.material) obj.material.dispose();
    });
    old.texture.dispose();
  }
  function startPreview() {
    if (preview) {
      stopPreview();
      return;
    }
    const data = validate(capture()),
      project = Project,
      groups = new Map(groupList().map((g) => [g.uuid, g]));
    const hierarchy = [];
    // Java's editor keeps groups flat. Temporarily parent preview objects as a rig,
    // preserving their original scene parents and transforms for stop/export/Undo.
    if (!Format.bone_rig) {
      for (const node of [...groupList(), ...Outliner.elements])
        if (node.mesh) {
          const obj = node.mesh;
          hierarchy.push({
            obj,
            parent: obj.parent,
            position: obj.position.clone(),
            quaternion: obj.quaternion.clone(),
            scale: obj.scale.clone(),
          });
        }
      for (const node of [...groupList(), ...Outliner.elements])
        if (node.mesh) {
          const parent = sourceGroup(node);
          (parent?.mesh ?? Project.model_3d).add(node.mesh);
          if (node instanceof Group) node.mesh.position.fromArray(node.origin);
          if (parent)
            node.mesh.position.sub(
              new THREE.Vector3().fromArray(parent.origin),
            );
        }
    }
    const restoreHierarchy = () => {
      for (const item of hierarchy) {
        item.parent?.add(item.obj);
        item.obj.position.copy(item.position);
        item.obj.quaternion.copy(item.quaternion);
        item.obj.scale.copy(item.scale);
        item.obj.updateMatrixWorld(true);
      }
    };
    const overlay = new THREE.Group();
    overlay.name = "Kasuga component preview";
    overlay.no_export = true;
    Canvas.scene.add(overlay);
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 32;
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "white";
    ctx.beginPath();
    ctx.arc(16, 16, 15, 0, Math.PI * 2);
    ctx.fill();
    const texture = new THREE.CanvasTexture(canvas);
    const particles = new Map(),
      bodies = new Map(),
      poses = new Map();
    for (const e of data.emitters) {
      const positions = new Float32Array((e.max_particles ?? 256) * 3),
        geometry = new THREE.BufferGeometry();
      geometry.setAttribute(
        "position",
        new THREE.BufferAttribute(positions, 3),
      );
      geometry.setDrawRange(0, 0);
      const material = new THREE.PointsMaterial({
        color: e.particle.color ?? "#ff8844",
        size: e.particle.size ?? 1,
        map: texture,
        transparent: true,
        alphaTest: 0.1,
        depthWrite: false,
        sizeAttenuation: true,
      });
      const points = new THREE.Points(geometry, material);
      points.frustumCulled = false;
      overlay.add(points);
      particles.set(e.id, {
        state: particleState(),
        points,
        locator: locatorList().find((l) => l.uuid === e.id),
      });
    }
    for (const b of data.bones) {
      const p = b.components?.physics;
      if (!p) continue;
      const size = p.size ?? [1, 4, 1];
      const geo =
        p.shape === "sphere"
          ? new THREE.SphereGeometry(size[0], 12, 8)
          : new THREE.BoxGeometry(
              size[0] * 2,
              p.shape === "capsule" ? size[1] + size[0] * 2 : size[1] * 2,
              size[2] * 2,
            );
      const mesh = new THREE.Mesh(
        geo,
        new THREE.MeshBasicMaterial({
          color: 0x51ccee,
          wireframe: true,
          transparent: true,
          opacity: 0.55,
        }),
      );
      overlay.add(mesh);
      bodies.set(b.id, { state: null, mesh });
    }
    const restore = () => {
      for (const [obj, pose] of poses) {
        if (
          obj.quaternion.equals(pose.appliedQ) &&
          obj.position.equals(pose.appliedP)
        ) {
          obj.quaternion.copy(pose.baseQ);
          obj.position.copy(pose.baseP);
          obj.updateMatrixWorld(true);
        }
      }
      poses.clear();
    };
    const worldPosition = (obj) => obj.getWorldPosition(new THREE.Vector3());
    const applyWorldRotation = (obj, q) => {
      const parentQ =
        obj.parent?.getWorldQuaternion(new THREE.Quaternion()) ??
        new THREE.Quaternion();
      obj.quaternion.premultiply(
        parentQ.clone().invert().multiply(q).multiply(parentQ),
      );
      obj.updateMatrixWorld(true);
    };
    let last = performance.now(),
      accumulator = 0;
    const tick = () => {
      if (Project !== project) {
        stopPreview();
        return;
      }
      const now = performance.now(),
        dt = Math.max(0, Math.min((now - last) / 1000, 0.1));
      last = now;
      accumulator = Math.min(accumulator + dt, 0.1);
      restore();
      for (const g of groups.values()) {
        const obj = g.mesh;
        if (obj)
          poses.set(obj, {
            baseQ: obj.quaternion.clone(),
            baseP: obj.position.clone(),
            appliedQ: obj.quaternion.clone(),
            appliedP: obj.position.clone(),
          });
      }
      Canvas.scene.updateMatrixWorld(true);
      for (const b of data.bones) {
        const ik = b.components?.ik;
        if (!ik) continue;
        const end = groups.get(b.id)?.mesh,
          target = groups.get(ik.target)?.mesh;
        if (!end || !target) continue;
        for (let n = 0; n < (ik.iterations ?? 32); n++) {
          if (
            worldPosition(end).distanceTo(worldPosition(target)) <
            (ik.tolerance ?? 0.01)
          )
            break;
          for (const link of [...ik.links].reverse()) {
            const obj = groups.get(link.bone)?.mesh;
            if (!obj) continue;
            const origin = worldPosition(obj),
              a = worldPosition(end).sub(origin),
              t = worldPosition(target).sub(origin);
            if (a.lengthSq() < 1e-12 || t.lengthSq() < 1e-12) continue;
            a.normalize();
            t.normalize();
            let q = new THREE.Quaternion().setFromUnitVectors(a, t),
              angle = 2 * Math.acos(Math.min(1, Math.abs(q.w))),
              max = ((ik.max_step ?? 45) * Math.PI) / 180;
            if (angle > max) q = new THREE.Quaternion().slerp(q, max / angle);
            applyWorldRotation(obj, q);
            if (link.min) {
              const base = poses.get(obj).baseQ,
                relative = base.clone().invert().multiply(obj.quaternion),
                euler = new THREE.Euler().setFromQuaternion(relative, "XYZ");
              ["x", "y", "z"].forEach(
                (axis, i) =>
                  (euler[axis] = Math.max(
                    (link.min[i] * Math.PI) / 180,
                    Math.min((link.max[i] * Math.PI) / 180, euler[axis]),
                  )),
              );
              obj.quaternion
                .copy(base)
                .multiply(new THREE.Quaternion().setFromEuler(euler));
              obj.updateMatrixWorld(true);
            }
          }
        }
      }
      const steps = Math.min(12, Math.floor((accumulator + 1e-9) * 120));
      accumulator -= steps / 120;
      for (const b of data.bones) {
        const p = b.components?.physics,
          body = bodies.get(b.id),
          obj = groups.get(b.id)?.mesh;
        if (!p || !obj) continue;
        const offset = new THREE.Vector3().fromArray(p.position ?? [0, 4, 0]);
        if (offset.lengthSq() < 1e-9) offset.set(0, 4, 0);
        const origin = worldPosition(obj),
          target = obj.localToWorld(offset.clone());
        if (!body.state)
          body.state = { position: target.toArray(), velocity: [0, 0, 0] };
        if (p.mode !== "kinematic") {
          for (let n = 0; n < steps; n++)
            springStep(body.state, target.toArray(), p, 1 / 120);
          const dir = new THREE.Vector3()
            .fromArray(body.state.position)
            .sub(origin);
          if (dir.lengthSq() > 1e-12)
            applyWorldRotation(
              obj,
              new THREE.Quaternion().setFromUnitVectors(
                target.sub(origin).normalize(),
                dir.normalize(),
              ),
            );
        } else {
          body.state.position = target.toArray();
          body.state.velocity = [0, 0, 0];
        }
        body.mesh.position.copy(
          obj.localToWorld(
            new THREE.Vector3().fromArray(p.position ?? [0, 0, 0]),
          ),
        );
        body.mesh.quaternion
          .copy(obj.getWorldQuaternion(new THREE.Quaternion()))
          .multiply(
            new THREE.Quaternion().setFromEuler(
              new THREE.Euler(
                ...(p.rotation ?? [0, 0, 0]).map((v) => (v * Math.PI) / 180),
                "ZYX",
              ),
            ),
          );
      }
      Canvas.scene.updateMatrixWorld(true);
      for (const e of data.emitters) {
        const entry = particles.get(e.id),
          locator = entry.locator;
        if (!locator?.mesh) continue;
        const matrix = locator.mesh.matrixWorld,
          rotation = locator.mesh.getWorldQuaternion(new THREE.Quaternion());
        for (let n = 0; n < steps; n++)
          stepParticles(entry.state, e, 1 / 120, Math.random, (p) => {
            if (e.space !== "local") {
              p.position = new THREE.Vector3()
                .fromArray(p.position)
                .applyMatrix4(matrix)
                .toArray();
              p.velocity = new THREE.Vector3()
                .fromArray(p.velocity)
                .applyQuaternion(rotation)
                .toArray();
            }
            return p;
          });
        const positions = entry.points.geometry.attributes.position;
        entry.state.particles.forEach((p, i) => {
          const point = new THREE.Vector3().fromArray(p.position);
          if (e.space === "local") point.applyMatrix4(matrix);
          positions.setXYZ(i, point.x, point.y, point.z);
        });
        positions.needsUpdate = true;
        entry.points.geometry.setDrawRange(0, entry.state.particles.length);
      }
      for (const [obj, pose] of poses) {
        pose.appliedQ.copy(obj.quaternion);
        pose.appliedP.copy(obj.position);
      }
    };
    preview = {
      project,
      overlay,
      texture,
      restore,
      restoreHierarchy,
      tick,
      data,
    };
    tick();
  }
  function listen(target, event, callback) {
    target.on(event, callback);
    listeners.push(() => target.removeListener(event, callback));
  }
  Plugin.register("kasuga_model_components", {
    title: "Kasuga Model Components",
    author: "KasugaLib contributors",
    description:
      "骨骼 IK、物理参数、点状粒子源与 Blockbench 实时预览；三种 Minecraft JSON 格式内嵌组件。",
    icon: "account_tree",
    version: "0.2.0",
    min_version: "4.12.0",
    variant: "both",
    tags: [
      "Minecraft: Java Edition",
      "Minecraft: Bedrock Edition",
      "Animation",
    ],
    onload() {
      if (actions.length) return;
      originalJavaLocators = Formats.java_block.locators;
      Formats.java_block.locators = true;
      properties.push(
        new Property(Group, "object", "kasuga_components", {
          default: () => ({}),
        }),
        new Property(Group, "object", "kasuga_bone", { default: () => ({}) }),
        new Property(Locator, "object", "kasuga_emitter", {
          default: () => ({}),
        }),
      );
      installConfigUI();
      for (const [id, name, icon, fn] of [
        ["kasuga_ik", "Kasuga：编辑 IK", "account_tree", editIk],
        [
          "kasuga_physics",
          "Kasuga：编辑物理骨骼",
          "sports_baseball",
          editPhysics,
        ],
        ["kasuga_add_emitter", "Kasuga：新增粒子源", "flare", addEmitter],
        ["kasuga_edit_emitter", "Kasuga：编辑粒子源", "tune", editEmitter],
        ["kasuga_component_json", "Kasuga：组件 JSON", "code", editJson],
        [
          "kasuga_preview",
          "Kasuga：开始 / 停止预览",
          "play_arrow",
          startPreview,
        ],
      ]) {
        const action = new Action(id, {
          name,
          icon,
          condition: () => !!Project,
          click: guarded(fn),
        });
        actions.push(action);
        MenuBar.addAction(action, "tools");
      }
      for (const [id, kind] of [
        ["project", "bbmodel"],
        ["java_block", "java"],
        ["bedrock", "bedrock"],
      ])
        if (Codecs[id]) {
          // Compile errors deliberately propagate: silently exporting broken references loses authored data.
          listen(Codecs[id], "compile", (event) => exportModel(kind, event));
          listen(
            Codecs[id],
            "parsed",
            guarded((event) => {
              const data = event.model.kasuga_lib;
              if (data) hydrate(data, kind);
            }),
          );
        }
      listen(
        Blockbench,
        "render_frame",
        guarded(() => {
          if (preview && !(Animator.open && Timeline.playing)) preview.tick();
          updateEditor();
        }),
      );
      listen(
        Blockbench,
        "display_animation_frame",
        guarded(() => {
          if (preview) preview.tick();
        }),
      );
      for (const event of [
        "unselect_project",
        "close_project",
        "select_project",
        "undo",
        "redo",
        "init_edit",
        "convert_format",
      ])
        listen(Blockbench, event, stopPreview);
      // Available for console diagnostics and repeatable real-host smoke tests; not persisted.
      window.KasugaModelComponents = {
        ...api,
        capture,
        startPreview,
        stopPreview,
        hydrate,
        enterConfigMode,
        refreshConfig,
        beginBonePick,
        commitConfig,
        syncToGame,
        get configPanel() {
          return configPanel;
        },
        get editor() {
          return editor;
        },
        get panelState() {
          return panelState;
        },
        get preview() {
          return preview;
        },
      };
    },
    onunload() {
      emitterDialog?.hide();
      emitterDialog?.delete();
      emitterDialog = null;
      endDrag(false);
      picking = null;
      modes.splice(0).forEach((mode) => mode.delete());
      disposeEditor();
      configPanel?.delete();
      configPanel = null;
      configDisposers
        .splice(0)
        .reverse()
        .forEach((dispose) => dispose());
      stopPreview();
      Formats.java_block.locators = originalJavaLocators;
      listeners.splice(0).forEach((dispose) => dispose());
      actions.splice(0).forEach((action) => action.delete());
      properties.splice(0).forEach((property) => property.delete());
      delete window.KasugaModelComponents;
    },
  });
})();
