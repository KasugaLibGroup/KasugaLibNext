const { test } = require("node:test");
const assert = require("node:assert/strict");
const core = require("../kasuga_model_components.js");
const rig = () => ({
  version: 1,
  bones: [
    {
      id: "base",
      name: "base",
      parent: null,
      pivot: [0, 0, 0],
      components: {},
    },
    {
      id: "mid",
      name: "mid",
      parent: "base",
      pivot: [0, 8, 0],
      components: {},
    },
    {
      id: "tip",
      name: "tip",
      parent: "mid",
      pivot: [0, 16, 0],
      components: {
        ik: { target: "target", links: [{ bone: "base" }, { bone: "mid" }] },
      },
    },
    {
      id: "target",
      name: "target",
      parent: null,
      pivot: [8, 8, 0],
      components: {},
    },
  ],
  bindings: [],
  emitters: [],
});
test("version, references, cycles, ordering and unknown data", () => {
  const r = rig();
  r.extension = { vendor: 42 };
  assert.deepEqual(core.validate(r), r);
  for (const mutate of [
    (r) => (r.version = 2),
    (r) => (r.bones[1].parent = "missing"),
    (r) => (r.bones[0].parent = "tip"),
    (r) => (r.bones[3].name = "mid"),
    (r) => (r.bones[3].parent = "base"),
    (r) => r.bones[2].components.ik.links.reverse(),
    (r) => (r.bones[2].components.ik.target = "mid"),
    (r) => (r.bones[1].pivot = [NaN, 0, 0]),
  ]) {
    const bad = rig();
    mutate(bad);
    assert.throws(() => core.validate(bad));
  }
});

test("batch component patches preserve unrelated bone data", () => {
  const data = rig();
  data.bones[0].components.physics = {
    mode: "dynamic",
    shape: "box",
    size: [2, 3, 4],
    position: [0, 1, 0],
    mass: 2,
    parent_body: "mid",
  };
  const patched = core.applyComponentBatch(data, ["base", "mid"], "physics", {
    fields: { shape: true, mass: true },
    values: { shape: "sphere", mass: 5 },
  });
  assert.equal(patched.bones[0].components.physics.shape, "sphere");
  assert.equal(patched.bones[0].components.physics.mass, 5);
  assert.equal(patched.bones[0].components.physics.mode, "dynamic");
  assert.deepEqual(patched.bones[0].components.physics.size, [2, 3, 4]);
  assert.equal(patched.bones[0].components.physics.parent_body, "mid");
  assert.equal(data.bones[0].components.physics.shape, "box");
  const snapshot = structuredClone(data);
  assert.throws(() =>
    core.applyComponentBatch(data, ["base", "mid"], "physics", {
      fields: { parent_body: true },
      values: { parent_body: "missing" },
    }),
  );
  assert.deepEqual(data, snapshot);
  assert.throws(() =>
    core.applyComponentBatch(data, ["tip", "mid"], "ik", {
      fields: { root: true, target: true },
      values: { root: "mid", target: "target" },
    }),
  );
  assert.deepEqual(data, snapshot);
  const limited = rig();
  limited.bones[2].components.ik.links[0].min = [-90, -90, -90];
  limited.bones[2].components.ik.links[0].max = [30, 40, 50];
  const next = core.applyComponentBatch(limited, ["tip"], "ik", {
    fields: { minimum: true },
    values: { minimum: [-10, -20, -30] },
  });
  assert.deepEqual(next.bones[2].components.ik.links[0].max, [30, 40, 50]);
  assert.equal(next.bones[2].components.ik.target, "target");
});

