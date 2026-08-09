import assert from "node:assert/strict";
import { test } from "node:test";

import eslintConfig from "../eslint.config.mjs";

test("ESLint ignores the generated standalone service", () => {
  const ignoredPatterns = eslintConfig.flatMap((entry) => entry.ignores ?? []);

  assert.ok(ignoredPatterns.includes("dist/**"));
});
