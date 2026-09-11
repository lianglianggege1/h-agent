import assert from "node:assert/strict";
import { test } from "node:test";
import { buildCallHref, originalChatHref } from "./voice-call-url.ts";

test("voice links preserve an opaque existing session id", () => {
  const sessionId = "session/中文?x=1";
  assert.equal(buildCallHref(sessionId), "/call?sessionId=session%2F%E4%B8%AD%E6%96%87%3Fx%3D1");
  assert.equal(
    originalChatHref(sessionId),
    "/chat?agentId=standard-chat&sessionId=session%2F%E4%B8%AD%E6%96%87%3Fx%3D1",
  );
});
