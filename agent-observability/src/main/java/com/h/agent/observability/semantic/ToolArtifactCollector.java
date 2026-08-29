package com.h.agent.observability.semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具执行线程内收集已提交业务资源的 ArtifactReference（设计 §9.5）。
 *
 * <p>业务工具在挂接事务提交后（如 {@code saveAndAttach} 返回后）记录引用；
 * 观测 ToolExecutor 在工具返回后 drain 并附加到该工具 Observation 的输出。
 * 引用只在产生它的那次工具执行线程上有效，drain 总是清空 ThreadLocal，
 * 线程池复用不会泄漏到下一次执行。</p>
 */
public final class ToolArtifactCollector {

    static final int MAX_REFERENCES = 32;

    private static final ThreadLocal<List<ArtifactReference>> HOLDER = new ThreadLocal<>();

    private ToolArtifactCollector() {
    }

    public static void record(ArtifactReference reference) {
        if (reference == null || reference.resourceId() == null || reference.resourceId().isBlank()) {
            return;
        }
        List<ArtifactReference> current = HOLDER.get();
        if (current == null) {
            current = new ArrayList<>();
            HOLDER.set(current);
        }
        if (current.size() < MAX_REFERENCES) {
            current.add(reference);
        }
    }

    public static List<ArtifactReference> drain() {
        List<ArtifactReference> current = HOLDER.get();
        HOLDER.remove();
        if (current == null || current.isEmpty()) {
            return List.of();
        }
        return List.copyOf(current);
    }
}
