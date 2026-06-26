# Agent Call Design

- Date: 2026-06-26
- Scope: `/call` voice conversation page, user call recording persistence, assistant TTS persistence, and chat-page call entry
- Status: design for review

## Background

The app already has a unified text chat flow for ordinary chat and domain Agents. The frontend sends messages through `POST /api/chat/messages/stream`; the backend validates the session, routes by `agentId`, streams assistant text events, and persists user and assistant messages in `chat_session_messages`.

The new goal is to add a phone-like conversation mode for both ordinary and domain Agents:

1. Users speak to an Agent.
2. The Agent understands the speech through text transcription and replies by voice.
3. The same conversation is recorded as text messages.
4. Users can hang up and continue the same session in the text chat page.
5. The text chat page shows both text and a playable audio bar for phone-created user and assistant messages.

The call feature must not create a separate conversation system. It is a voice interaction shell on top of the existing chat session model.

## Goals

1. Add a `/call` page for ordinary chat and domain Agents.
2. Add a call button in `/chat` that opens the current session in `/call`.
3. Use browser speech recognition for first-version STT.
4. Treat 3 seconds of no new recognized speech as the end of one user utterance.
5. Reuse the existing chat stream for Agent responses.
6. Play assistant speech while text is still streaming by doing lightweight sentence-level TTS preview.
7. If the user speaks while assistant audio is playing, stop local playback and listen to the user.
8. Persist a complete user recording audio resource on the user message.
9. Persist one complete assistant TTS audio resource on the assistant message.
10. Hang up by returning to the same text chat session.

## Non-Goals

1. Do not implement MiniMax WebSocket TTS in the first version.
2. Do not implement server-side speech-to-text.
3. Do not cancel an in-flight chat stream when the user interrupts assistant playback.
4. Do not support multiple concurrent call turns in the same call page.
5. Do not implement voice cloning, voice design, or user-managed voice selection in this iteration.
6. Do not create a separate call conversation table unless implementation discovers a hard persistence gap.

## Product Behavior

### Routes

The call page route is:

```text
/call?agentId={agentId}&sessionId={sessionId}
```

`agentId` identifies ordinary chat or a domain Agent. `sessionId` is optional only for entry points that do not yet have a session. If `sessionId` is absent, `/call` creates or resolves a session using the same rules as `/chat`.

The text chat page must support restoring a specific session from:

```text
/chat?agentId={agentId}&sessionId={sessionId}
```

If `/chat` currently ignores `sessionId`, it must be extended to activate that session first.

### Text Chat Entry

`/chat` adds a phone button for the current session. Clicking it opens:

```text
/call?agentId={currentAgentId}&sessionId={sessionId}
```

If the current text chat view has no session yet, create a session first and then navigate to `/call`.

### Hang Up

There is no separate "switch to text" action. Hang up is the single exit action from the call page.

On hang up, the call page:

1. Stops speech recognition.
2. Stops `MediaRecorder`.
3. Cancels or finalizes any active call turn as appropriate.
4. Stops local audio playback and clears the preview TTS queue.
5. Navigates to:

```text
/chat?agentId={currentAgentId}&sessionId={sessionId}
```

The text page then continues the same conversation.

## Call State Machine

The main call states are:

```text
idle -> listening -> committing -> streaming -> speaking
                         ^                         |
                         |                         v
                     interrupted <- user speaks while audio plays
```

### `idle`

The page is loaded but is not actively listening. The user may need to grant microphone permission.

### `listening`

The browser is capturing microphone input. `SpeechRecognition` produces interim and final transcript text. `MediaRecorder` produces audio chunks for the current call turn.

### `committing`

When the frontend sees no new recognition text for 3 seconds, the current utterance is considered complete.

The frontend:

1. Stops the current turn recording.
2. Sends the final transcript to the existing chat stream endpoint.
3. Waits for the backend to return the persisted user message event.
4. Finalizes the call turn recording and binds it to the user message.

Empty transcripts and extremely short noise-only transcripts are not sent.

### `streaming`

The page consumes `POST /api/chat/messages/stream`. It renders text chunks as subtitles. It also detects completed sentence segments and sends them to preview TTS for low-latency playback.

### `speaking`

Preview TTS audio is played from a local queue. The final assistant audio resource is not created from these preview segments. It is created after the assistant message is complete.

### `interrupted`

If the user starts speaking while assistant preview audio is playing:

1. Stop the current audio element.
2. Clear all queued preview audio.
3. Keep the in-flight chat stream alive.
4. Return to `listening`.

The old assistant text continues to be persisted by the existing backend flow. The phone page does not play the remaining audio from that old reply.

If the previous chat stream has not finished when the new user utterance is ready, the phone page queues the new transcript locally and displays a waiting state. It sends the new message only after the previous stream reaches `done`, `blocked`, or `error`. This preserves the current per-session stream concurrency model.

## Architecture

### Frontend

Add:

```text
frontend/app/call/page.tsx
frontend/lib/voice.ts
frontend/lib/call-state.ts
```

`/call` reuses existing chat/session helpers:

