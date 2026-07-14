package com.h.backend.generation.domain.model;

import java.util.Arrays;

public enum GenerationStatus {
    PENDING_SUBMISSION("PENDING_SUBMISSION", "待提交"),
    IN_PROGRESS("IN_PROGRESS", "生成中"),
    MATERIALIZING("MATERIALIZING", "文件处理中"),
    RETRY_WAIT("RETRY_WAIT", "等待重试"),
    SUCCEEDED("SUCCEEDED", "生成完成"),
    FAILED("FAILED", "生成失败");

    private final String name;
    private final String cnName;

    GenerationStatus(String name, String cnName) {
        this.name = name;
        this.cnName = cnName;
    }

    public String getName() {
        return name;
    }

    public String getCnName() {
        return cnName;
    }

    public static GenerationStatus fromName(String name) {
        return Arrays.stream(values())
                .filter(status -> status.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown generation status: " + name));
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
