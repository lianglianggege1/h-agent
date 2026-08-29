package com.h.backend.memory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.memory.infrastructure.persistence.entity.MemoryCaptureOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MemoryCaptureOutboxMapper extends BaseMapper<MemoryCaptureOutboxEntity> {

    @Select("""
            SELECT id, operation_key, owner_user_id, logical_agent_id, memory_run_id, scope_kind,
                   source_execution_id, prompt_id, session_id, user_message_id, assistant_message_id,
                   state, attempts, next_attempt_at, last_error, created_at, updated_at
            FROM long_term_memory_capture_outbox
            WHERE operation_key = #{operationKey}
            """)
    MemoryCaptureOutboxEntity selectByOperationKey(@Param("operationKey") String operationKey);

    @Select("""
            SELECT id, operation_key, owner_user_id, logical_agent_id, memory_run_id, scope_kind,
                   source_execution_id, prompt_id, session_id, user_message_id, assistant_message_id,
                   state, attempts, next_attempt_at, last_error, created_at, updated_at
            FROM long_term_memory_capture_outbox
            WHERE (state IN ('PENDING', 'RECONCILING') AND next_attempt_at <= NOW())
               OR (state = 'PROCESSING' AND updated_at < NOW() - INTERVAL '5 minutes')
            ORDER BY next_attempt_at ASC, id ASC
            LIMIT #{batchSize}
            FOR UPDATE SKIP LOCKED
            """)
    List<MemoryCaptureOutboxEntity> claimBatch(@Param("batchSize") int batchSize);

    @Select("""
            SELECT COUNT(*)
            FROM long_term_memory_capture_outbox
            WHERE state = #{state}
            """)
    long countByState(@Param("state") String state);

    @Select("""
            SELECT MIN(next_attempt_at)
            FROM long_term_memory_capture_outbox
            WHERE state IN ('PENDING', 'RECONCILING')
            """)
    java.time.LocalDateTime oldestPendingAttemptAt();
}
