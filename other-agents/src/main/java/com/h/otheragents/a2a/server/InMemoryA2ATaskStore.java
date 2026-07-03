package com.h.otheragents.a2a.server;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryA2ATaskStore implements A2ATaskStore {

    private final ConcurrentMap<String, A2ATaskRecord> tasks = new ConcurrentHashMap<>();

    @Override
    public A2ATaskRecord save(A2ATaskRecord record) {
        tasks.put(record.taskId(), record);
        return record;
    }

    @Override
    public Optional<A2ATaskRecord> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }
}
