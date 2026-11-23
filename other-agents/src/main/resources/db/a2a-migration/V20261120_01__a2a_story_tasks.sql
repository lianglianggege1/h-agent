CREATE TABLE other_agents.a2a_story_tasks (
    id VARCHAR(36) PRIMARY KEY,
    owner VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    payload JSONB NOT NULL
);
