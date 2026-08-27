package com.h.backend.chat.infrastructure.storage;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResourceStorageConfiguration 启动校验与 Bean 装配测试（计划 §8.1 / §10 任务 2）。
 *
 * <p>启动校验是纯配置校验，不做任何网络探测：合法配置即使 endpoint 不可路由，
 * 上下文也能启动成功（这同时证明启动阶段无网络调用）；非法配置 fail fast，
 * 异常消息只含属性名与格式要求，绝不包含属性值（尤其 secret）。
 */
class ResourceStorageConfigurationTest {

    /** 不可路由地址：上下文启动成功即证明启动阶段无网络探测。 */
    private static final String UNREACHABLE_ENDPOINT = "http://10.255.255.1:9000";
    private static final String SECRET = "super-secret-value";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ResourceStorageConfiguration.class)
            .withPropertyValues(baseProperties());

    @Test
    void validConfigRegistersMinioStorageAsSoleResourceStorageBean() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MinioClient.class);
            assertThat(context).hasSingleBean(MinioResourceStorage.class);

            assertThat(context).hasSingleBean(ResourceStorage.class);
            assertThat(context.getBean(ResourceStorage.class))
                    .isInstanceOf(MinioResourceStorage.class);
        });
    }

    // ------------------------------------------------------------------
    // 必填属性缺失 → fail fast（消息只含属性名，不含任何属性值）
    // ------------------------------------------------------------------

    @Test
    void missingEndpointFailsFast() {
        runnerMissing("resource-storage.minio.endpoint").run(context ->
                assertStartupFailureMentions(context, "resource-storage.minio.endpoint"));
    }

    @Test
    void invalidEndpointUrlFailsFast() {
        contextRunner
                .withPropertyValues("resource-storage.minio.endpoint=not-a-url")
                .run(context -> {
                    assertStartupFailureMentions(context, "resource-storage.minio.endpoint");
                    assertThat(failureMessages(context.getStartupFailure()))
                            .doesNotContain("not-a-url");
                });

        contextRunner
                .withPropertyValues("resource-storage.minio.endpoint=ftp://example.com")
                .run(context ->
                        assertStartupFailureMentions(context, "resource-storage.minio.endpoint"));
    }

    @Test
    void missingAccessKeyFailsFast() {
        runnerMissing("resource-storage.minio.access-key").run(context ->
                assertStartupFailureMentions(context, "resource-storage.minio.access-key"));
    }

    @Test
    void missingSecretKeyFailsFastWithoutLeakingSecret() {
        runnerMissing("resource-storage.minio.secret-key").run(context -> {
            assertStartupFailureMentions(context, "resource-storage.minio.secret-key");
            assertThat(failureMessages(context.getStartupFailure())).doesNotContain(SECRET);
        });
    }

    @Test
    void missingBucketFailsFast() {
        runnerMissing("resource-storage.minio.bucket").run(context ->
                assertStartupFailureMentions(context, "resource-storage.minio.bucket"));
    }

    // ------------------------------------------------------------------
    // 非法数值/格式 → fail fast
    // ------------------------------------------------------------------

    @Test
    void nonPositiveTimeoutFailsFast() {
        contextRunner
                .withPropertyValues("resource-storage.minio.connect-timeout=0s")
                .run(context ->
                        assertStartupFailureMentions(context, "resource-storage.minio.connect-timeout"));

        contextRunner
                .withPropertyValues("resource-storage.minio.read-timeout=-5s")
                .run(context ->
                        assertStartupFailureMentions(context, "resource-storage.minio.read-timeout"));
    }

    @Test
    void partSizeOutsideSdkSupportedRangeFailsFast() {
        contextRunner
                .withPropertyValues("resource-storage.minio.part-size-bytes=1048576")
                .run(context ->
                        assertStartupFailureMentions(context, "resource-storage.minio.part-size-bytes"));
    }

    @Test
    void objectPrefixWithLeadingSlashFailsFast() {
        contextRunner
                .withPropertyValues("resource-storage.minio.object-prefix=/leading/slash/")
                .run(context -> {
                    assertStartupFailureMentions(context, "resource-storage.minio.object-prefix");
                    assertThat(failureMessages(context.getStartupFailure()))
                            .doesNotContain("/leading/slash/");
                });
    }

    @Test
    void nonPositiveAbsoluteMaxBytesFailsFast() {
        contextRunner
                .withPropertyValues("resource-storage.absolute-max-bytes=0")
                .run(context ->
                        assertStartupFailureMentions(context, "resource-storage.absolute-max-bytes"));
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private static String[] baseProperties() {
        return new String[] {
                "resource-storage.minio.endpoint=" + UNREACHABLE_ENDPOINT,
                "resource-storage.minio.access-key=test-access-key",
                "resource-storage.minio.secret-key=" + SECRET,
                "resource-storage.minio.bucket=test-bucket"
        };
    }

    /** 构造缺失某个属性的 runner（其余属性保持合法）。 */
    private ApplicationContextRunner runnerMissing(String excludedPropertyPrefix) {
        List<String> properties = new ArrayList<>(List.of(baseProperties()));
        properties.removeIf(property -> property.startsWith(excludedPropertyPrefix + "="));
        return new ApplicationContextRunner()
                .withUserConfiguration(ResourceStorageConfiguration.class)
                .withPropertyValues(properties.toArray(String[]::new));
    }

    private void assertStartupFailureMentions(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
            String propertyName) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
                .as("启动失败应以 IllegalStateException fail fast")
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(failureMessages(context.getStartupFailure()))
                .contains(propertyName)
                .doesNotContain(SECRET);
    }

    /** 汇总异常链上所有消息，供"包含属性名/不含属性值"断言使用。 */
    private static String failureMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Set<Throwable> seen = new HashSet<>();
        for (Throwable current = failure; current != null && seen.add(current);
                current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
