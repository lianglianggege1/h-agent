package com.h.backend.automation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationDeliveryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AutomationDeliveryMapper extends BaseMapper<AutomationDeliveryEntity> {

    @Select("""
            WITH due AS (
                SELECT id FROM automation_deliveries
                WHERE status = 'PENDING'
                  AND (next_attempt_at IS NULL OR next_attempt_at <= #{now})
                  AND (lease_until IS NULL OR lease_until < #{now})
                ORDER BY next_attempt_at ASC NULLS FIRST, created_at ASC
                FOR UPDATE SKIP LOCKED
                LIMIT #{limit}
            )
            UPDATE automation_deliveries d
            SET lease_owner = #{leaseOwner}, lease_until = #{leaseUntil},
                attempt_count = attempt_count + 1, updated_at = #{now}
            FROM due
            WHERE d.id = due.id
            RETURNING d.*
            """)
    List<AutomationDeliveryEntity> claimDue(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Update("""
            UPDATE automation_deliveries
            SET status = 'DELIVERED', delivered_at = #{now},
                lease_owner = NULL, lease_until = NULL, last_error = NULL, updated_at = #{now}
            WHERE id = #{deliveryId} AND lease_owner = #{leaseOwner}
            """)
    int markDelivered(
            @Param("deliveryId") String deliveryId,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE automation_deliveries
            SET status = 'PENDING', next_attempt_at = #{nextAttemptAt}, last_error = #{errorMessage},
                lease_owner = NULL, lease_until = NULL, updated_at = #{now}
            WHERE id = #{deliveryId} AND lease_owner = #{leaseOwner}
            """)
    int markRetrying(
            @Param("deliveryId") String deliveryId,
            @Param("leaseOwner") String leaseOwner,
            @Param("errorMessage") String errorMessage,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE automation_deliveries
            SET status = 'DEAD_LETTER', last_error = #{errorMessage},
                lease_owner = NULL, lease_until = NULL, updated_at = #{now}
            WHERE id = #{deliveryId} AND lease_owner = #{leaseOwner}
            """)
    int markDeadLetter(
            @Param("deliveryId") String deliveryId,
            @Param("leaseOwner") String leaseOwner,
            @Param("errorMessage") String errorMessage,
            @Param("now") LocalDateTime now
    );

    @Select("""
            SELECT * FROM automation_deliveries
            WHERE user_id = #{userId} AND run_id = #{runId}
            ORDER BY created_at ASC
            """)
    List<AutomationDeliveryEntity> selectByRun(
            @Param("userId") Long userId, @Param("runId") String runId);

    @Select("""
            SELECT * FROM automation_deliveries
            WHERE user_id = #{userId} AND id = #{deliveryId}
            """)
    AutomationDeliveryEntity selectOwned(
            @Param("userId") Long userId, @Param("deliveryId") String deliveryId);
}
