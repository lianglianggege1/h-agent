package com.h.backend.memory.domain;

/** 长期记忆未启用（enabled=false）时访问管理能力。 */
public class MemoryModuleDisabledException extends RuntimeException {

    public MemoryModuleDisabledException() {
        super("长期记忆功能未启用");
    }
}
