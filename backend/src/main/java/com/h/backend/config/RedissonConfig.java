package com.h.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 配置类
 * 提供分布式锁、分布式集合等功能
 *
 * @author corp-map-app
 * @date 2026-01-06
 */
@Slf4j
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private String port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Value("${spring.data.redis.timeout:5000ms}")
    private String timeout;

    /**
     * 创建 Redisson 客户端
     *
     * @return RedissonClient
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        
        // 单机模式配置
        String address = "redis://" + host + ":" + port;
        config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                // 连接超时时间，单位：毫秒
                .setConnectTimeout(parseTimeout(timeout))
                // 命令等待超时时间，单位：毫秒
                .setTimeout(parseTimeout(timeout))
                // 连接池大小
                .setConnectionPoolSize(64)
                // 最小空闲连接数
                .setConnectionMinimumIdleSize(10)
                // 空闲连接超时时间，单位：毫秒
                .setIdleConnectionTimeout(10000)
                // 连接重试次数
                .setRetryAttempts(3)
                // 连接重试间隔时间，单位：毫秒
                .setRetryInterval(1500);

        // 如果有密码，设置密码
        if (StringUtils.hasText(password)) {
            config.useSingleServer().setPassword(password);
        }

        // 配置编码器
        config.setCodec(new org.redisson.codec.JsonJacksonCodec());
        
        // 配置线程池
        config.setThreads(16);
        config.setNettyThreads(32);

        log.info("Redisson 配置完成，连接地址：{}", address);
        return Redisson.create(config);
    }

    /**
     * 解析超时时间配置
     * 支持 "5000ms" 或 "5s" 格式
     *
     * @param timeout 超时时间配置
     * @return 毫秒数
     */
    private int parseTimeout(String timeout) {
        if (timeout == null || timeout.isEmpty()) {
            return 5000;
        }
        
        timeout = timeout.toLowerCase().trim();
        
        if (timeout.endsWith("ms")) {
            return Integer.parseInt(timeout.substring(0, timeout.length() - 2));
        } else if (timeout.endsWith("s")) {
            return Integer.parseInt(timeout.substring(0, timeout.length() - 1)) * 1000;
        } else {
            return Integer.parseInt(timeout);
        }
    }
}

