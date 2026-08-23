package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionVersionSummary;

import java.time.Instant;

/** 版本列表条目（含当前标记）。 */
public record SubagentVersionSummaryDto(
        int version,
        String contentHash,
        Instant publishedAt,
        boolean current) {

    public static SubagentVersionSummaryDto from(SubagentDefinitionVersionSummary summary) {
        return new SubagentVersionSummaryDto(
                summary.version(),
                summary.contentHash(),
                summary.publishedAt(),
                summary.current()
        );
    }
}
