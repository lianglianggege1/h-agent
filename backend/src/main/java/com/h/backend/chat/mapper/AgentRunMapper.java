package com.h.backend.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.entity.AgentRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    @Select("""
            SELECT id, session_id, user_id, prompt_id, user_message_id, assistant_message_id,
                   status, model_name, langfuse_trace_id, tool_count, tool_names_json,
                   error_message, started_at, completed_at, created_at, updated_at
            FROM agent_runs
            WHERE session_id = #{sessionId}
            ORDER BY started_at DESC, id DESC
            """)
    List<AgentRunEntity> selectBySessionId(@Param("sessionId") String sessionId);
}
