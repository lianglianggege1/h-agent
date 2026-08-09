import { access, chmod, cp, mkdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const START_SCRIPT = `#!/usr/bin/env node
"use strict";

require("./server.js");
`;

async function copyIfPresent(source, destination) {
  try {
    await access(source);
  } catch (error) {
    if (error?.code === "ENOENT") return;
    throw error;
  }

  await cp(source, destination, { recursive: true });
}

export async function packageStandalone(projectDir) {
  const standaloneDir = path.join(projectDir, ".next", "standalone");
  const serverFile = path.join(standaloneDir, "server.js");
  const distDir = path.join(projectDir, "dist");

  try {
    await access(serverFile);
  } catch {
    throw new Error(
      `Standalone server not found at ${serverFile}. Run \"npm run build\" first.`,
    );
  }

  await rm(distDir, { recursive: true, force: true });
  await cp(standaloneDir, distDir, { recursive: true });
  await mkdir(path.join(distDir, ".next"), { recursive: true });
  await copyIfPresent(
    path.join(projectDir, ".next", "static"),
    path.join(distDir, ".next", "static"),
  );
  await copyIfPresent(path.join(projectDir, "public"), path.join(distDir, "public"));

  const startFile = path.join(distDir, "start");
  await writeFile(startFile, START_SCRIPT, "utf8");
  await chmod(startFile, 0o755);

  return distDir;
}

const currentFile = fileURLToPath(import.meta.url);
if (process.argv[1] && path.resolve(process.argv[1]) === currentFile) {
  const projectDir = path.resolve(path.dirname(currentFile), "..");
  const distDir = await packageStandalone(projectDir);
  console.log(`Standalone Node service created at ${distDir}`);
  console.log("Start it with: cd dist && node start");
}
