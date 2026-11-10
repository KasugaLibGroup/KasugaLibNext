/* Run with a real locally served official Blockbench source build. */
const { chromium } = require("playwright");
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
(async () => {
  const browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ||
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
    args: ["--use-angle=metal", "--no-sandbox"],
  });
  try {
    const page = await browser.newPage({
        viewport: { width: 1440, height: 1000 },
      }),
      errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) =>
      console.log("BROWSER", message.type(), message.text()),
    );
    page.on("dialog", (dialog) => {
      console.log("DIALOG", dialog.message());
      dialog.accept();
    });
    await page.goto(process.env.BLOCKBENCH_URL || "http://127.0.0.1:8794/");
    await page.waitForFunction(
      () => typeof Codecs !== "undefined" && typeof Plugin !== "undefined",
    );
    await page.evaluate(async () => {
      if (Plugins.loading_promise) await Plugins.loading_promise;
    });
    await page.evaluate(() => {
      window.__kasugaBaseCompiles = new WeakMap();
      for (const id of ["project", "java_block", "bedrock"])
        Codecs[id].on("compile", (event) =>
          window.__kasugaBaseCompiles.set(
            event.model,
            JSON.stringify(event.model),
          ),
        );
    });
    await page.evaluate(
      async (content) =>
        new Plugin().loadFromFile(
          {
            path: "kasuga_model_components.js",
            name: "kasuga_model_components.js",
            // The official web loader supplies no Node require; inject only the test filesystem adapter.
            content:
              "var require = (...args) => window.require(...args);\n" + content,
          },
          true,
        ),
      fs.readFileSync(
        path.join(__dirname, "../kasuga_model_components.js"),
        "utf8",
      ),
    );
    const result = await page.evaluate(async () => {
      const check = (yes, message) => {
        if (!yes) throw Error(message);
      };
      const checkBase = (model, kind) => {
        const original = JSON.parse(window.__kasugaBaseCompiles.get(model)),
          actual = JSON.parse(JSON.stringify(model));
        const a = kind === "bedrock" ? actual["minecraft:geometry"][0] : actual,
          b = kind === "bedrock" ? original["minecraft:geometry"][0] : original;
        delete a.kasuga_lib;
        delete b.kasuga_lib;
        check(
          JSON.stringify(actual) === JSON.stringify(original),
          `${kind}: plugin changed native structure`,
        );
      };
      newProject(Formats.bedrock);
      Project.name = "kasuga_components_demo";
      Project.geometry_name = "kasuga_components_demo";
      const base = new Group({ name: "base", origin: [0, 0, 0] }).init(),
        mid = new Group({ name: "mid", origin: [0, 8, 0] }).addTo(base).init(),
        tip = new Group({ name: "tip", origin: [0, 16, 0] }).addTo(mid).init(),
        target = new Group({ name: "target", origin: [8, 8, 0] }).init();
      for (const [bone, y] of [
        [base, 0],
        [mid, 8],
      ])
        new Cube({
          name: "segment",
          from: [-1, y, -1],
          to: [1, y + 8, 1],
          origin: [0, y, 0],
        })
          .addTo(bone)
          .init();
      const textureCanvas = document.createElement("canvas");
      textureCanvas.width = textureCanvas.height = 16;
      const context = textureCanvas.getContext("2d");
      context.fillStyle = "#79a7c8";
      context.fillRect(0, 0, 16, 16);
      context.fillStyle = "#adcbe2";
      context.fillRect(0, 0, 8, 8);
      context.fillRect(8, 8, 8, 8);
      const texture = new Texture({ name: "rig.png" })
        .fromDataURL(textureCanvas.toDataURL())
        .add(false);
      tip.kasuga_components = {
        ik: {
          target: target.uuid,
          links: [{ bone: base.uuid }, { bone: mid.uuid }],
          iterations: 64,
          tolerance: 0.01,
          max_step: 45,
        },
      };
      base.kasuga_components = {
        physics: {
          shape: "box",
          mode: "kinematic",
          size: [1, 4, 1],
          position: [0, 4, 0],
          mass: 0,
        },
      };
      const tail = new Group({ name: "tail", origin: [0, 8, 0] })
        .addTo(base)
        .init();
      new Cube({
        name: "tail_cube",
        from: [-1, 8, -1],
        to: [1, 14, 1],
        origin: [0, 8, 0],
      })
        .addTo(tail)
        .init();
      tail.kasuga_components = {
        physics: {
          shape: "capsule",
          mode: "rotation",
          size: [1, 4, 1],
          position: [0, 4, 0],
          mass: 1,
          linear_damping: 0.3,
          gravity: [20, -30, 0],
          parent_body: base.uuid,
        },
      };
      const emitter = new Locator({
        name: "exhaust",
        position: [0, 16, 0],
        rotation: [0, 0, 90],
        kasuga_emitter: {
          rate: 60,
          max_particles: 128,
          space: "world",
          particle: {
            lifetime: 2,
            speed: 12,
            spread: 10,
            size: 2,
            color: "#ff8844",
            gravity: [0, -2, 0],
          },
        },
      })
        .addTo(tip)
        .init();
      for (const cube of Cube.all)
        for (const face of Object.values(cube.faces))
          face.texture = texture.uuid;
      await new Promise((resolve) => setTimeout(resolve, 50));
      Canvas.updateAll();
      const authored = Group.all.map((g) => g.rotation.slice());
      KasugaModelComponents.startPreview();
      check(!!KasugaModelComponents.preview, "preview failed to start");
      const previewStarted = KasugaModelComponents.preview;
      await new Promise((resolve) => setTimeout(resolve, 600));
      // Also drive a frame explicitly: background rendering policies vary by host.
      for (let i = 0; i < 20; i++) {
        await new Promise((resolve) => setTimeout(resolve, 16));
        KasugaModelComponents.preview?.tick();
      }
      const end = tip.mesh.getWorldPosition(new THREE.Vector3()),
        goal = target.mesh.getWorldPosition(new THREE.Vector3());
      check(
        end.distanceTo(goal) < 0.05,
        `real THREE IK convergence: ${end.distanceTo(goal)}`,
      );
      const particlePreview = previewStarted.overlay.children.some(
        (o) => o.isPoints && o.geometry.drawRange.count > 0,
      );
      check(particlePreview, "no particle preview");
      const physicsMoved = Math.abs(tail.mesh.quaternion.z) > 0.001;
      check(physicsMoved, "physics preview did not move");
      Preview.selected.render();
      const previewImage = Preview.selected.canvas.toDataURL("image/png");
      const originalNodes = Outliner.elements.length,
        baseModel = Codecs.project.compile({ raw: true });
      check(
        KasugaModelComponents.preview === null,
        "export did not stop preview",
      );
      check(
        JSON.stringify(authored) ===
          JSON.stringify(Group.all.map((g) => g.rotation)),
        "preview changed authored rotations",
      );
      check(baseModel.kasuga_lib.emitters.length === 1, "emitter not saved");
      check(
        Outliner.elements.length === originalNodes,
        "point became geometry",
      );
      checkBase(baseModel, "bbmodel");
      const captures = { bbmodel: baseModel };
      // Format switching is part of host operation; the attachment itself must not touch base JSON.
      Formats.java_block.select();
      Canvas.updateAll();
      KasugaModelComponents.startPreview();
      check(
        tip.mesh
          .getWorldPosition(new THREE.Vector3())
          .distanceTo(target.mesh.getWorldPosition(new THREE.Vector3())) < 0.05,
        "Java hierarchy preview IK",
      );
      KasugaModelComponents.stopPreview();
      const java = Codecs.java_block.compile({ raw: true });
      check(
        java.kasuga_lib.bindings.length === Cube.all.length,
        "Java element bindings",
      );
      checkBase(java, "java");
      captures.java = java;
      Formats.bedrock.select();
      Canvas.updateAll();
      settings.export_empty_groups.value = false;
      const bedrock = Codecs.bedrock.compile({ raw: true });
      check(
        bedrock["minecraft:geometry"][0].kasuga_lib.emitters.length === 1,
        "Bedrock extension placement",
      );
      checkBase(bedrock, "bedrock");
      captures.bedrock = bedrock;
      for (const [kind, codec, document] of [
        ["bedrock", Codecs.bedrock, bedrock],
        ["java", Codecs.java_block, java],
        ["bbmodel", Codecs.project, baseModel],
      ]) {
        newProject(kind === "java" ? Formats.java_block : Formats.bedrock);
        codec.parse(JSON.parse(JSON.stringify(document)), "smoke/model.json");
        const restored = KasugaModelComponents.capture();
        check(restored.emitters.length === 1, `${kind}: emitter round trip`);
        check(
          restored.bones.some((b) => b.components.ik),
          `${kind}: IK round trip`,
        );
        KasugaModelComponents.validate(restored);
      }
      const saved = Codecs.project.compile({ raw: true });
      const bone = Group.all.find((g) => g.name === "tail");
      bone.select();
      BarItems.kasuga_physics.click();
      check(Modes.id === "kasuga_physics_mode", "physics mode failed");
      const panel = KasugaModelComponents.configPanel;
      check(panel.node.isConnected, "persistent panel missing");
      const mass = panel.node.querySelector(
        '[data-field="mass"] input[type="number"]',
      );
      mass.value = "2";
      mass.dispatchEvent(new Event("input"));
      panel.node.querySelector('[data-action="apply"]').click();
      check(bone.kasuga_components.physics.mass === 2, "physics panel save");
      const endBone = Group.all.find((g) => g.name === "tip");
      endBone.select();
      BarItems.kasuga_ik.click();
      check(Modes.id === "kasuga_ik_mode", "IK mode failed");
      const iterations = panel.node.querySelector(
        '[data-field="iterations"] input[type="number"]',
      );
      iterations.value = "48";
      iterations.dispatchEvent(new Event("input"));
      panel.node.querySelector('[data-action="apply"]').click();
      check(endBone.kasuga_components.ik.iterations === 48, "IK panel save");
      check(
        KasugaModelComponents.editor.bones.length === Group.all.length,
        "diamond bones missing",
      );
      // Exercise the real save callback while giving the web host a deterministic desktop filesystem.
      const originalRequire = window.require,
        originalIsApp = window.isApp;
      const files = new Map(),
        dirs = new Set(["/", "/preview-pack"]);
      window.require = (id) =>
        id === "path"
          ? {
              sep: "/",
              isAbsolute: (p) => p.startsWith("/"),
              resolve: (...parts) =>
                "/" +
                parts
                  .join("/")
                  .split("/")
                  .filter(Boolean)
                  .reduce((out, part) => {
                    if (part === "..") out.pop();
                    else if (part !== ".") out.push(part);
                    return out;
                  }, [])
                  .join("/"),
              dirname: (p) => p.slice(0, p.lastIndexOf("/")) || "/",
              join: (...parts) => parts.join("/"),
            }
          : {
              existsSync: (p) => files.has(p) || dirs.has(p),
              lstatSync: () => ({ isSymbolicLink: () => false }),
              mkdirSync: (p) => dirs.add(p),
              readFileSync: (p) => files.get(p),
              writeFileSync: (p, value) => files.set(p, value),
              renameSync: (a, b) => {
                files.set(b, files.get(a));
                files.delete(a);
              },
              unlinkSync: (p) => files.delete(p),
            };
      window.isApp = true;
      Project.save_path = "/preview-author.bbmodel";
      localStorage.setItem(
        "kasuga_live:/preview-author.bbmodel",
        JSON.stringify({
          enabled: true,
          directory: "/preview-pack",
          resource: "smoke:models/live.bbmodel",
          format: "project",
        }),
      );
      Blockbench.dispatchEvent("select_project", { project: Project });
      window.isApp = false;
      const oldAddRecent = window.addRecentProject,
        oldThumbnail = window.updateRecentProjectThumbnail;
      window.addRecentProject = () => {};
      window.updateRecentProjectThumbnail = () => {};
      Codecs.project.afterSave("/preview-author.bbmodel");
      const live = JSON.parse(
        files.get("/preview-pack/assets/smoke/models/live.bbmodel"),
      );
      check(
        live.kasuga_lib.bones.some((b) => b.components.ik?.iterations === 48),
        "save hook did not publish updated IK",
      );
      const oldMarker = files.get("/preview-pack/.kasuga-sync.json");
      Codecs.project.afterSave("/preview-author.bbmodel");
      check(
        oldMarker !== files.get("/preview-pack/.kasuga-sync.json"),
        "save hook did not advance generation",
      );
      window.require = originalRequire;
      window.isApp = originalIsApp;
      window.addRecentProject = oldAddRecent;
      window.updateRecentProjectThumbnail = oldThumbnail;
      Locator.all.find((l) => l.kasuga_emitter?.particle).select();
      BarItems.kasuga_edit_emitter.click();
      check(Dialog.open?.id === "kasuga_emitter", "emitter dialog failed");
      Dialog.open.setFormValues({ color: "#22aaff", speed: 9 });
      Dialog.open.confirm();
      check(
        Locator.all.find((l) => l.kasuga_emitter?.particle).kasuga_emitter
          .particle.color === "#22aaff",
        "emitter dialog save",
      );
      const before = bone.kasuga_components.physics.mass;
      Undo.initEdit({ groups: [bone] });
      bone.kasuga_components.physics.mass = 5;
      Undo.finishEdit("component undo smoke");
      Undo.undo();
      check(
        bone.kasuga_components.physics.mass === before,
        "Undo lost Property",
      );
      Undo.redo();
      check(bone.kasuga_components.physics.mass === 5, "Redo lost Property");
      KasugaModelComponents.startPreview();
      newProject(Formats.java_block);
      check(
        KasugaModelComponents.preview === null,
        "project switch leaked preview",
      );
      check(
        !KasugaModelComponents.capture().emitters.length,
        "project data leaked",
      );
      Codecs.project.parse(saved);
      KasugaModelComponents.startPreview();
      Plugins.registered.kasuga_model_components.onunload();
      check(
        !Canvas.scene.children.some(
          (o) => o.name === "Kasuga component preview",
        ),
        "unload leaked scene objects",
      );
      check(!BarItems.kasuga_ik, "unload leaked action");
      check(
        !Modes.options.kasuga_ik_mode && !Interface.Panels.kasuga_components,
        "unload leaked configuration UI",
      );
      return {
        version: Blockbench.version,
        captures,
        previewImage,
        physicsMoved,
        ikError: end.distanceTo(goal),
        elementCount: originalNodes,
      };
    });
    const out =
      process.env.KASUGA_SMOKE_OUTPUT || "/private/tmp/kasuga-blockbench-smoke";
    fs.mkdirSync(out, { recursive: true });
    for (const [format, document] of Object.entries(result.captures))
      fs.writeFileSync(
        path.join(
          out,
          `demo.${format === "bbmodel" ? "bbmodel" : format === "java" ? "java.json" : "geo.json"}`,
        ),
        JSON.stringify(document, null, 2),
      );
    fs.writeFileSync(
      path.join(out, "preview.png"),
      Buffer.from(result.previewImage.split(",")[1], "base64"),
    );
    await page.screenshot({ path: path.join(out, "final.png") });
    delete result.captures;
    delete result.previewImage;
    console.log(JSON.stringify({ ...result, errors, output: out }, null, 2));
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
