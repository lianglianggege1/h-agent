package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.AgentDefinitionAuditLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentDefinitionAuditLogMapper extends BaseMapper<AgentDefinitionAuditLogEntity> {

    @Insert("""
            INSERT INTO agent_definition_audit_logs
                (actor_user_id, definition_id, version, revision, operation, request_id, metadata_json, created_at)
            VALUES
                (#{actorUserId}, #{definitionId}, #{version}, #{revision}, #{operation},
                 #{requestId}, CAST(#{metadataJson} AS jsonb), NOW())
            """)
    int insertAudit(
            @Param("actorUserId") Long actorUserId,
            @Param("definitionId") long definitionId,
            @Param("version") Integer version,
            @Param("revision") Long revision,
            @Param("operation") String operation,
            @Param("requestId") String requestId,
            @Param("metadataJson") String metadataJson);
}
