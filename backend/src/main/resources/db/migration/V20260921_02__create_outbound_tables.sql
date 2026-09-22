CREATE TABLE outbound_contacts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    phone VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    consent_basis VARCHAR(256) NOT NULL,
    dnc BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    UNIQUE(user_id, phone)
);
CREATE INDEX outbound_contacts_user ON outbound_contacts(user_id);

CREATE TABLE outbound_tasks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    name VARCHAR(256) NOT NULL,
    agent_binding_snapshot TEXT NOT NULL,
    communication_goal VARCHAR(1024) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'READY',
    status_reason VARCHAR(256),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE(user_id, request_id)
);
CREATE UNIQUE INDEX outbound_one_running_task ON outbound_tasks(user_id) WHERE status = 'RUNNING';
CREATE INDEX outbound_tasks_user ON outbound_tasks(user_id);

CREATE TABLE outbound_calls (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES outbound_tasks(id),
    contact_id BIGINT NOT NULL REFERENCES outbound_contacts(id),
    user_id BIGINT NOT NULL,
    phone_snapshot VARCHAR(32) NOT NULL,
    name_snapshot VARCHAR(128),
    voice_call_id VARCHAR(36) UNIQUE REFERENCES voice_calls(id),
    stage VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    connect_result VARCHAR(32),
    dialogue_result VARCHAR(32),
    reason VARCHAR(256),
    time_facts TEXT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE(task_id, phone_snapshot)
);
CREATE UNIQUE INDEX outbound_one_active_call ON outbound_calls(user_id) WHERE stage IN ('PREPARING','DIALING','ACTIVE','ENDING','UNKNOWN');
CREATE INDEX outbound_calls_task ON outbound_calls(task_id);
CREATE INDEX outbound_calls_stage ON outbound_calls(task_id, stage);
