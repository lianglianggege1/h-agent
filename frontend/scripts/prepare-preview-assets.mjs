import { cp, mkdir, readFile, rm } from "node:fs/promises";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const packageDir = path.dirname(require.resolve("pdfjs-dist/package.json"));
const { version } = JSON.parse(await readFile(path.join(packageDir, "package.json"), "utf8"));
const assets = path.join(root, "public", "pdfjs");
// Keep the worker, CMaps, fonts and decoder binaries on our own origin, matching the JS version.
await rm(assets, { recursive: true, force: true });
const destination = path.join(assets, version);
await mkdir(destination, { recursive: true });
await cp(path.join(packageDir, "legacy", "build", "pdf.worker.min.mjs"), path.join(destination, "pdf.worker.min.mjs"));
for (const folder of ["cmaps", "standard_fonts", "wasm"]) {
  await cp(path.join(packageDir, folder), path.join(destination, folder), { recursive: true });
}
console.log(`Prepared local PDF preview assets (${version})`);
