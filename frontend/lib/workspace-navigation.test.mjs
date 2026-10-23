import assert from "node:assert/strict";
import { test } from "node:test";
import { workspaceSection } from "./workspace-navigation.ts";

test("workspace navigation follows nested resource and agent routes", () => {
  assert.equal(workspaceSection("/me/skills/demo/releases/4")?.href, "/me/skills");
  assert.equal(workspaceSection("/me/agents/subagents/new")?.href, "/agents");
  assert.equal(workspaceSection("/me/agents/travel")?.href, "/agents");
  assert.equal(workspaceSection("/call")?.href, "/chat");
  assert.equal(workspaceSection("/me")?.label, "个人设置");
});

test("public and unrelated paths do not get workspace chrome", () => {
  for (const path of ["/", "/auth/login", "/auth/register", "/api/chat", "/me/skills-extra", "/agents-other"]) {
    assert.equal(workspaceSection(path), undefined, path);
  }
});
