package com.h.otheragents.a2a.story;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

final class JdbcStoryTaskStore implements StoryTaskWorkflow.Store {
    private final JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    JdbcStoryTaskStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoryTaskWorkflow.Task> find(String owner, String id) {
        return jdbc.sql("SELECT payload FROM other_agents.a2a_story_tasks WHERE owner = ? AND id = ?")
                .params(owner, id)
                .query((row, index) -> decode(row.getString("payload")))
                .optional();
    }

    @Override
    public StoryTaskWorkflow.Task start(StoryTaskWorkflow.Task task) {
        int inserted = jdbc.sql("""
                INSERT INTO other_agents.a2a_story_tasks (id, owner, revision, payload)
                VALUES (?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (id) DO NOTHING
                """).params(task.id(), task.owner(), task.revision(), encode(task)).update();
        return inserted == 1 ? task : find(task.owner(), task.id()).orElseThrow();
    }

    @Override
    public boolean replace(StoryTaskWorkflow.Task expected, StoryTaskWorkflow.Task updated) {
        return jdbc.sql("""
                UPDATE other_agents.a2a_story_tasks SET revision = ?, payload = CAST(? AS jsonb)
                WHERE id = ? AND owner = ? AND revision = ?
                """).params(updated.revision(), encode(updated), expected.id(), expected.owner(), expected.revision())
                .update() == 1;
    }

    private String encode(StoryTaskWorkflow.Task task) {
        return json.valueToTree(task).toString();
    }

    private StoryTaskWorkflow.Task decode(String payload) {
        try {
            return json.readValue(payload, StoryTaskWorkflow.Task.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid story task record", failure);
        }
    }
}
