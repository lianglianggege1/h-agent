import assert from "node:assert/strict";
import { test } from "node:test";
import {
  appendTranscript,
  buildCallHref,
  buildChatHrefFromCall,
  createAudioQueue,
  segmentAssistantText,
  shouldCommitUtterance,
} from "./call-state.ts";

test("shouldCommitUtterance commits only after configured transcript silence", () => {
  const state = { transcript: "你好", lastTranscriptAt: 1000 };

  assert.equal(shouldCommitUtterance({ ...state, now: 4999, silenceMs: 4000 }), false);
  assert.equal(shouldCommitUtterance({ ...state, now: 5000, silenceMs: 4000 }), true);
});

test("shouldCommitUtterance uses three seconds of silence by default", () => {
  const state = { transcript: "你好", lastTranscriptAt: 1000 };

  assert.equal(shouldCommitUtterance({ ...state, now: 3999 }), false);
  assert.equal(shouldCommitUtterance({ ...state, now: 4000 }), true);
});

test("appendTranscript refreshes timestamp only when normalized text changes", () => {
  const state = { transcript: "你好", lastTranscriptAt: 1000 };

  assert.equal(appendTranscript(state, " 你好 ", 2000), state);
  assert.deepEqual(appendTranscript(state, " 你好啊 ", 2000), {
    transcript: "你好啊",
    lastTranscriptAt: 2000,
  });
});

test("appendTranscript ignores blank interim transcript", () => {
  const state = { transcript: "你好", lastTranscriptAt: 1000 };

  assert.equal(appendTranscript(state, "   ", 2000), state);
  assert.equal(appendTranscript(state, "", 3000), state);
});

test("segmentAssistantText emits complete Chinese sentence and preserves remainder", () => {
  const result = segmentAssistantText("你好。我正在查询", "");

  assert.deepEqual(result.segments, ["你好。"]);
  assert.equal(result.remainder, "我正在查询");
});

test("segmentAssistantText trims sentence whitespace and keeps consecutive punctuation together", () => {
  const result = segmentAssistantText("你好。 我继续！真的吗？！", "");

  assert.deepEqual(result.segments, ["你好。", "我继续！", "真的吗？！"]);
  assert.equal(result.remainder, "");
});

test("segmentAssistantText combines previous remainder and flushes long remainder", () => {
  const result = segmentAssistantText("继续", "a".repeat(79));

  assert.deepEqual(result.segments, ["a".repeat(79) + "继续"]);
  assert.equal(result.remainder, "");
});

test("createAudioQueue enqueues, starts, finishes, and clears immutable queue state", () => {
  const queue = createAudioQueue();
  const withItem = queue.enqueue("/audio/1.mp3");
  const playing = withItem.startCurrent();
  const finished = playing.finishCurrent();
  const cleared = playing.enqueue("/audio/2.mp3").clear();

  assert.deepEqual(queue.items, []);
  assert.equal(queue.playing, false);
  assert.deepEqual(withItem.items, ["/audio/1.mp3"]);
  assert.equal(withItem.playing, false);
  assert.deepEqual(playing.items, ["/audio/1.mp3"]);
  assert.equal(playing.playing, true);
  assert.deepEqual(finished.items, []);
  assert.equal(finished.playing, false);
  assert.deepEqual(cleared.items, []);
  assert.equal(cleared.playing, false);
});

test("createAudioQueue safely finishes an empty queue", () => {
  const finished = createAudioQueue().finishCurrent();

  assert.deepEqual(finished.items, []);
  assert.equal(finished.playing, false);
});

test("buildCallHref and buildChatHrefFromCall preserve agent and session ids", () => {
  const agentId = "agent/1";
  const sessionId = "session 1";

  assert.equal(buildCallHref(agentId, sessionId), "/call?agentId=agent%2F1&sessionId=session+1");
  assert.equal(buildChatHrefFromCall(agentId, sessionId), "/chat?agentId=agent%2F1&sessionId=session+1");
});
