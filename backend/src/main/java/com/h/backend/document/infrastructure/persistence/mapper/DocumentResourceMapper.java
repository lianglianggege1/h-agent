package com.h.backend.document.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/** 文档附件归属查询，跨消息及资源表的 SQL 集中在 XML。 */
@Mapper
public interface DocumentResourceMapper {
    int countOwnedReference(
            @Param("user") Long user,
            @Param("session") String session,
            @Param("storageKey") String storageKey);

    List<Map<String, Object>> inputs(@Param("user") Long user, @Param("session") String session);
}
