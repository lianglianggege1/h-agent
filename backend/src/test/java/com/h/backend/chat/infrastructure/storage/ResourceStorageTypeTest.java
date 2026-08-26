package com.h.backend.chat.infrastructure.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceStorageTypeTest {

    @Test
    void objectStorageIsTheOnlyKnownProductionStorageType() {
        assertThat(ResourceStorageType.values()).hasSize(1);
        assertThat(ResourceStorageType.OBJECT_STORAGE.value()).isEqualTo("OBJECT_STORAGE");
    }

    @Test
    void databaseValueIsStableString() {
        // 计划不变量 4：数据库 storage_type 固定写 OBJECT_STORAGE，禁止散落裸字符串。
        assertThat(ResourceStorageType.OBJECT_STORAGE.value()).isNotBlank();
    }
}