1. `bootstrapChatSession`
2. `createChatSession`
3. `resolveChatSession`
4. `activateHistorySession`
5. `getChatSessionMessages`
6. `buildChatSendPayload`
7. `apiStream`

`frontend/lib/voice.ts` wraps voice-specific API calls:

1. start call turn
2. upload call turn chunk
3. finalize call turn
4. cancel call turn
5. preview TTS
6. message TTS

`frontend/lib/call-state.ts` keeps pure helpers for:

1. 3-second silence detection
2. sentence segmentation for preview TTS
3. audio queue operations
4. interruption behavior

### Backend

Add a voice package:

```text
backend/src/main/java/com/h/backend/voice/config/VoiceTtsProperties.java
backend/src/main/java/com/h/backend/voice/controller/VoiceController.java
backend/src/main/java/com/h/backend/voice/service/VoiceTtsService.java
backend/src/main/java/com/h/backend/voice/service/CallTurnService.java
backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsClient.java
backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsRequest.java
backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsResult.java
```

The backend keeps MiniMax credentials server-side. The frontend never calls MiniMax directly.

MiniMax first-version configuration:

1. HTTP T2A, not WebSocket T2A.
2. Model configurable, default `speech-2.8-turbo`.
3. Audio format configurable, default `mp3`.
4. Default `voiceId` configurable from application properties or `.env`.

## API Design

### Start Call Turn

```text
POST /api/voice/call-turns/start
```

Request:

```json
{
  "sessionId": "session-id",
  "agentId": "standard-chat"
}
```

Response:

```json
{
  "turnId": "turn-id"
}
```

The backend creates a user-owned temporary turn directory.

### Upload Call Turn Chunk

```text
POST /api/voice/call-turns/{turnId}/chunks
```

Multipart fields:

```text
chunk: audio blob
sequence: integer
mimeType: audio/webm
```

The backend stores chunks in sequence order under a temporary directory.

### Finalize Call Turn

```text
POST /api/voice/call-turns/{turnId}/finalize
```

Request:

```json
{
  "sessionId": "session-id",
  "agentId": "standard-chat",
  "messageId": "user-message-id",
  "transcript": "用户识别文本"
}
```

The backend:

1. Verifies the turn belongs to the current user.
2. Verifies the session belongs to the current user.
3. Verifies the message belongs to the session.
4. Verifies the message is a user message.
5. Packages chunks into one playable WebM audio file for the first version.
6. Saves the audio using the existing resource storage.
7. Inserts a `chat_message_resources` row bound to the user message.
8. Cleans temporary chunks.

Response:

```json
{
  "resourceId": "resource-id",
  "viewUrl": "/api/chat/resources/resource-id/content",
  "downloadUrl": "/api/chat/resources/resource-id/download",
  "mimeType": "audio/webm",
  "durationMs": null
}
```

### Cancel Call Turn

```text
POST /api/voice/call-turns/{turnId}/cancel
```

Cancels and deletes temporary chunks for an unfinished turn.

### Preview TTS

```text
POST /api/voice/tts/preview
```

Request:

```json
{
  "sessionId": "session-id",
  "agentId": "standard-chat",
  "text": "一句用于即时播放的文本"
}
```

Response:

```text
Content-Type: audio/mpeg
binary audio body
```

This endpoint does not persist audio and does not bind resources. It exists only for low-latency phone playback.

### Message TTS

```text
POST /api/voice/tts/message
```

Request:

```json
{
  "sessionId": "session-id",
  "agentId": "standard-chat",
  "messageId": "assistant-message-id"
}
```

The backend reads the assistant message content from the database. It does not trust frontend-provided text.

The backend:

1. Verifies the session belongs to the current user.
2. Verifies the message belongs to the session.
3. Verifies the message is an assistant AI message.
4. Calls MiniMax HTTP T2A with the full assistant text.
5. Saves one complete audio resource.
6. Binds the resource to the assistant message.

Response:

```json
{
  "resourceId": "resource-id",
  "viewUrl": "/api/chat/resources/resource-id/content",
  "downloadUrl": "/api/chat/resources/resource-id/download",
  "mimeType": "audio/mpeg",
  "durationMs": null
}
```

## Chat Stream Event Extensions

The existing stream must be extended with one new event and one richer terminal event.

### `user_message`

Emitted after `appendUserMessage(...)` succeeds.

```json
{
  "type": "user_message",
  "content": "",
  "message": {
    "id": "user-message-id",
    "role": "user",
    "messageType": "USER",
    "content": "用户识别文本"
  }
}
```

The call page uses this ID to finalize and bind the user recording.

### `done`

`done` includes the final assistant message for text assistant replies.

```json
{
  "type": "done",
  "content": "",
  "message": {
    "id": "assistant-message-id",
    "role": "assistant",
    "messageType": "AI",
    "content": "完整 assistant 回复"
  }
}
```

The call page uses this ID to request complete assistant message TTS.

For image-only responses, `done.message` is absent. The call page skips assistant TTS in that case.

## Storage Model

Prefer reusing `chat_message_resources`.

