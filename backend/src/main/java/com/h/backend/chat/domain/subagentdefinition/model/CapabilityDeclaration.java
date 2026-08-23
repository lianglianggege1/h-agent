package com.h.backend.chat.domain.subagentdefinition.model;

import java.util.List;

/**
 * tools / skills 字段的声明语义。
 *
 * <p>平台不沿用 AgentScope "空列表表示继承全部" 的语义：</p>
 * <ul>
 *   <li>{@code OMITTED}：字段省略，使用平台安全默认集合（tools 为四个只读文件工具，skills 为空）；</li>
 *   <li>{@code EMPTY}：显式空数组，明确为无能力；</li>
 *   <li>{@code EXPLICIT}：显式列表，运行时取与平台允许集合的交集。</li>
 * </ul>
 */
public record CapabilityDeclaration(Kind kind, List<String> names) {

    public enum Kind {
        OMITTED,
        EMPTY,
        EXPLICIT
    }

    public static final CapabilityDeclaration OMITTED = new CapabilityDeclaration(Kind.OMITTED, List.of());

    public static CapabilityDeclaration empty() {
        return new CapabilityDeclaration(Kind.EMPTY, List.of());
    }

    public static CapabilityDeclaration explicit(List<String> names) {
        return new CapabilityDeclaration(Kind.EXPLICIT, List.copyOf(names));
    }
}
