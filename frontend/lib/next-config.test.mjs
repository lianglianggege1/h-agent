import assert from "node:assert/strict";
import { test } from "node:test";

import nextConfig from "../next.config.ts";

test("Next rewrite proxy allows five-minute chat streams", () => {
  assert.equal(nextConfig.experimental?.proxyTimeout, 300_000);
});

test("Next build produces a self-contained Node.js server", () => {
  assert.equal(nextConfig.output, "standalone");
});
