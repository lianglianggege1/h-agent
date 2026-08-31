import assert from "node:assert/strict";
import { test } from "node:test";

const {
  initialMemoryPageState,
  memoryLoaded,
  memoryLoadFailed,
  retryMemoryLoad,
  startEditing,
  updateDraft,
  cancelEditing,
  beginSaving,
  saveSucceeded,
  saveConflict,
  saveFailed,
  conflictReloaded,
  conflictResumeEditing,
  staleEditReloaded,
  isMemoryDraftDirty,
} = await import("./harness-memory-state.ts");

function loadedState(content = "# 记忆", revision = 3) {
  const initial = initialMemoryPageState();
  return memoryLoaded(initial, {
    content,
    revision,
    exists: true,
    updatedAt: "2026-08-31T06:30:00Z",
  });
}

test("initialMemoryPageState starts in loading with empty document", () => {
  const state = initialMemoryPageState();
  assert.deepEqual(state, {
    mode: "loading",
    document: null,
    draft: "",
    revisionStale: false,
    loadError: "",
  });
  assert.equal(isMemoryDraftDirty(state), false);
});

test("memoryLoaded enters read mode and resets draft flags", () => {
  const document = { content: "# 记忆", revision: 3, exists: true, updatedAt: null };
  const state = memoryLoaded(initialMemoryPageState(), document);
  assert.equal(state.mode, "read");
  assert.equal(state.document, document);
  assert.equal(state.draft, "");
  assert.equal(state.revisionStale, false);
});

test("memoryLoadFailed enters error mode without leaking raw error text", () => {
  const state = memoryLoadFailed(loadedState());
  assert.equal(state.mode, "error");
  assert.notEqual(state.loadError, "");
  assert.equal(state.loadError.includes("SyntaxError"), false);
});

test("retryMemoryLoad only retries from error mode", () => {
  const failed = memoryLoadFailed(initialMemoryPageState());
  const retried = retryMemoryLoad(failed);
  assert.equal(retried.mode, "loading");
  assert.equal(retried.loadError, "");

  const read = loadedState();
  assert.equal(retryMemoryLoad(read), read);
});

test("startEditing seeds draft from the loaded document only in read mode", () => {
  const state = startEditing(loadedState("# 草稿种子"));
  assert.equal(state.mode, "edit");
  assert.equal(state.draft, "# 草稿种子");

  const loading = initialMemoryPageState();
  assert.equal(startEditing(loading), loading);
  const errored = memoryLoadFailed(loadedState());
  assert.equal(startEditing(errored), errored);
});

test("updateDraft only mutates edit mode", () => {
  const editing = startEditing(loadedState());
  const updated = updateDraft(editing, "## 新草稿");
  assert.equal(updated.draft, "## 新草稿");

  const read = loadedState();
  assert.equal(updateDraft(read, "## 新草稿"), read);
});

test("cancelEditing returns to read mode and discards the draft", () => {
  const editing = updateDraft(startEditing(loadedState("# 原文")), "# 改动");
  const cancelled = cancelEditing(editing);
  assert.equal(cancelled.mode, "read");
  assert.equal(cancelled.draft, "");
  assert.equal(cancelled.document.content, "# 原文");

  const read = loadedState();
  assert.equal(cancelEditing(read), read);
});

test("beginSaving moves edit into saving and keeps the draft", () => {
  const editing = updateDraft(startEditing(loadedState()), "# 待保存");
  const saving = beginSaving(editing);
  assert.equal(saving.mode, "saving");
  assert.equal(saving.draft, "# 待保存");

  const read = loadedState();
  assert.equal(beginSaving(read), read);
});

test("saveSucceeded publishes the server baseline in read mode", () => {
  const saving = beginSaving(startEditing(loadedState("# 旧", 3)));
  const saved = saveSucceeded(saving, {
    content: "# 新",
    revision: 4,
    exists: true,
    updatedAt: "2026-08-31T07:00:00Z",
  });
  assert.equal(saved.mode, "read");
  assert.equal(saved.document.revision, 4);
  assert.equal(saved.draft, "");
  assert.equal(saved.revisionStale, false);
});

