package com.h.backend.chat.infrastructure.config;

import com.h.backend.chat.domain.subagentdefinition.SubagentCapabilityPolicy;
import com.h.backend.chat.domain.subagentdefinition.SubagentMarkdownCompiler;
import com.h.backend.chat.domain.subagentdefinition.SubagentQuotaPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Subagent Definition Catalog 领域组件装配。
 *
 * <p>Compiler 与 Policy 是无状态领域对象；policy 作为单例 bean 共享，
 * 其 policyRevision 是平台能力政策的单调修订号。</p>
 */
@Configuration
@EnableConfigurationProperties(SubagentCatalogProperties.class)
public class SubagentDefinitionCatalogConfig {

    @Bean
    public SubagentMarkdownCompiler subagentMarkdownCompiler() {
        return new SubagentMarkdownCompiler();
    }

    @Bean
    public SubagentCapabilityPolicy subagentCapabilityPolicy() {
        return new SubagentCapabilityPolicy();
    }

    @Bean
    public SubagentQuotaPolicy subagentQuotaPolicy() {
        return new SubagentQuotaPolicy();
    }
}
