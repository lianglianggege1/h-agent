import assert from "node:assert/strict";
import { test } from "node:test";
import { safeMarkdownHref } from "./markdown-links.ts";

test("safeMarkdownHref allows common web and relative links", () => {
  assert.equal(safeMarkdownHref("https://example.com/a?b=1"), "https://example.com/a?b=1");
  assert.equal(safeMarkdownHref("http://example.com"), "http://example.com");
  assert.equal(safeMarkdownHref("mailto:hello@example.com"), "mailto:hello@example.com");
  assert.equal(safeMarkdownHref("tel:+8613800138000"), "tel:+8613800138000");
  assert.equal(safeMarkdownHref("/me/knowledge"), "/me/knowledge");
  assert.equal(safeMarkdownHref("./local"), "./local");
  assert.equal(safeMarkdownHref("../parent"), "../parent");
  assert.equal(safeMarkdownHref("#section"), "#section");
});

test("safeMarkdownHref rejects empty and executable links", () => {
  assert.equal(safeMarkdownHref(undefined), null);
  assert.equal(safeMarkdownHref(""), null);
  assert.equal(safeMarkdownHref("   "), null);
  assert.equal(safeMarkdownHref("//example.com/protocol-relative"), null);
  assert.equal(safeMarkdownHref("javascript:alert(1)"), null);
  assert.equal(safeMarkdownHref("data:text/html,<script>alert(1)</script>"), null);
  assert.equal(safeMarkdownHref("vbscript:msgbox(1)"), null);
});
