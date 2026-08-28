package com.h.backend.memory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.memory.infrastructure.persistence.entity.LongTermMemoryRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LongTermMemoryRecordMapper extends BaseMapper<LongTermMemoryRecordEntity> {

    @Select("""
            SELECT id, remote_memory_id, owner_user_id, scope_kind, logical_agent_id, memory_run_id,
                   version, operation_state, source, source_execution_id, remote_hash,
                   remote_updated_at, created_at, updated_at, deleted_at
            FROM long_term_memory_records
            WHERE owner_user_id = #{userId}
              AND deleted_at IS NULL
              AND (#{scopeKind} IS NULL OR scope_kind = #{scopeKind})
              AND (#{logicalAgentId} IS NULL OR logical_agent_id = #{logicalAgentId})
              AND (#{cursorId} IS NULL OR id < #{cursorId})
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<LongTermMemoryRecordEntity> selectOwnedPage(
            @Param("userId") Long userId,
            @Param("scopeKind") String scopeKind,
            @Param("logicalAgentId") String logicalAgentId,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit);

    @Select("""
            SELECT id, remote_memory_id, owner_user_id, scope_kind, logical_agent_id, memory_run_id,
                   version, operation_state, source, source_execution_id, remote_hash,
                   remote_updated_at, created_at, updated_at, deleted_at
            FROM long_term_memory_records
            WHERE id = #{localId} AND owner_user_id = #{userId} AND deleted_at IS NULL
            """)
    LongTermMemoryRecordEntity selectOwnedById(@Param("localId") Long localId, @Param("userId") Long userId);

    @Select("""
            SELECT id, remote_memory_id, owner_user_id, scope_kind, logical_agent_id, memory_run_id,
                   version, operation_state, source, source_execution_id, remote_hash,
                   remote_updated_at, created_at, updated_at, deleted_at
            FROM long_term_memory_records
            WHERE remote_memory_id = #{remoteMemoryId} AND owner_user_id = #{userId}
            """)
    LongTermMemoryRecordEntity selectByRemoteMemoryId(@Param("remoteMemoryId") String remoteMemoryId,
                                                      @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM long_term_memory_records
            WHERE owner_user_id = #{userId} AND deleted_at IS NULL
            """)
    long countOwned(@Param("userId") Long userId);

    @Update("""
            UPDATE long_term_memory_records
            SET version = version + 1,
                remote_hash = #{remoteHash},
                remote_updated_at = #{remoteTimestamp},
                updated_at = #{remoteTimestamp}
            WHERE id = #{localId}
              AND owner_user_id = #{userId}
              AND version = #{expectedVersion}
              AND deleted_at IS NULL
            """)
    int casUpdateContent(@Param("localId") Long localId,
                         @Param("userId") Long userId,
                         @Param("expectedVersion") int expectedVersion,
                         @Param("remoteHash") String remoteHash,
                         @Param("remoteTimestamp") LocalDateTime remoteTimestamp);

    @Update("""
            UPDATE long_term_memory_records
            SET version = version + 1,
                operation_state = 'DELETED',
                deleted_at = #{deletedAt},
                updated_at = #{deletedAt}
            WHERE id = #{localId}
              AND owner_user_id = #{userId}
              AND version = #{expectedVersion}
              AND deleted_at IS NULL
            """)
    int casMarkDeleted(@Param("localId") Long localId,
                       @Param("userId") Long userId,
                       @Param("expectedVersion") int expectedVersion,
                       @Param("deletedAt") LocalDateTime deletedAt);
}
