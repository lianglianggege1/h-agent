DROP INDEX IF EXISTS outbound_one_active_call;
CREATE UNIQUE INDEX outbound_one_active_call ON outbound_calls ((true))
    WHERE stage IN ('PREPARING','DIALING','ACTIVE','ENDING','UNKNOWN');

DROP INDEX IF EXISTS outbound_one_running_task;
CREATE UNIQUE INDEX outbound_one_running_task ON outbound_tasks ((true))
    WHERE status = 'RUNNING';
