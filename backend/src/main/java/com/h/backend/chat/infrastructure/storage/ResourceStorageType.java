package com.h.backend.chat.infrastructure.storage;

/**
 * 资源存储类型统一常量（计划不变量 4 / §4.4）。
 *
 * <p>数据库 {@code storage_type} 固定写 {@code OBJECT_STORAGE}；
 * Java 侧禁止散落裸字符串。读到其他类型按内部数据错误 fail closed。
 * （历史本地文件存储类型已在任务 5 随其实现删除，不再存在本地存储类型。）
 */
public enum ResourceStorageType {

    OBJECT_STORAGE("OBJECT_STORAGE");

    private final String value;

    ResourceStorageType(String value) {
        this.value = value;
    }

    /** 与数据库 {@code storage_type} 列一致的稳定值。 */
    public String value() {
        return value;
    }
}
