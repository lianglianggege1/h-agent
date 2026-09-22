ALTER TABLE outbound_calls
    ADD COLUMN originate_job_id VARCHAR(36),
    ADD COLUMN originate_state VARCHAR(16) NOT NULL DEFAULT 'NOT_SUBMITTED',
    ADD COLUMN remote_ended BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE outbound_tasks
    ADD CONSTRAINT outbound_tasks_id_user_unique UNIQUE (id, user_id);
ALTER TABLE outbound_contacts
    ADD CONSTRAINT outbound_contacts_id_user_unique UNIQUE (id, user_id);
ALTER TABLE outbound_calls
    ADD CONSTRAINT outbound_calls_task_user_fk
        FOREIGN KEY (task_id, user_id) REFERENCES outbound_tasks(id, user_id),
    ADD CONSTRAINT outbound_calls_contact_user_fk
        FOREIGN KEY (contact_id, user_id) REFERENCES outbound_contacts(id, user_id);
