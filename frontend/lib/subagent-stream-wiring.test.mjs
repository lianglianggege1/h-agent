import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const pageSource = await readFile(new URL("../app/chat/page.tsx", import.meta.url), "utf8");
const childSubmitStart = pageSource.indexOf("async function handleSubagentSubmit");
const childSubmitEnd = pageSource.indexOf("async function handleLogout", childSubmitStart);
const childSubmit = pageSource.slice(childSubmitStart, childSubmitEnd);
const childDrawerStart = pageSource.indexOf("{activeSubagent ? (");
const childDrawerEnd = pageSource.indexOf("</aside>", childDrawerStart);
const childDrawer = pageSource.slice(childDrawerStart, childDrawerEnd);

test("subagent direct stream consumes reasoning like the parent stream", () => {
  assert.match(childSubmit, /reasoningMessage/);
  assert.match(childSubmit, /onReasoning\(chunk\)[\s\S]*applyReasoningChunk/);
});

test("subagent direct stream consumes agent steps like the parent stream", () => {
  assert.match(childSubmit, /onAgentStep\(step\)[\s\S]*applyAgentStep/);
});

test("subagent drawer omits the generic pending assistant status bubble", () => {
  assert.doesNotMatch(childDrawer, /<PendingAssistantStatus\b/);
  assert.match(childDrawer, /toRenderableTurns\(subagentMessages\)\.filter\(isVisibleSubagentTurn\)/);
});
