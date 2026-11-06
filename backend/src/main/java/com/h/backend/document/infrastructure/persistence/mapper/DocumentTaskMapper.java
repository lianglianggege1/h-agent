package com.h.backend.document.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.document.infrastructure.persistence.entity.DocumentTaskEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 普通 CRUD 由 MyBatis-Plus 提供；幂等插入、行锁和 SKIP LOCKED 租约见同名 XML。 */
@Mapper
public interface DocumentTaskMapper extends BaseMapper<DocumentTaskEntity> {
    int insertIdempotent(@Param("row") DocumentTaskEntity row);

    DocumentTaskEntity lockOwned(
            @Param("user") Long user, @Param("session") String session, @Param("id") String id);

    List<DocumentTaskEntity> claim();

    /** 原子恢复服务不可用任务，并使旧工作线程检查点失效。 */
    int requeueUnavailable();

    Long activeAnchor(@Param("user") Long user, @Param("session") String session);

    String runStatus(@Param("runId") Long runId);
}
