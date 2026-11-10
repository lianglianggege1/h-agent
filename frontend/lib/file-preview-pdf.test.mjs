import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import { test } from "node:test";
import { getDocument, GlobalWorkerOptions, version } from "pdfjs-dist/legacy/build/pdf.mjs";

const require = createRequire(import.meta.url);

test("the actual PDF engine renders both fixture pages and uses a matching local worker", async () => {
  const worker = new URL(`../public/pdfjs/${version}/pdf.worker.min.mjs`, import.meta.url);
  // Builds generate these assets; clean test checkouts can use the identical installed worker.
  let workerBytes;
  try { workerBytes = await readFile(worker); } catch (error) { if (error.code !== "ENOENT") throw error; }
  const installed = require.resolve("pdfjs-dist/legacy/build/pdf.worker.min.mjs");
  if (workerBytes) assert.deepEqual(workerBytes, await readFile(installed));
  GlobalWorkerOptions.workerSrc = pathToFileURL(installed).href;
  const bytes = new Uint8Array(await readFile(new URL("./fixtures/preview.pdf", import.meta.url)));
  const task = getDocument({ data: bytes });
  try {
    const pdf = await task.promise;
    assert.equal(pdf.numPages, 2);
    for (const [number, expected] of [[1, [51, 102, 204, 255]], [2, [204, 51, 51, 255]]]) {
      const page = await pdf.getPage(number);
      const holder = pdf.canvasFactory.create(200, 100);
      const { canvas, context } = holder;
      await page.render({ canvas, viewport: page.getViewport({ scale: 1 }) }).promise;
      assert.deepEqual([...context.getImageData(30, 60, 1, 1).data], expected);
      pdf.canvasFactory.destroy(holder);
    }
  } finally { await task.destroy(); }
});
