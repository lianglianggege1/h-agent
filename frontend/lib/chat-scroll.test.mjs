import assert from "node:assert/strict";
import { test } from "node:test";
import { scrollTopAfterPrepend } from "./chat-scroll.ts";

test("scrollTopAfterPrepend offsets the viewport by the prepended content height", () => {
  assert.equal(
    scrollTopAfterPrepend({
      previousScrollHeight: 1000,
      previousScrollTop: 120,
      nextScrollHeight: 1460,
    }),
    580,
  );
});

test("scrollTopAfterPrepend keeps the previous position when height is unchanged", () => {
  assert.equal(
    scrollTopAfterPrepend({
      previousScrollHeight: 1000,
      previousScrollTop: 120,
      nextScrollHeight: 1000,
    }),
    120,
  );
});

test("scrollTopAfterPrepend never returns a negative position", () => {
  assert.equal(
    scrollTopAfterPrepend({
      previousScrollHeight: 1000,
      previousScrollTop: 20,
      nextScrollHeight: 900,
    }),
    0,
  );
});
