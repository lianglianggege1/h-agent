package com.h.backend.chat.domain.subagentdefinition.model;

/** 定义的运行时物化方式。 */
public enum SubagentRuntimeKind {
    /** 代码库 Markdown 声明，经 Catalog runtime factory 物化。 */
    CATALOG_DECLARATION,
    /** SDK 自动提供的 general-purpose；factory 复用 SDK 原实现。 */
    SDK_GENERAL_PURPOSE
}
