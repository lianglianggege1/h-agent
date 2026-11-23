package com.h.otheragents.a2a.story;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class StoryTaskWorkflow {
    private final Store store;
    private final StoryModel model;

    StoryTaskWorkflow(Store store, StoryModel model) {
        this.store = store;
        this.model = model;
    }

    Task get(String owner, String taskId) {
        Task task = store.find(owner, taskId).orElseThrow(() -> new RpcError(-32001, "Task not found"));
        if (task.state() == State.WORKING && Instant.parse(task.timestamp()).plus(Duration.ofMinutes(5))
                .isBefore(Instant.now())) {
            Task timedOut = task.updated(State.FAILED, task.turns(), "远端创作超时，请重新发起任务。");
            if (store.replace(task, timedOut)) return timedOut;
            return store.find(owner, taskId).orElseThrow(() -> new RpcError(-32001, "Task not found"));
        }
        return task;
    }

    Submission begin(String owner, String taskId, String contextId, String messageId, String text,
                     boolean longForm, boolean longFormAllowed, String parentTaskId) {
        Turn input = new Turn(messageId, text);
        boolean first = taskId == null;
        if (longForm && !longFormAllowed) throw new RpcError(-32004, "This story skill is unavailable");
        if (first && parentTaskId != null) {
            var parent = get(owner, parentTaskId);
            if (parent.longForm() && !longFormAllowed) throw new RpcError(-32001, "Task not found");
            if (parent.state() != State.COMPLETED || !parent.contextId().equals(contextId)) {
                throw new RpcError(-32602, "Revision requires a completed story in the same context");
            }
            longForm = parent.longForm();
        }
        Task task = first ? start(owner, contextId, input, longForm, parentTaskId) : get(owner, taskId);
        if (first && !java.util.Objects.equals(parentTaskId, task.parentTaskId())) {
            throw new RpcError(-32602, "Message ID already exists with a different story reference");
        }
        if (task.longForm() && !longFormAllowed) throw new RpcError(-32001, "Task not found");
        if (longForm && !task.longForm()) throw new RpcError(-32602, "The skill of an existing task cannot change");
        if (contextId != null && !contextId.equals(task.contextId())) {
            throw new RpcError(-32602, "Context does not match task");
        }

        Optional<Turn> handled = task.turns().stream().filter(turn -> turn.id().equals(messageId)).findFirst();
        if (handled.isPresent()) {
            if (!handled.get().text().equals(text)) {
                throw new RpcError(-32602, "Message ID already exists with different content");
            }
            if (task.state() != State.SUBMITTED) {
                return new Submission(task, false);
            }
        } else if (task.state() != State.INPUT_REQUIRED) {
            throw new RpcError(-32004, "Task cannot accept more input");
        }

        List<Turn> turns = new ArrayList<>(task.turns());
        if (handled.isEmpty()) turns.add(input);
        if (turns.size() > 20) throw new RpcError(-32602, "Too many story clarification turns");
        Task working = task.updated(State.WORKING, turns, null);
        if (!store.replace(task, working)) throw alreadyWorking();

        return new Submission(working, true);
    }

    Task run(Task working) {
        List<Turn> turns = working.turns();
        var current = new AtomicReference<>(working);
        Task result;
        try {
            var conversation = String.join("\n", turns.stream().map(Turn::text).toList());
            var parent = working.parentTaskId() == null ? null : get(working.owner(), working.parentTaskId());
            StoryBrief brief = parent == null ? StoryBrief.fromUserText(model.extract(conversation), turns) : null;
            List<String> missing = brief == null ? List.of() : brief.missing();
            if (!missing.isEmpty()) {
                result = working.updated(State.INPUT_REQUIRED, turns, "请补充" + String.join("、", missing) + "。");
            } else {
                // Save a bounded stream of cumulative snapshots for readers on any instance.
                var text = new StringBuilder();
                long[] nextFlush = { 0 };
                Consumer<String> chunks = delta -> {
                    text.append(delta);
                    long now = System.nanoTime();
                    if (now < nextFlush[0]) return;
                    Task previous = current.get();
                    Task progress = previous.updated(State.WORKING, turns, text.toString());
                    if (!store.replace(previous, progress)) throw alreadyWorking();
                    current.set(progress);
                    nextFlush[0] = now + Duration.ofMillis(120).toNanos();
                };
                String story = parent == null ? model.write(brief, working.longForm(), chunks)
                        : model.revise(parent.output(), conversation, chunks);
                if (story == null || story.isBlank()) throw new IllegalStateException("Empty story");
                result = current.get().updated(State.COMPLETED, turns, story);
            }
        } catch (RuntimeException failure) {
            result = current.get().updated(State.FAILED, turns, "远端创作失败，请重新发起任务。");
        }
        if (!store.replace(current.get(), result)) return get(working.owner(), working.id());
        return result;
    }

    Task cancel(String owner, String taskId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Task task = get(owner, taskId);
            if (task.state() == State.CANCELED) return task;
            if (task.state().terminal()) throw new RpcError(-32002, "Task cannot be canceled");
            Task canceled = task.updated(State.CANCELED, task.turns(), null);
            if (store.replace(task, canceled)) return canceled;
        }
        throw alreadyWorking();
    }

    private Task start(String owner, String contextId, Turn input, boolean longForm, String parentTaskId) {
        // Identical initial message IDs retry the same submission within an authenticated owner.
        String taskId = UUID.nameUUIDFromBytes((owner + "\0" + input.id()).getBytes(StandardCharsets.UTF_8))
                .toString();
        String context = contextId == null ? UUID.randomUUID().toString() : contextId;
        return store.start(new Task(taskId, context, owner, State.SUBMITTED, List.of(input), null,
                Instant.now().toString(), 0, longForm, parentTaskId));
    }

    private static RpcError alreadyWorking() {
        return new RpcError(-32004, "Task is already processing this interaction");
    }

    public enum State {
        SUBMITTED, WORKING, INPUT_REQUIRED, COMPLETED, FAILED, CANCELED;

        boolean terminal() { return this == COMPLETED || this == FAILED || this == CANCELED; }
        boolean streamDone() { return terminal() || this == INPUT_REQUIRED; }
    }

    record Submission(Task task, boolean claimed) {}

    public record Turn(String id, String text) {}

    public record Task(String id, String contextId, String owner, State state, List<Turn> turns,
                       String output, String timestamp, long revision, boolean longForm, String parentTaskId) {
        Task updated(State state, List<Turn> turns, String output) {
            return new Task(id, contextId, owner, state, List.copyOf(turns), output,
                    Instant.now().toString(), revision + 1, longForm, parentTaskId);
        }
    }

    public record StoryBrief(String topic, String style, String audience) {
        static StoryBrief fromUserText(StoryBrief extracted, List<Turn> turns) {
            if (extracted == null) return new StoryBrief(null, null, null);
            return new StoryBrief(quoted(extracted.topic(), turns), quoted(extracted.style(), turns),
                    quoted(extracted.audience(), turns));
        }

        private static String quoted(String value, List<Turn> turns) {
            if (value == null || value.isBlank()) return null;
            String excerpt = value.strip();
            return turns.stream().anyMatch(turn -> turn.text().contains(excerpt)) ? excerpt : null;
        }

        List<String> missing() {
            List<String> missing = new ArrayList<>();
            if (topic == null || topic.isBlank()) missing.add("故事主题");
            if (style == null || style.isBlank()) missing.add("故事风格");
            if (audience == null || audience.isBlank()) missing.add("目标受众");
            return missing;
        }
    }

    interface Store {
        Optional<Task> find(String owner, String id);
        Task start(Task task);
        boolean replace(Task expected, Task updated);
    }

    interface StoryModel {
        StoryBrief extract(String conversation);
        String write(StoryBrief brief);

        default String write(StoryBrief brief, boolean longForm, Consumer<String> chunks) {
            return write(brief);
        }

        default String revise(String prior, String instruction, Consumer<String> chunks) {
            throw new UnsupportedOperationException("Story revision is unavailable");
        }
    }

    static final class RpcError extends RuntimeException {
        final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
