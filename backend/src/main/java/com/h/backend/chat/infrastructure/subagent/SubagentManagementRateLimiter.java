package com.h.backend.chat.infrastructure.subagent;

import com.h.backend.chat.domain.subagentdefinition.SubagentRateLimitException;
import com.h.backend.chat.infrastructure.config.SubagentCatalogProperties;
import com.h.backend.shared.infrastructure.utils.RedissonUtil;
import org.redisson.api.RateIntervalUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Subagent 管理接口的用户级限流（设计 Phase 3）。
 *
 * <p>变更类操作（创建/保存/校验/发布/启停/删除/恢复）共享同一 Redis RRateLimiter，
 * 每用户每分钟 {@code managementRateLimitPerMinute} 次；0 表示关闭。
 * 读接口不限流。</p>
 */
@Component
public class SubagentManagementRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SubagentManagementRateLimiter.class);

    private static final String KEY_PREFIX = "subagent:mgmt:rate:";

    private final RedissonUtil redissonUtil;
    private final SubagentCatalogProperties properties;

    public SubagentManagementRateLimiter(
            RedissonUtil redissonUtil,
            SubagentCatalogProperties properties
    ) {
        this.redissonUtil = redissonUtil;
        this.properties = properties;
    }

    /** 变更类操作前调用；超出配额抛 {@link SubagentRateLimitException}（接口层映射 429）。 */
    public void acquire(long userId) {
        long perMinute = properties.getManagementRateLimitPerMinute();
        if (perMinute <= 0) {
            return;
        }
        String key = KEY_PREFIX + userId;
        redissonUtil.setRateLimit(key, perMinute, 1, RateIntervalUnit.MINUTES);
        if (!redissonUtil.tryAcquireToken(key)) {
            log.warn("[SubagentRateLimit] 管理接口限流触发 userId={} limit={}/min", userId, perMinute);
            throw new SubagentRateLimitException("Subagent 管理操作过于频繁，请稍后重试");
        }
    }
}