test("live pack publications preserve native JSON and reject path escapes", () => {
  const fs = require("node:fs"),
    path = require("node:path"),
    os = require("node:os");
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "kasuga-live-pack-"));
  try {
    const model = {
        meta: { format_version: "5.0" },
        native: { keep: true },
        kasuga_lib: rig(),
      },
      config = { directory, resource: "demo:models/example.bbmodel" };
    const output = core.writeLivePack(fs, path, config, model);
    assert.deepEqual(JSON.parse(fs.readFileSync(output, "utf8")), model);
    const manifest = JSON.parse(
      fs.readFileSync(path.join(directory, ".kasuga-sync.json"), "utf8"),
    );
    assert.equal(manifest.version, 1);
    assert.equal(manifest.resource, config.resource);
    for (const resource of [
      "demo:models/../outside.bbmodel",
      "demo:models//test.bbmodel",
      "UPPER:models/test.bbmodel",
    ])
      assert.throws(() =>
        core.writeLivePack(fs, path, { ...config, resource }, model),
      );
    fs.symlinkSync(os.tmpdir(), path.join(directory, "assets", "escape"));
    assert.throws(() =>
      core.writeLivePack(
        fs,
        path,
        { ...config, resource: "escape:models/out.bbmodel" },
        model,
      ),
    );
    assert.equal(
      JSON.parse(
        fs.readFileSync(path.join(directory, ".kasuga-sync.json"), "utf8"),
      ).generation,
      manifest.generation,
    );
    assert.deepEqual(
      JSON.parse(
        fs.readFileSync(
          path.join(directory, "assets/kasuga_live/models/model_proxy.json"),
          "utf8",
        ),
      ),
      { demo: ["models/example.bbmodel"] },
    );
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test("viewport parent picking supports validated hierarchy changes", () => {
  const data = rig();
  delete data.bones[2].components.ik;
  const next = core.applyComponentBatch(data, ["target"], "physics", {
    fields: { parent_group: true, pivot: true },
    values: { parent_group: "mid", pivot: [3, 5, 7] },
  });
  assert.equal(next.bones[3].parent, "mid");
  assert.deepEqual(next.bones[3].pivot, [3, 5, 7]);
  assert.equal(next.bones[3].components.physics, undefined);
  assert.equal(data.bones[3].parent, null);
  assert.throws(() =>
    core.applyComponentBatch(rig(), ["target"], "physics", {
      fields: { parent_group: true },
      values: { parent_group: "mid" },
    }),
  );
});
test("CCD converges and handles opposite, unreachable and zero length segments", () => {
  for (const target of [
    [8, 8, 0],
    [0, -12, 0],
    [50, 50, 0],
    [0, 0, 0],
  ]) {
    const source = [
        [0, 0, 0],
        [0, 8, 0],
        [0, 16, 0],
      ],
      copy = structuredClone(source);
    const solved = core.solveCCD(source, target, 128, 0.001, Math.PI);
    assert.deepEqual(source, copy);
    assert.ok(solved.flat().every(Number.isFinite));
    for (let i = 1; i < 3; i++)
      assert.ok(Math.abs(core.distance(solved[i], solved[i - 1]) - 8) < 1e-6);
    if (target[0] === 8) assert.ok(core.distance(solved[2], target) < 0.01);
  }
  assert.ok(
    core
      .solveCCD(
        [
          [0, 0, 0],
          [0, 0, 0],
        ],
        [1, 0, 0],
      )
      .flat()
      .every(Number.isFinite),
  );
});
test("particle emission is frame-rate independent, bounded and finite", () => {
  const emitter = {
    rate: 60,
    max_particles: 100,
    particle: {
      lifetime: 2,
      speed: 2,
      spread: 0,
      size: 1,
      color: "#ff8844",
      gravity: [0, -1, 0],
    },
  };
  const simulate = (dt) => {
    const state = core.particleState();
    for (let i = 0; i < Math.round(1 / dt); i++)
      core.stepParticles(state, emitter, dt, () => 0.5);
    return state;
  };
  assert.equal(simulate(1 / 60).particles.length, 60);
  assert.equal(simulate(1 / 30).particles.length, 60);
  const s = core.particleState();
  for (let i = 0; i < 1000; i++)
    core.stepParticles(
      s,
      { ...emitter, rate: 10000, max_particles: 10 },
      0.1,
      () => 0.5,
    );
  assert.equal(s.particles.length, 10);
  assert.ok(
    s.particles.every((p) =>
      p.position.concat(p.velocity, p.age).every(Number.isFinite),
    ),
  );
  const fresh = core.particleState();
  core.stepParticles(fresh, { ...emitter, rate: 1000, max_particles: 10 }, 0.1);
  core.stepParticles(fresh, { ...emitter, enabled: false }, 0.1);
  assert.equal(fresh.particles.length, 10);
});
test("physics spring has finite fixed steps and zero mass is not divided", () => {
  const state = { position: [0, 0, 0], velocity: [0, 0, 0] };
  for (let i = 0; i < 600; i++)
    core.springStep(
      state,
      [0, 8, 0],
      { mass: 1, stiffness: 80, linear_damping: 0.3, gravity: [0, -9.8, 0] },
      1 / 120,
    );
  assert.ok(state.position.every(Number.isFinite));
  assert.ok(Math.abs(state.position[1] - 8) < 1);
  assert.throws(() =>
    core.validate({
      ...rig(),
      emitters: [
        {
          id: "e",
          name: "e",
          bone: "missing",
          position: [0, 0, 0],
          rotation: [0, 0, 0],
        },
      ],
    }),
  );
  const bad = rig();
  bad.bones[0].components.physics = { mass: -1 };
  assert.throws(() => core.validate(bad));
});
test("extension attachment changes only the namespaced key in every format", () => {
  for (const [kind, model] of [
    [
      "bbmodel",
      {
        meta: { format_version: "4.10" },
        elements: [{ uuid: "cube", from: [0, 0, 0], to: [1, 1, 1] }],
        outliner: ["cube"],
        textures: [{ name: "original" }],
        vendor_data: { untouched: true },
      },
    ],
    [
      "java",
      {
        parent: "block/block",
        textures: { all: "minecraft:block/stone" },
        elements: [
          {
            from: [0, 0, 0],
            to: [16, 16, 16],
            faces: { north: { texture: "#all" } },
          },
        ],
        display: { gui: { scale: [1, 1, 1] } },
      },
    ],
    [
      "bedrock",
      {
        format_version: "1.12.0",
        "minecraft:geometry": [
          {
            description: { identifier: "geometry.original" },
            bones: [
              {
                name: "original",
                pivot: [0, 0, 0],
                cubes: [{ origin: [0, 0, 0], size: [1, 2, 3] }],
              },
            ],
          },
          { description: { identifier: "geometry.other" }, bones: [] },
        ],
      },
    ],
  ]) {
    const original = structuredClone(model);
    core.attachExtension(model, rig(), kind);
    const container =
      kind === "bedrock" ? model["minecraft:geometry"][0] : model;
    assert.deepEqual(container.kasuga_lib, rig());
    delete container.kasuga_lib;
    assert.deepEqual(model, original);
  }
});
