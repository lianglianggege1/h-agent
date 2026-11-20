package com.h.backend.capability.matrix;

import com.h.matrixteam.MatrixClient;

import java.util.Comparator;
import java.util.List;

/** 按执行者账号优先选择 Matrix 客户端，其余客户端兜底。 */
public final class MatrixClients {

    private MatrixClients() {
    }

    public static MatrixClient forActor(List<MatrixClient> clients, String actor) {
        if (clients == null || clients.isEmpty()) {
            throw new IllegalStateException("没有可用的 Matrix 客户端");
        }
        if (actor == null) {
            return clients.getFirst();
        }
        return clients.stream()
                .min(Comparator.comparing(client -> !actor.equals(client.userId())))
                .orElseThrow();
    }
}