package com.h.backend.chat.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Subagent Definition Catalog 开关。
 *
 * <p>设计 16/Phase 5：滚动发布期间默认关闭，先完成数据库 migration、
 * 部署全部新节点后再开启；本地开发环境在 application.yml 显式开启。</p>
 */
@ConfigurationProperties(prefix = "chat.subagent-catalog")
public class SubagentCatalogProperties {

    /** 是否启用 Catalog：内置同步、管理接口与运行时委托。 */
    private boolean enabled = false;

    /** 管理接口变更类操作的用户级限流：每分钟次数；0 表示关闭。 */
    private long managementRateLimitPerMinute = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getManagementRateLimitPerMinute() {
        return managementRateLimitPerMinute;
    }

    public void setManagementRateLimitPerMinute(long managementRateLimitPerMinute) {
        this.managementRateLimitPerMinute = managementRateLimitPerMinute;
    }
}
