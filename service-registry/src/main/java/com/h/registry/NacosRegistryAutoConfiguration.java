package com.h.registry;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.CommonParams;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Properties;

@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "h-agent.registry", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(NacosRegistryProperties.class)
public class NacosRegistryAutoConfiguration {

    @Bean(destroyMethod = "shutDown")
    @ConditionalOnMissingBean
    NamingService nacosNamingService(NacosRegistryProperties properties) throws NacosException {
        return NacosFactory.createNamingService(clientProperties(properties));
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "h-agent.registry", name = "a2a-enabled", havingValue = "true")
    AiService nacosAiService(NacosRegistryProperties properties) throws NacosException {
        return AiFactory.createAiService(clientProperties(properties));
    }

    private Properties clientProperties(NacosRegistryProperties properties) {
        Properties client = new Properties();
        client.setProperty(PropertyKeyConst.SERVER_ADDR, properties.serverAddr());
        client.setProperty(PropertyKeyConst.NAMESPACE, properties.namespace());
        client.setProperty(PropertyKeyConst.USERNAME, properties.username());
        client.setProperty(PropertyKeyConst.PASSWORD, properties.password());
        client.setProperty(CommonParams.NAMING_REQUEST_TIMEOUT, Long.toString(properties.timeoutMs()));
        client.setProperty(AiConstants.AI_REQUEST_TIMEOUT, Long.toString(properties.timeoutMs()));
        return client;
    }

    @Bean
    NacosServiceRegistration nacosServiceRegistration(NamingService namingService,
            NacosRegistryProperties properties, Environment environment, ApplicationContext context) {
        return new NacosServiceRegistration(namingService, properties,
                environment.getRequiredProperty("spring.application.name"), context);
    }

    @Bean
    @ConditionalOnMissingBean
    public NacosServiceDiscovery nacosServiceDiscovery(NamingService namingService, NacosRegistryProperties properties) {
        return new NacosServiceDiscovery(namingService, properties.group());
    }
}