test("saveConflict marks the revision stale and drops to conflict mode", () => {
  const saving = beginSaving(updateDraft(startEditing(loadedState()), "# 我的草稿"));
  const conflict = saveConflict(saving);
  assert.equal(conflict.mode, "conflict");
  assert.equal(conflict.revisionStale, true);
  assert.equal(conflict.draft, "# 我的草稿");
});

test("saveFailed falls back to edit mode and keeps the draft", () => {
  const saving = beginSaving(updateDraft(startEditing(loadedState()), "# 我的草稿"));
  const failed = saveFailed(saving);
  assert.equal(failed.mode, "edit");
  assert.equal(failed.draft, "# 我的草稿");
});

test("saveSucceeded and saveConflict only apply in saving mode", () => {
  const read = loadedState();
  assert.equal(saveSucceeded(read, { content: "x", revision: 1, exists: true, updatedAt: null }), read);
  assert.equal(saveConflict(read), read);
  assert.equal(saveFailed(read), read);
});

test("conflictReloaded adopts the fresh server document in read mode", () => {
  const conflict = saveConflict(beginSaving(startEditing(loadedState("# 旧", 3))));
  const reloaded = conflictReloaded(conflict, {
    content: "# 服务器新内容",
    revision: 9,
    exists: true,
    updatedAt: "2026-08-31T08:00:00Z",
  });
  assert.equal(reloaded.mode, "read");
  assert.equal(reloaded.document.content, "# 服务器新内容");
  assert.equal(reloaded.document.revision, 9);
  assert.equal(reloaded.revisionStale, false);
  assert.equal(reloaded.draft, "");
});

test("conflictResumeEditing keeps the local draft for manual merging", () => {
  const conflict = saveConflict(
    beginSaving(updateDraft(startEditing(loadedState("# 旧", 3)), "# 本地草稿")),
  );
  const resumed = conflictResumeEditing(conflict);
  assert.equal(resumed.mode, "edit");
  assert.equal(resumed.draft, "# 本地草稿");
  assert.equal(resumed.revisionStale, true);
});

test("conflict handlers only apply in conflict mode", () => {
  const read = loadedState();
  assert.equal(conflictReloaded(read, { content: "x", revision: 1, exists: true, updatedAt: null }), read);
  assert.equal(conflictResumeEditing(read), read);
});

test("staleEditReloaded adopts the fresh document from a stale edit session", () => {
  const stale = conflictResumeEditing(saveConflict(beginSaving(startEditing(loadedState("# 旧", 3)))));
  const reloaded = staleEditReloaded(stale, {
    content: "# 服务器新内容",
    revision: 9,
    exists: true,
    updatedAt: "2026-08-31T08:00:00Z",
  });
  assert.equal(reloaded.mode, "read");
  assert.equal(reloaded.document.revision, 9);
  assert.equal(reloaded.document.content, "# 服务器新内容");
  assert.equal(reloaded.draft, "");
  assert.equal(reloaded.revisionStale, false);
});

test("staleEditReloaded only applies to a stale edit session", () => {
  const freshEdit = startEditing(loadedState());
  assert.equal(staleEditReloaded(freshEdit, { content: "x", revision: 1, exists: true, updatedAt: null }), freshEdit);
  const read = loadedState();
  assert.equal(staleEditReloaded(read, { content: "x", revision: 1, exists: true, updatedAt: null }), read);
});

test("isMemoryDraftDirty reflects draft divergence while editing", () => {
  assert.equal(isMemoryDraftDirty(loadedState()), false);
  assert.equal(isMemoryDraftDirty(startEditing(loadedState())), false);
  assert.equal(isMemoryDraftDirty(updateDraft(startEditing(loadedState()), "改动")), true);
  assert.equal(isMemoryDraftDirty(beginSaving(updateDraft(startEditing(loadedState()), "改动"))), true);
  assert.equal(isMemoryDraftDirty(saveConflict(beginSaving(updateDraft(startEditing(loadedState()), "改动")))), true);
});