User call recording:

```text
resource_type = AUDIO
resource_role = ATTACHMENT
metadata.source = USER_RECORDING
mime_type = audio/webm
message_id = user message id
session_id = current session id
```

Assistant TTS:

```text
resource_type = AUDIO
resource_role = ATTACHMENT
metadata.source = ASSISTANT_TTS
mime_type = audio/mpeg
message_id = assistant message id
session_id = current session id
```

No new `resource_role` values are required in the first version. Voice-specific meaning is stored in metadata.

Recommended metadata:

```json
{
  "source": "USER_RECORDING",
  "callTurnId": "turn-id",
  "transcript": "用户识别文本",
  "durationMs": null
}
```

```json
{
  "source": "ASSISTANT_TTS",
  "voiceId": "voice-id",
  "model": "speech-2.8-turbo",
  "durationMs": null
}
```

Temporary chunks are stored locally for the first version:

```text
/tmp/h-agent/call-turns/{userId}/{turnId}/chunk-000.webm
/tmp/h-agent/call-turns/{userId}/{turnId}/chunk-001.webm
```

A scheduled cleanup removes stale, unfinalized turn directories.

## UI Design

The call page is mobile-first and desktop-compatible. It is not a duplicate chat page.

Layout:

1. Top: Agent name, Agent domain or ordinary-chat label, call status.
2. Middle: Live subtitles and current state.
3. Bottom: call controls.

Controls:

1. Start or pause listening.
2. Mute or unmute assistant playback.
3. Hang up.

Subtitle states:

1. User speaking: show interim transcript.
2. User sent: show finalized transcript.
3. Assistant replying: show streamed assistant subtitle.
4. Domain Agent executing: show concise `agent_step` status.

Text chat history renders:

1. User message text plus one playable user recording audio bar.
2. Assistant message text plus one playable assistant TTS audio bar.

The current chat page already renders `audio/*` resources with `<audio controls>`, but it must be verified that user and assistant text resources render in the same message bubble.

## Error Handling

### Browser Limitations

If `SpeechRecognition` is unavailable, show a browser support message and offer to return to text chat.

If microphone permission is denied, show a permission message and offer to return to text chat.

### Recording Failures

If chunk upload fails, the phone page may continue text chat using the transcript. The user recording audio bar will be absent for that turn.

If finalize fails, show a small non-blocking message that the recording was not saved.

### TTS Failures

If preview TTS fails, subtitles continue and playback skips that segment.

If full assistant message TTS fails, the assistant text remains persisted. The assistant audio bar will be absent.

### Chat Stream Failures

Use the same error behavior as `/chat`. Keep the call page recoverable so the user can start listening again or hang up to text chat.

## Security and Limits

All voice endpoints require authentication.

Validation rules:

1. A turn belongs only to the user who started it.
2. A session must belong to the current user.
3. A message must belong to the session.
4. User recordings can bind only to user messages.
5. Assistant TTS can bind only to assistant AI messages.
6. Message TTS reads text from the database.

Limits:

1. Maximum call turn duration.
2. Maximum chunk size.
3. Maximum number of chunks per turn.
4. Maximum TTS text length.
5. Rate limit preview TTS to avoid runaway sentence splitting.
6. Cleanup stale temporary chunks.

## Testing

Backend tests:

1. MiniMax TTS client sends the expected request, authorization header, model, voice ID, and audio settings.
2. MiniMax TTS client handles provider errors and malformed responses.
3. Call turn start/chunk/finalize/cancel lifecycle.
4. Finalize aggregates chunks and binds one audio resource to a user message.
5. Message TTS reads assistant text from the database and binds one audio resource to the assistant message.
6. Unauthorized session, message, and turn access is rejected.
7. Wrong message type is rejected.
8. Empty or too-long TTS text is rejected.
9. `user_message` SSE event is emitted after the user message is persisted.
10. `done` SSE event contains the assistant message for text assistant replies.

Frontend tests:

1. 3-second silence commits one utterance.
2. Interim transcript refreshes the silence timer.
3. Assistant playback interruption clears the audio queue.
4. A new transcript waits if the previous stream has not ended.
5. The `/chat` call button builds the correct `/call` URL.
6. Hang up builds the correct `/chat` URL for the same session.
7. Sentence segmentation produces reasonable preview TTS segments.
8. Audio resources render with the correct message in text history.

Browser validation:

1. Chrome and Edge microphone permission flow.
2. STT to text to chat stream.
3. User recording chunk upload and final audio resource playback.
4. Assistant preview playback while streaming.
5. Assistant full TTS resource playback from chat history.
6. Mobile viewport does not overlap controls and subtitles.

## Implementation Notes

The first version keeps the provider-specific details inside the voice TTS client. The call page and chat message model talk in terms of user recordings, assistant TTS, sessions, messages, and resources, not MiniMax-specific fields.

The design intentionally avoids cancelling in-flight chat streams. That keeps the implementation aligned with the existing concurrency guard and persistence model. True barge-in cancellation can be designed later after the first phone experience is stable.
