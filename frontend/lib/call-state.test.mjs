import assert from "node:assert/strict";
import { test } from "node:test";
import {
  buildCallHref,
  buildChatHrefFromCall,
  createAudioQueue,
  normalizeRecordedTranscript,
  preferredRecordingMimeType,
  shouldAcceptPreviewAudio,
  segmentAssistantText,
} from "./call-state.ts";

test("normalizeRecordedTranscript returns text only when recognition produced content", () => {
  assert.equal(normalizeRecordedTranscript(" 你好 "), "你好");
  assert.equal(normalizeRecordedTranscript("   "), null);
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

test("shouldAcceptPreviewAudio rejects stale playback generations after interruption", () => {
  assert.equal(
    shouldAcceptPreviewAudio({
      mounted: true,
      callEnding: false,
      currentCallGeneration: 3,
      previewCallGeneration: 3,
      currentPlaybackGeneration: 5,
      previewPlaybackGeneration: 4,
    }),
    false,
  );
  assert.equal(
    shouldAcceptPreviewAudio({
      mounted: true,
      callEnding: false,
      currentCallGeneration: 3,
      previewCallGeneration: 3,
      currentPlaybackGeneration: 5,
      previewPlaybackGeneration: 5,
    }),
    true,
  );
});

test("buildCallHref and buildChatHrefFromCall preserve agent and session ids", () => {
  const agentId = "agent/1";
  const sessionId = "session 1";

  assert.equal(buildCallHref(agentId, sessionId), "/call?agentId=agent%2F1&sessionId=session+1");
  assert.equal(buildCallHref(agentId, sessionId, 42), "/call?agentId=agent%2F1&sessionId=session+1&promptId=42");
  assert.equal(buildChatHrefFromCall(agentId, sessionId), "/chat?agentId=agent%2F1&sessionId=session+1");
});

test("preferredRecordingMimeType chooses supported webm codec without forcing unsupported mime", () => {
  assert.equal(
    preferredRecordingMimeType((mimeType) => mimeType === "audio/webm;codecs=opus"),
    "audio/webm;codecs=opus",
  );
  assert.equal(
    preferredRecordingMimeType((mimeType) => mimeType === "audio/webm"),
    "audio/webm",
  );
  assert.equal(preferredRecordingMimeType(() => false), undefined);
  assert.equal(preferredRecordingMimeType(undefined), undefined);
});
