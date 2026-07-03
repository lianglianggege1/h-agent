package com.h.otheragents.a2a.server;

import java.util.Optional;

public interface A2ATaskStore {

    A2ATaskRecord save(A2ATaskRecord record);

    Optional<A2ATaskRecord> find(String taskId);
}
