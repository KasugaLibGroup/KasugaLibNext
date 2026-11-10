/* Real pointer interaction and demo recording; optional private user models are never modified. */
const { chromium } = require("playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const output =
  process.env.KASUGA_CONFIG_OUTPUT ||
  path.resolve("build/reports/model-components/config-modes");
const fixtureRoot = process.env.KASUGA_PRIVATE_MODELS;
(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ||
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
    args: ["--use-angle=metal", "--no-sandbox"],
  });
  const context = await browser.newContext({
    viewport: { width: 1600, height: 1040 },
  });
  const page = await context.newPage(),
    errors = [],
    results = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("dialog", (dialog) => dialog.accept());
  const recording = fs.mkdtempSync(path.join(output, "frames-")),
    frames = [];
  let cdp;
  const caption = async (text) =>
    page.evaluate((text) => {
      let node = document.getElementById("kasuga-demo-caption");
      if (!node) {
        node = document.createElement("div");
        node.id = "kasuga-demo-caption";
        Object.assign(node.style, {
          position: "fixed",
          left: "335px",
          top: "70px",
          maxWidth: "850px",
          padding: "12px 18px",
          background: "#132135ed",
          color: "#eefaff",
          font: "18px sans-serif",
          borderRadius: "5px",
          zIndex: 100000,
          pointerEvents: "none",
        });
        document.body.append(node);
      }
      node.textContent = text;
    }, text);
  const projectObject = async (kind, name, axis = 0) =>
    page.evaluate(
      ({ kind, name, axis }) => {
        Preview.selected.render();
        const editor = KasugaModelComponents.editor;
        const object =
          kind === "handle"
            ? editor.handles.find(
                (o) =>
                  o.userData.axis === axis &&
                  o.geometry.type !== "CylinderGeometry",
              )
            : editor.bones.find((o) => o.userData.bone.name === name);
        if (!object) throw Error(`Missing ${kind}: ${name}`);
        const p = object
            .getWorldPosition(new THREE.Vector3())
            .project(Preview.selected.camera),
          rect = Preview.selected.canvas.getBoundingClientRect();
        return {
          x: rect.left + ((p.x + 1) * rect.width) / 2,
          y: rect.top + ((1 - p.y) * rect.height) / 2,
        };
      },
      { kind, name, axis },
    );
  const clickBone = async (name) => {
    const p = await projectObject("bone", name);
    await page.mouse.click(p.x, p.y);
  };
  const dragHandle = async (axis, dx, dy) => {
    const p = await projectObject("handle", "", axis);
    await page.mouse.move(p.x, p.y);
    await page.mouse.down();
    await page.mouse.move(p.x + dx, p.y + dy, { steps: 24 });
    await page.mouse.up();
  };
  try {
    await page.goto(process.env.BLOCKBENCH_URL || "http://127.0.0.1:8794/");
    await page.waitForFunction(() => typeof Codecs !== "undefined");
    await page.evaluate(async () => {
      if (Plugins.loading_promise) await Plugins.loading_promise;
    });
    await page.evaluate(
      async (content) =>
        new Plugin().loadFromFile(
          {
            path: "kasuga_model_components.js",
            name: "kasuga_model_components.js",
            content,
          },
          true,
        ),
      fs.readFileSync(
        path.join(__dirname, "../kasuga_model_components.js"),
        "utf8",
      ),
    );
    cdp = await context.newCDPSession(page);
    cdp.on("Page.screencastFrame", ({ data, metadata, sessionId }) => {
      const filename = `frame-${String(frames.length).padStart(6, "0")}.jpg`;
      fs.writeFileSync(
        path.join(recording, filename),
        Buffer.from(data, "base64"),
      );
      frames.push({ filename, timestamp: metadata.timestamp });
      cdp.send("Page.screencastFrameAck", { sessionId }).catch(() => {});
    });
    await cdp.send("Page.startScreencast", {
      format: "jpeg",
      quality: 88,
      maxWidth: 1600,
      maxHeight: 1040,
      everyNthFrame: 2,
    });
    const files = fixtureRoot
      ? ["qj_bogey_main.bbmodel", "df11g_bogeynew2.bbmodel"]
      : ["rig.bbmodel"];
    for (const filename of files) {
      const model = JSON.parse(
        fs.readFileSync(
          path.join(
            fixtureRoot ||
              path.join(__dirname, "../../../examples/model-components"),
            filename,
          ),
          "utf8",
        ),
      );
      await page.evaluate(
        ({ model, filename }) => {
          Codecs.project.load(model, { path: filename, name: filename });
          for (const dialog of document.querySelectorAll(".dialog_wrapper"))
            dialog.style.display = "none";
          for (const group of Group.all)
            if (group.name === "railway") {
              group.visibility = false;
              group.forEachChild((node) => {
                node.visibility = false;
              });
            }
          Canvas.updateAll();
          const qj = filename.startsWith("qj"),
            df = filename.startsWith("df11"),
            view = Preview.selected;
          view.loadAnglePreset({
            position: qj ? [-100, 65, 90] : df ? [-100, 65, 95] : [38, 28, 40],
            target: qj ? [-10, 22, 10] : df ? [0, 8, 0] : [0, 8, 0],
            projection: "perspective",
          });
          view.controls.update();
          (
            Group.all.find(
              (g) => g.name === (qj ? "pianxingan" : df ? "dainji" : "tip"),
            ) || Group.all[0]
          ).select();
          BarItems.kasuga_ik.click();
        },
        { model, filename },
      );
      await page.waitForTimeout(650);
      await caption(`${filename} · IK 配置模式：直接点选菱形骨骼`);
      await page.locator('[data-action="focus-bones"]').check();
      await page.waitForTimeout(600);
      const rod = filename.startsWith("qj")
        ? "pianxingan"
        : filename.startsWith("df11")
          ? "dainji"
          : "tip";
      if (fixtureRoot && filename.startsWith("qj")) {
        await page.locator('[data-action="rod-controls"]').click();
        await page.waitForTimeout(350);
        const created = await page.evaluate((rod) => {
          const tip = Group.all.find((g) => g.name === `${rod}_杆端`),
            goal = Group.all.find((g) => g.name === `${rod}_IK目标`);
          if (!tip || !goal) throw Error("Rod helper creation failed");
          // For QJ the other eccentric-rod pin is represented by the exported mesh's own pivot.
          if (rod === "pianxingan") {
            const pin = Group.all
              .find((g) => g.name === rod)
              .children.find(
                (n) =>
                  n instanceof Mesh && n.origin[2] > 23 && n.origin[2] < 24,
              );
            if (pin) {
              tip.origin.splice(0, 3, ...pin.origin);
              goal.origin.splice(0, 3, ...pin.origin);
              Canvas.updateAll();
            }
          }
          window.__kasugaTip = tip.uuid;
          return { tip: tip.name, target: goal.name };
        }, rod);
        await caption(
          "点“目标骨骼 → 点选骨骼”，再点模型中的菱形；无需输入 UUID",
        );
        await page.locator('[data-action="pick-target"]').click();
        await clickBone(created.target);
        await page.locator('[data-action="apply"]').click();
        const selected = await page.evaluate(() => Group.first_selected.uuid);
        assert.equal(selected, await page.evaluate(() => window.__kasugaTip));
        await page.waitForTimeout(800);
        await page.locator('[data-action="move-target"]').click();
        const before = await page.evaluate(
          (name) => Group.all.find((g) => g.name === name).origin.slice(),
          created.target,
        );
        await caption("拖动视口红色手柄移动 IK 目标；数值输入框依然保留");
        await dragHandle(0, 55, -15);
        const after = await page.evaluate(
          (name) => Group.all.find((g) => g.name === name).origin.slice(),
          created.target,
        );
        assert.notDeepEqual(
          after,
          before,
          "real pointer drag did not move IK target",
        );
        // Check a preview pose changes while authored rotations remain untouched.
        const evidence = await page.evaluate(async (rod) => {
          const group = Group.all.find((g) => g.name === rod),
            authored = group.rotation.slice();
          KasugaModelComponents.startPreview();
          await new Promise((resolve) => setTimeout(resolve, 300));
          KasugaModelComponents.preview.tick();
          const changed =
            group.mesh.quaternion.angleTo(new THREE.Quaternion()) > 0.001;
          if (!changed) throw Error("IK did not rotate the actual rod group");
          if (JSON.stringify(group.rotation) !== JSON.stringify(authored))
            throw Error("preview wrote authored pose");
          return {
            ikMoved: changed,
            bones: Group.all.length,
            elements: Outliner.elements.length,
          };
        }, rod);
        await page.waitForTimeout(1000);
        await page.screenshot({
          path: path.join(output, filename + "-ik.png"),
        });
        await page.evaluate(() => KasugaModelComponents.stopPreview());
        results.push({ filename, ...evidence });
      }
      if (filename.startsWith("df11")) {
        await caption(
          "选择父级：点“父级骨骼 → 点选骨骼”，再点模型上的父级菱形",
        );
        await page.locator('[data-action="pick-parent_group"]').click();
        await clickBone("bone12");
        await page.locator('[data-action="apply"]').click();
        assert.equal(
          await page.evaluate(
            () => Group.all.find((g) => g.name === "dainji").parent.name,
          ),
          "bone12",
        );
        await page.waitForTimeout(1100);
        await page.screenshot({
          path: path.join(output, filename + "-parent-pick.png"),
        });
      }
      await caption("物理配置模式：多选骨骼，批量应用勾选字段");
      await page.evaluate((filename) => {
        const names = filename.startsWith("qj")
          ? ["liangan", "liangan2"]
          : filename.startsWith("df11")
            ? ["bone4", "bone7"]
            : ["base", "tail"];
        Group.all.find((g) => g.name === names[0]).select();
        Group.all.find((g) => g.name === names[1]).multiSelect();
        updateSelection();
        BarItems.kasuga_physics.click();
      }, filename);
      const mass = page.locator('[data-field="mass"] input[type="number"]');
      await mass.fill("2");
      await page.locator('[data-action="apply"]').click();
      assert.equal(await page.evaluate(() => Modes.id), "kasuga_physics_mode");
      const before = await page.evaluate(() =>
        Group.multi_selected.map((g) =>
          g.kasuga_components.physics.position.slice(),
        ),
      );
      await caption("拖动刚体位置手柄：批量更新局部参数；撤销一次恢复整批");
      await dragHandle(1, 0, -35);
      const after = await page.evaluate(() =>
        Group.multi_selected.map((g) =>
          g.kasuga_components.physics.position.slice(),
        ),
      );
      assert.notDeepEqual(
        after,
        before,
        "physics handle did not update both bodies",
      );
      assert.equal(after.length, 2);
      await page.evaluate(() => Undo.undo());
      assert.deepEqual(
        await page.evaluate(() =>
          Group.multi_selected.map((g) =>
            g.kasuga_components.physics.position.slice(),
          ),
        ),
        before,
      );
      await page.evaluate(() =>
        KasugaModelComponents.enterConfigMode("physics"),
      );
      const cancelledStart = await page.evaluate(() =>
        Group.multi_selected.map((g) =>
          g.kasuga_components.physics.position.slice(),
        ),
      );
      const cancelHandle = await projectObject("handle", "", 0);
      await page.mouse.move(cancelHandle.x, cancelHandle.y);
      await page.mouse.down();
      await page.mouse.move(cancelHandle.x + 30, cancelHandle.y - 10, {
        steps: 12,
      });
      await page.keyboard.press("Escape");
      await page.mouse.up();
      assert.deepEqual(
        await page.evaluate(() =>
          Group.multi_selected.map((g) =>
            g.kasuga_components.physics.position.slice(),
          ),
        ),
        cancelledStart,
        "Escape did not restore the drag",
      );
      await page.waitForTimeout(600);
      await page.screenshot({
        path: path.join(output, filename + "-physics.png"),
      });
      const saved = await page.evaluate(() =>
        Codecs.project.compile({ raw: true }),
      );
      fs.writeFileSync(
        path.join(output, filename),
        JSON.stringify(saved, null, 2),
      );
      const result = results.find((r) => r.filename === filename) || {
        filename,
      };
      Object.assign(result, {
        batchPhysics: true,
        pointerDrag: true,
        atomicUndo: true,
        escapeRestored: true,
      });
      if (!results.includes(result)) results.push(result);
    }
    await caption("粒子颜色：调色板选色，也可以输入 HEX 精确值");
    await page.evaluate(() => {
      Group.all.find((g) => g.name === "jiati")?.select();
      BarItems.kasuga_add_emitter.click();
    });
    await page.locator("#kasuga_emitter .form_bar_color .sp-replacer").click();
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(output, "particle-palette.png") });
    const palette = page.locator(".sp-container:visible");
    assert.ok(
      (await palette.locator(".sp-palette .sp-thumb-el").count()) > 0,
      "palette swatches missing",
    );
    const swatches = palette.locator(".sp-palette .sp-thumb-el");
    const positions = await swatches.evaluateAll((nodes) =>
      nodes.map((node) => {
        const r = node.getBoundingClientRect();
        return `${Math.round(r.x)},${Math.round(r.y)}`;
      }),
    );
    assert.equal(
      new Set(positions).size,
      positions.length,
      "palette presets overlap",
    );
    await palette
      .locator('.sp-thumb-el[data-color="rgb(255, 85, 204)"]')
      .click();
    await palette.locator(".sp-input").fill("#22aaff");
    await palette.locator(".sp-input").dispatchEvent("change");
    await palette.locator(".sp-choose").click();
    await page.evaluate(() => {
      Dialog.open.confirm();
    });
    assert.equal(
      await page.evaluate(
        () => Locator.selected[0].kasuga_emitter.particle.color,
      ),
      "#22aaff",
    );
    await page.waitForTimeout(600);
    assert.deepEqual(errors, []);
    fs.writeFileSync(
      path.join(output, "report.json"),
      JSON.stringify({ results, errors }, null, 2),
    );
    console.log(JSON.stringify({ output, results, errors }, null, 2));
  } catch (error) {
    console.error(error.stack);
    await page.screenshot({ path: path.join(output, "failure.png") });
    console.error(
      await page.evaluate(() => ({
        mode: Modes.id,
        status: KasugaModelComponents?.configPanel?.node.textContent.slice(
          0,
          700,
        ),
        selected: Group.multi_selected.map((g) => g.name),
      })),
    );
    throw error;
  } finally {
    const bounded = (promise) =>
      Promise.race([
        promise.catch(() => {}),
        new Promise((resolve) => {
          const timer = setTimeout(resolve, 2500);
          timer.unref();
        }),
      ]);
    if (cdp) await bounded(cdp.send("Page.stopScreencast"));
    await bounded(page.close({ runBeforeUnload: false }));
    await bounded(browser.close());
  }
  const manifest = frames
    .map(
      (frame, index) =>
        `file '${frame.filename}'\nduration ${Math.min(1, Math.max(0.02, (frames[index + 1]?.timestamp ?? frame.timestamp + 0.1) - frame.timestamp))}`,
    )
    .join("\n");
  fs.writeFileSync(
    path.join(recording, "frames.txt"),
    manifest + `\nfile '${frames.at(-1).filename}'\n`,
  );
  const encoded = require("node:child_process").spawnSync(
    "ffmpeg",
    [
      "-y",
      "-f",
      "concat",
      "-safe",
      "0",
      "-i",
      path.join(recording, "frames.txt"),
      "-vf",
      "fps=25,scale=1600:1040",
      "-c:v",
      "libx264",
      "-preset",
      "fast",
      "-crf",
      "20",
      "-pix_fmt",
      "yuv420p",
      "-movflags",
      "+faststart",
      path.join(output, "config-modes.mp4"),
    ],
    { encoding: "utf8" },
  );
  if (encoded.status !== 0) throw Error(encoded.stderr);
})().then(() => process.exit(0)).catch((error) => {
  console.error(error);
  process.exit(1);
});
