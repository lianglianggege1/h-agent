package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.AgentDefinitionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentDefinitionMapper extends BaseMapper<AgentDefinitionEntity> {

    /**
     * 事务级 advisory lock；内置同步与用户 quota 用同一 key 串行化并发登记。
     * 用 @Update 执行：MyBatis 不做结果映射（@Select + void 会触发构造器解析错误）。
     */
    @Update("SELECT pg_advisory_xact_lock(hashtext(#{key}))")
    void acquireAdvisoryLock(@Param("key") String key);

    /** 仅前向更新当前发布版本；旧 release 节点重启不能把指针改回旧版本。 */
    @Update("""
            UPDATE agent_definitions
            SET current_published_version = #{version}, updated_at = NOW()
            WHERE id = #{id}
              AND (current_published_version IS NULL OR current_published_version < #{version})
            """)
    int updateCurrentVersionForward(@Param("id") long id, @Param("version") int version);

    /** 内置定义恒为启用；同步时幂等纠正。 */
    @Update("""
            UPDATE agent_definitions
            SET enabled = TRUE, updated_at = NOW()
            WHERE id = #{id} AND enabled = FALSE
            """)
    int enableIfDisabled(@Param("id") long id);

    @Select("""
            SELECT id, source, owner_user_id, agent_id, current_published_version, enabled,
                   deleted_at, created_at, updated_at
            FROM agent_definitions
            WHERE source = 'BUILTIN'
            ORDER BY agent_id ASC
            """)
    List<AgentDefinitionEntity> selectBuiltins();

    @Select("""
            SELECT id, source, owner_user_id, agent_id, current_published_version, enabled,
                   deleted_at, created_at, updated_at
            FROM agent_definitions
            WHERE source = 'BUILTIN' AND agent_id = #{agentId}
            LIMIT 1
            """)
    AgentDefinitionEntity selectBuiltinByAgentId(@Param("agentId") String agentId);

    @Select("""
            SELECT id, source, owner_user_id, agent_id, current_published_version, enabled,
                   deleted_at, created_at, updated_at
            FROM agent_definitions
            WHERE source = 'USER' AND owner_user_id = #{userId} AND agent_id = #{agentId}
            LIMIT 1
            """)
    AgentDefinitionEntity selectUserByAgentId(@Param("userId") long userId, @Param("agentId") String agentId);

    @Select("""
            SELECT id, source, owner_user_id, agent_id, current_published_version, enabled,
                   deleted_at, created_at, updated_at
            FROM agent_definitions
            WHERE source = 'USER' AND owner_user_id = #{userId}
            ORDER BY updated_at DESC
            """)
    List<AgentDefinitionEntity> selectOwnedByUser(@Param("userId") long userId);

    /** 参与新父 turn 的用户定义：未删除、已启用且存在当前发布版本。 */
    @Select("""
            SELECT id, source, owner_user_id, agent_id, current_published_version, enabled,
                   deleted_at, created_at, updated_at
            FROM agent_definitions
            WHERE source = 'USER' AND owner_user_id = #{userId}
              AND enabled = TRUE AND deleted_at IS NULL AND current_published_version IS NOT NULL
            ORDER BY agent_id ASC
            """)
    List<AgentDefinitionEntity> selectEnabledOwnedByUser(@Param("userId") long userId);

    /** 行级锁读取；发布/启停事务使用，避免并发状态翻转。 */
    @Select("""
            SELECT id, source, owner_user_id, agent_id, current_published_version, enabled,
                   deleted_at, created_at, updated_at
            FROM agent_definitions
            WHERE id = #{id}
            FOR UPDATE
            """)
    AgentDefinitionEntity selectByIdForUpdate(@Param("id") long id);

    /** 启停状态更新；quota 检查在事务内完成后调用。 */
    @Update("""
            UPDATE agent_definitions
            SET enabled = #{enabled}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    /** 软删除；已删除行不重复更新。 */
    @Update("""
            UPDATE agent_definitions
            SET deleted_at = NOW(), updated_at = NOW()
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int markDeleted(@Param("id") long id);

    /** 恢复软删除；不改变 enabled。 */
    @Update("""
            UPDATE agent_definitions
            SET deleted_at = NULL, updated_at = NOW()
            WHERE id = #{id} AND deleted_at IS NOT NULL
            """)
    int markRestored(@Param("id") long id);

    /** 未删除用户定义数；quota 统计使用。 */
    @Select("""
            SELECT COUNT(*)
            FROM agent_definitions
            WHERE source = 'USER' AND owner_user_id = #{userId} AND deleted_at IS NULL
            """)
    long countActiveOwnedByUser(@Param("userId") long userId);

    /** 已启用用户定义数；quota 统计使用。 */
    @Select("""
            SELECT COUNT(*)
            FROM agent_definitions
            WHERE source = 'USER' AND owner_user_id = #{userId}
              AND enabled = TRUE AND deleted_at IS NULL
            """)
    long countEnabledOwnedByUser(@Param("userId") long userId);
}
