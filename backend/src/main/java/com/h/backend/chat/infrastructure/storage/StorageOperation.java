package com.h.backend.chat.infrastructure.storage;

import java.util.Locale;

/**
 * 资源存储操作类型（统一 Trace 设计 §10.7）：Micrometer tag 有界枚举，
 * 取值固定为 save/open/discard，运行时不得扩展动态标签。
 */
public enum StorageOperation {

    SAVE,

    OPEN,

    DISCARD;

    /** Prometheus label 值（小写）。 */
    String tagValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
