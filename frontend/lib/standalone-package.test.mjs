import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { packageStandalone } from "../scripts/package-standalone.mjs";

test("packageStandalone creates a directly runnable Node service", async () => {
  const projectDir = await mkdtemp(path.join(tmpdir(), "h-agent-package-"));

  await mkdir(path.join(projectDir, ".next", "standalone"), { recursive: true });
  await mkdir(path.join(projectDir, ".next", "static", "chunks"), { recursive: true });
  await mkdir(path.join(projectDir, "public"), { recursive: true });
  await writeFile(path.join(projectDir, ".next", "standalone", "server.js"), "server");
  await writeFile(path.join(projectDir, ".next", "static", "chunks", "app.js"), "static");
  await writeFile(path.join(projectDir, "public", "logo.svg"), "public");

  await packageStandalone(projectDir);

  assert.equal(await readFile(path.join(projectDir, "dist", "server.js"), "utf8"), "server");
  assert.equal(
    await readFile(path.join(projectDir, "dist", ".next", "static", "chunks", "app.js"), "utf8"),
    "static",
  );
  assert.equal(await readFile(path.join(projectDir, "dist", "public", "logo.svg"), "utf8"), "public");
  assert.match(await readFile(path.join(projectDir, "dist", "start"), "utf8"), /server\.js/);
});
