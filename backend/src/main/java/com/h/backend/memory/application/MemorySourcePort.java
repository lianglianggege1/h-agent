package com.h.backend.memory.application;

import java.util.List;
import java.util.Map;

/** 记忆来源读取端口：backend 由会话消息 + 旧版工作区文件实现，团队运行时可另行适配。 */
public interface MemorySourcePort {
    Map<String, Object> search(AgentMemoryContext ctx, String query);

    Map<String, Object> read(Long user, String ref, int offset);

    List<String> validated(AgentMemoryContext ctx, List<String> supplied);
}