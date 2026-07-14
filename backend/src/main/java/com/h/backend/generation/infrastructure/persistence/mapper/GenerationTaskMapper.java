package com.h.backend.generation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.generation.infrastructure.persistence.entity.GenerationTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface GenerationTaskMapper extends BaseMapper<GenerationTaskEntity> {
    @Select("""
            SELECT * FROM generation_tasks
            WHERE status IN ('IN_PROGRESS', 'RETRY_WAIT', 'MATERIALIZING')
              AND next_poll_at <= #{now}
            ORDER BY next_poll_at ASC
            LIMIT #{limit}
            """)
    List<GenerationTaskEntity> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
