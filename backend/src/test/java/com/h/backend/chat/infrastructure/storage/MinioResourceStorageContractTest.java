package com.h.backend.chat.infrastructure.storage;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 MinIO contract 测试（新计划 §10 任务 6 / §11.4）：面向<b>真实开发 MinIO</b>
 * 验证 {@link MinioResourceStorage} 的端到端行为——multipart 流式上传、stat/Range
 * 一致性、补偿删除、匿名拒绝与账号权限矩阵。本套件是本期最终验收的强制门槛，
 * 但不进入普通 CI 的必绿集：<b>缺少凭证时全类 SKIP</b>，普通 {@code mvn test}
 * 不受影响（计划 §11.4）。
 *
 * <p><b>环境注入</b>（系统属性优先、环境变量兜底，两套名字都支持）：
 * <table border="1">
 *   <tr><th>用途</th><th>解析顺序</th></tr>
 *   <tr><td>endpoint</td><td>{@code TEST_MINIO_ENDPOINT} → {@code MINIO_ENDPOINT}</td></tr>
 *   <tr><td>access key</td><td>{@code TEST_MINIO_ACCESS_KEY} → {@code MINIO_ACCESS_KEY} → {@code MINIO_ROOT_USER}</td></tr>
 *   <tr><td>secret key</td><td>{@code TEST_MINIO_SECRET_KEY} → {@code MINIO_SECRET_KEY} → {@code MINIO_ROOT_PASSWORD}</td></tr>
 *   <tr><td>bucket</td><td>{@code TEST_MINIO_BUCKET} → {@code MINIO_RESOURCES_BUCKET} → {@code MINIO_DEFAULT_BUCKET}</td></tr>
 *   <tr><td>region</td><td>{@code TEST_MINIO_REGION} → {@code MINIO_REGION}（默认 us-east-1）</td></tr>
 * </table>
 * 任一必填缺失 → 本类所有用例 {@code assumeTrue} SKIP（surefire 报 skipped）。
 *
 * <p><b>公共端点防呆</b>：endpoint 指向已知公共 play 环境（play.min.io 等）时
 * 直接 fail（防止向公共端点写入真实测试对象），绝不静默 SKIP。
 *
 * <p><b>账号模式</b>（系统属性 {@code contract.account}，默认 {@code restricted}）：
 * <ul>
 *   <li>{@code restricted}（生产验收语义）：跨前缀写、管理操作（listBuckets/makeBucket）
 *       断言<b>被拒绝</b>。这两条用例失败即说明账号权限过宽——这本身就是验收发现，
 *       需要收紧账号 Policy 而不是改测试。</li>
 *   <li>{@code admin}（开发验证豁免）：上述两条用例改为"验证并告警"——断言操作
 *       <b>成功</b>（管理员确实能做），并输出 WARN 提示生产验收必须使用前缀受限
 *       专用账号；本模式用于无专用账号时的开发联调，不是验收终态。</li>
 * </ul>
 * 匿名访问拒绝用例在两种模式下都断言被拒绝（私有 Bucket 的硬不变量，计划不变量 1）。
 *
 * <p><b>测试对象纪律</b>：所有对象写入 {@code resources/contract-tests/{runId}/}
 * 前缀（runId=UUID）；跨前缀探针固定写 {@code other-prefix/contract-tests/{runId}/}。
 * {@code @AfterAll} 删除该 runId 下全部对象并验证前缀清零；listObjects 权限被
 * 策略拒绝时（受限账号预期行为）退化按测试期间记录的 key 列表逐一 discard 并
 * 断言全部成功。
 *
 * <p><b>日志与断言纪律</b>（计划不变量 17）：任何断言消息、日志输出都不得包含
 * secret、完整签名 URL、SDK 异常全文；定位信息最多使用 resourceId/key 尾段。
 * 不启动 Spring 上下文——直接构造 MinioClient + MinioResourceStorage。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MinioResourceStorageContractTest {

    private static final Logger log = LoggerFactory.getLogger(MinioResourceStorageContractTest.class);

    /** 所有测试对象的父前缀（计划 §10 任务 6）。 */
    private static final String CONTRACT_TEST_PREFIX_ROOT = "resources/contract-tests/";
    /** 跨前缀探针前缀：resources/ 之外（bucket 根下），用于验证账号前缀受限。 */
    private static final String FOREIGN_PREFIX_ROOT = "other-prefix/contract-tests/";

    private static final long MIB = 1024L * 1024L;

    // ------------------------------------------------------------------
    // 环境解析结果（static：BeforeAll 解析一次，AfterAll 复用）
    // ------------------------------------------------------------------

    private static boolean configured;
    private static String endpoint;
    private static String accessKey;
    private static String secretKey;
    private static String bucket;
    private static String region = "us-east-1";
    private static String runId;
    private static String testObjectPrefix;

    private static boolean adminMode;
    private static MinioClient appClient;
    private static MinioResourceStorage storage;
    private static ResourceStorageProperties properties;
    private static ResourceStorageMeters meters;

    /** 测试期间写入的全部 object key（含跨前缀探针），AfterAll 逐一幂等删除。 */
    private static final List<String> createdObjectKeys =
            Collections.synchronizedList(new ArrayList<>());
    /** admin 模式 makeBucket 创建的临时 bucket，AfterAll 移除。 */
    private static final List<String> createdBucketNames =
            Collections.synchronizedList(new ArrayList<>());

    /** 用例 1 上传的 multipart 大对象（用例 2/3 复用，避免重复上传 24 MiB）。 */
    private static StoredResource multipartObject;
    private static DeterministicContent multipartContent;

    @BeforeAll
    static void resolveEnvironment() {
        endpoint = resolve("TEST_MINIO_ENDPOINT", "MINIO_ENDPOINT");
        accessKey = resolve("TEST_MINIO_ACCESS_KEY", "MINIO_ACCESS_KEY", "MINIO_ROOT_USER");
        secretKey = resolve("TEST_MINIO_SECRET_KEY", "MINIO_SECRET_KEY", "MINIO_ROOT_PASSWORD");
        bucket = resolve("TEST_MINIO_BUCKET", "MINIO_RESOURCES_BUCKET", "MINIO_DEFAULT_BUCKET");
        region = resolveDefault("us-east-1", "TEST_MINIO_REGION", "MINIO_REGION");

        // 公共端点防呆：宁可 fail 也绝不向公共环境写测试对象（任务书 A 部分要求）。
        if (endpoint != null && isKnownPublicEndpoint(endpoint)) {
            fail("TEST_MINIO_ENDPOINT 指向已知公共 play/AWS 环境，拒绝向公共端点写入测试对象；"
                    + "请改用私有开发 MinIO 的 S3 API 地址");
        }

        configured = notBlank(endpoint) && notBlank(accessKey)
                && notBlank(secretKey) && notBlank(bucket);
        if (!configured) {
            // 不抛出、不打印任何值；每个用例的 assumption 输出统一原因。
            return;
        }

        runId = UUID.randomUUID().toString();
        testObjectPrefix = CONTRACT_TEST_PREFIX_ROOT + runId + "/";
        adminMode = "admin".equalsIgnoreCase(
                System.getProperty("contract.account", "restricted").strip());

        properties = new ResourceStorageProperties();
        properties.getMinio().setEndpoint(endpoint);
        properties.getMinio().setAccessKey(accessKey);
        properties.getMinio().setSecretKey(secretKey);
        properties.getMinio().setBucket(bucket);
        properties.getMinio().setRegion(region);
        // contract 专用前缀（强制 resources/contract-tests/ 或在其下）。
        properties.getMinio().setObjectPrefix(testObjectPrefix);
        properties.getMinio().setConnectTimeout(Duration.ofSeconds(10));
        properties.getMinio().setReadTimeout(Duration.ofSeconds(300));

        appClient = buildClient(endpoint, accessKey, secretKey, region);
        meters = new ResourceStorageMeters(new io.micrometer.core.instrument.composite.CompositeMeterRegistry());
        storage = new MinioResourceStorage(appClient, properties, meters);
    }

    // ------------------------------------------------------------------
    // 用例 1：20-32 MiB 生成流 multipart 上传 + 完整读取逐字节一致
    // ------------------------------------------------------------------

    /**
     * 24 MiB 确定性生成流（种子化 1 MiB 块循环，不落盘），以未知大小
     * （declaredSize=null）流式 save——MinIO SDK 按配置 partSize(10 MiB)
     * 走 multipart（约 3 个分片）。验证 StoredResource.fileSize、key 前缀、
     * open 完整读取的 SHA-256 与生成流一致（全量逐字节等价）。
     */
    @Test
    @Order(1)
    void multipartUploadRoundTripPreservesContent() throws Exception {
        assumeContractEnvironment();

        long size = 24L * MIB;
        multipartContent = new DeterministicContent(size);
        StoredResource stored = storage.save(ResourceSaveCommand.fromStream(
                "VIDEO", multipartContent.open(), null, "video/mp4", "mp4", 0));

        recordKey(stored.storageKey());
        assertThat(stored.fileSize())
                .as("StoredResource.fileSize 应等于实际上传字节数")
                .isEqualTo(size);
        assertThat(stored.storageType()).isEqualTo("OBJECT_STORAGE");
        assertThat(stored.storageKey())
                .as("object key 必须落在 contract 测试前缀内")
                .startsWith(testObjectPrefix);
        multipartObject = stored;

        byte[] expectedDigest = sha256(multipartContent.open());
        byte[] storedDigest;
        try (ResourceContent content = storage.open(stored.storageKey(), ResourceRange.fullRead())) {
            assertThat(content.totalSize()).isEqualTo(size);
            assertThat(content.responseLength()).isEqualTo(size);
            assertThat(content.partial()).isFalse();
            storedDigest = sha256(content.inputStream());
        }
        assertThat(storedDigest)
                .as("完整读取内容必须与生成流逐字节一致（SHA-256 全量比对）")
                .isEqualTo(expectedDigest);

        // multipart 证据：stat 的 etag 存在；含 '-' 的 etag 是 multipart 上传特征。
        StatObjectResponse stat = appClient.statObject(StatObjectArgs.builder()
                .bucket(bucket).object(stored.storageKey()).build());
        assertThat(stat.size()).isEqualTo(size);
        log.info("contract multipart 上传完成：size={} etagMultipart={}",
                size, stat.etag() != null && stat.etag().contains("-"));
    }

    // ------------------------------------------------------------------
    // 用例 2：stat 一致 + Range（中部区间 / suffix / 开放结尾）内容一致
    // ------------------------------------------------------------------

    /**
     * save 后重新 open 的 totalSize 与 stat 一致；三种 Range 形态
     * （{@code bytes=start-end}、{@code bytes=-suffix}、{@code bytes=start-}）
     * 读出的内容与生成流对应区段逐字节一致，且 offset/responseLength/partial 正确。
     */
    @Test
    @Order(2)
    void statConsistencyAndRangeReadsMatchGeneratedContent() throws Exception {
        assumeContractEnvironment();
        assumeUploadedMultipartObject();

        long total = multipartObject.fileSize();
        long midOffset = total / 2;
        int midLength = 64 * 1024;

        // 中部闭区间
        assertRangeMatches(multipartObject.storageKey(), multipartContent,
                midOffset + "-" + (midOffset + midLength - 1), midOffset, midLength);

        // suffix：最后 8 KiB
        int suffixLength = 8 * 1024;
        assertRangeMatches(multipartObject.storageKey(), multipartContent,
                "-" + suffixLength, total - suffixLength, suffixLength);

        // 开放结尾：从 total-100_000 到结尾
        long openOffset = total - 100_000L;
        assertRangeMatches(multipartObject.storageKey(), multipartContent,
                openOffset + "-", openOffset, (int) (total - openOffset));
    }

    private void assertRangeMatches(
            String storageKey, DeterministicContent content, String rangeHeader,
            long expectedOffset, int expectedLength) throws Exception {
        try (ResourceContent actual = storage.open(storageKey, ResourceRange.fromHeader("bytes=" + rangeHeader))) {
            assertThat(actual.partial()).as("Range 读取 partial=true").isTrue();
            assertThat(actual.offset()).as("Range offset").isEqualTo(expectedOffset);
            assertThat(actual.responseLength()).as("Range responseLength").isEqualTo(expectedLength);
            assertThat(actual.totalSize()).as("totalSize 不因 Range 改变").isEqualTo(content.totalSize());
            assertThat(readAll(actual.inputStream()))
                    .as("Range 内容必须与生成流对应区段一致（range=%s）", rangeHeader)
                    .isEqualTo(content.bytesAt(expectedOffset, expectedLength));
        }
    }

    // ------------------------------------------------------------------
    // 用例 3：Range 不下载完整对象（1 KiB 区间只产出 1 KiB）
    // ------------------------------------------------------------------

    /**
     * 对 20 MiB+ 对象请求 1 KiB 区间：responseLength=1024，且流只产出 1024 字节
     * （证明 ranged GET 下推到 MinIO，未下载前置字节——计划 §6.4 / 拒绝方案 9）。
     */
    @Test
    @Order(3)
    void rangeReadDownloadsOnlyRequestedBytes() throws Exception {
        assumeContractEnvironment();
        assumeUploadedMultipartObject();

        long offset = multipartObject.fileSize() / 3;
        long bytesRead;
        try (ResourceContent content = storage.open(
                multipartObject.storageKey(),
                ResourceRange.fromHeader("bytes=" + offset + "-" + (offset + 1023)))) {
            assertThat(content.responseLength())
                    .as("1 KiB 区间的 responseLength 必须是 1024")
                    .isEqualTo(1024L);
            byte[] buffer = new byte[4096];
            bytesRead = 0;
            int read;
            while ((read = content.inputStream().read(buffer)) != -1) {
                bytesRead += read;
            }
        }
        assertThat(bytesRead)
                .as("ranged GET 流必须只产出请求区间字节（不下载完整对象）")
                .isEqualTo(1024L);
    }

    // ------------------------------------------------------------------
    // 用例 4：discard 补偿删除 → open NOT_FOUND
    // ------------------------------------------------------------------

    /**
     * 对应"数据库 rollback 后补偿删除"的存储侧验证：save 成功 → discard →
     * 再次 open 断言 NOT_FOUND。（事务侧"rollback 触发 discard 恰好一次"由
     * ResourceWriteCoordinatorTest 在真实 PG 上锁定；两条组合覆盖完整补偿语义。）
     */
    @Test
    @Order(4)
    void discardCompensationRemovesObjectThenOpenIsNotFound() throws Exception {
        assumeContractEnvironment();

        StoredResource stored = storage.save(ResourceSaveCommand.fromStream(
                "FILE", new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}),
                5L, "application/pdf", "pdf", 0));
        recordKey(stored.storageKey());

        try (ResourceContent content = storage.open(stored.storageKey(), ResourceRange.fullRead())) {
            assertThat(content.totalSize()).isEqualTo(5L);
        }

        storage.discard(stored.storageKey());

        String keySuffix = keySuffix(stored.storageKey());
        assertThatThrownBy(() -> storage.open(stored.storageKey(), ResourceRange.fullRead()))
                .as("discard 后对象必须不可读（NOT_FOUND），keySuffix=%s", keySuffix)
                .isInstanceOfSatisfying(ResourceStorageException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.NOT_FOUND));
    }

    // ------------------------------------------------------------------
    // 用例 5：匿名访问拒绝（两种账号模式都断言）
    // ------------------------------------------------------------------

    /**
     * 无凭证 MinioClient 对同一 endpoint/bucket 读取对象：私有 Bucket 必须
     * 拒绝（计划不变量 1）。按现有异常映射断言为 ResourceStorageException
     * （AccessDenied 类错误映射 UNAVAILABLE；SDK 凭证缺失类异常映射 IO_ERROR），
     * 绝不能是 NOT_FOUND 之外的可读成功路径。
     */
    @Test
    @Order(5)
    void anonymousAccessIsRejected() throws Exception {
        assumeContractEnvironment();

        // 先用应用凭证写入一个可读对象
        StoredResource stored = storage.save(ResourceSaveCommand.fromStream(
                "FILE", new ByteArrayInputStream(new byte[]{9}), 1L, "text/plain", "txt", 0));
        recordKey(stored.storageKey());

        MinioClient anonymousClient = MinioClient.builder()
                .endpoint(endpoint)
                .region(region)
                .build();
        MinioResourceStorage anonymousStorage =
                new MinioResourceStorage(anonymousClient, properties, meters);

        assertThatThrownBy(() -> anonymousStorage.open(stored.storageKey(), ResourceRange.fullRead()))
                .as("私有 Bucket 匿名读取必须被拒绝（AccessDenied 类错误）")
                .isInstanceOfSatisfying(ResourceStorageException.class, exception -> {
                    assertThat(exception.kind()).isNotEqualTo(ResourceStorageErrorKind.NOT_FOUND);
                    assertThat(exception.kind()).isIn(
                            ResourceStorageErrorKind.UNAVAILABLE, ResourceStorageErrorKind.IO_ERROR);
                });
    }

    // ------------------------------------------------------------------
    // 用例 6：跨前缀写拒绝（restricted 断言拒绝 / admin 验证并告警）
    // ------------------------------------------------------------------

    /**
     * 用应用账号向 {@code resources/} 之外的 bucket 根前缀
     * （{@code other-prefix/contract-tests/{runId}/probe.txt}）写对象。
     *
     * <p><b>账号前提</b>：本用例按"前缀受限专用账号"（只允许
     * {@code huajiang/resources/*} 的 GetObject/PutObject/DeleteObject，计划 §5.4）
     * 设计——restricted 模式断言被拒绝；<b>测试失败即说明账号权限过宽，
     * 这本身就是验收发现</b>（应收紧 Policy，而不是改测试）。
     * {@code -Dcontract.account=admin} 时改为"验证并告警"：断言管理员确实能写
     * （如实记录权限现状），输出 WARN 提示生产验收必须使用受限专用账号，并把
     * 探针对象纳入 AfterAll 清理。
     */
    @Test
    @Order(6)
    void crossPrefixWriteIsRejectedUnderRestrictedAccount() throws Exception {
        assumeContractEnvironment();

        String foreignKey = FOREIGN_PREFIX_ROOT + runId + "/probe.txt";
        if (adminMode) {
            putProbeObject(foreignKey);
            recordKey(foreignKey);
            log.warn("当前账号为管理员权限，跨前缀写越权用例按 admin 模式豁免"
                    + "（已验证管理员可写 bucket 根前缀）；生产验收必须使用前缀受限专用账号");
            assertThat(objectExists(foreignKey))
                    .as("admin 模式下探针对象应写入成功（如实记录权限现状）")
                    .isTrue();
        } else {
            assertThatThrownBy(() -> putProbeObject(foreignKey))
                    .as("受限账号向 resources/ 之外前缀写对象必须被拒绝")
                    .isInstanceOf(Exception.class);
        }
    }

    // ------------------------------------------------------------------
    // 用例 7：管理操作拒绝（restricted 断言拒绝 / admin 验证并告警）
    // ------------------------------------------------------------------

    /**
     * 应用账号执行管理操作：listBuckets 与 makeBucket。
     *
     * <p><b>账号前提</b>：应用账号不获得 CreateBucket、Policy 修改、用户管理或
     * 常规 Bucket 列表权限（计划 §5.4）——restricted 模式断言两者均失败；
     * 失败即说明账号权限过宽（验收发现）。{@code -Dcontract.account=admin} 时
     * 改为"验证并告警"：断言成功（管理员确实能做）并输出 WARN；makeBucket
     * 创建的临时 bucket 立即 removeBucket 清理。
     */
    @Test
    @Order(7)
    void administrativeOperationsAreRejectedUnderRestrictedAccount() throws Exception {
        assumeContractEnvironment();

        String tempBucketName = "contract-test-bucket-" + runId.substring(0, 8);
        if (adminMode) {
            List<io.minio.messages.ListAllMyBucketsResult.Bucket> buckets = appClient.listBuckets();
            assertThat(buckets).as("admin 模式下 listBuckets 应成功（如实记录权限现状）").isNotNull();
            log.warn("当前账号为管理员权限，管理操作越权用例按 admin 模式豁免"
                    + "（已验证管理员可 listBuckets/makeBucket）；生产验收必须使用前缀受限专用账号");

            appClient.makeBucket(MakeBucketArgs.builder().bucket(tempBucketName).build());
            createdBucketNames.add(tempBucketName);
            appClient.removeBucket(RemoveBucketArgs.builder().bucket(tempBucketName).build());
            createdBucketNames.remove(tempBucketName);
        } else {
            assertThatThrownBy(() -> appClient.listBuckets())
                    .as("受限账号执行 listBuckets 必须被拒绝")
                    .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> appClient.makeBucket(
                            MakeBucketArgs.builder().bucket(tempBucketName).build()))
                    .as("受限账号执行 makeBucket 必须被拒绝")
                    .isInstanceOf(Exception.class);
        }
    }

    // ------------------------------------------------------------------
    // 清理：删除该 runId 前缀全部对象并验证前缀清零（计划 §10 任务 6）
    // ------------------------------------------------------------------

    @AfterAll
    static void cleanupContractTestObjects() {
        if (storage == null) {
            // 未配置凭证（全类 SKIP）或环境初始化失败：无可清理对象。
            return;
        }

        // admin 模式可能遗留的临时 bucket（异常路径下未及 removeBucket 的）。
        for (String bucketName : List.copyOf(createdBucketNames)) {
            try {
                appClient.removeBucket(RemoveBucketArgs.builder().bucket(bucketName).build());
                createdBucketNames.remove(bucketName);
            } catch (Exception cleanupFailure) {
                log.warn("contract 清理：移除临时 bucket 失败 bucketNameSuffix={}",
                        keySuffix(bucketName));
            }
        }

        // 1) 测试期间记录的全部 key（含跨前缀探针）逐一幂等 discard。
        int discardFailures = 0;
        for (String key : List.copyOf(createdObjectKeys)) {
            try {
                storage.discard(key);
                createdObjectKeys.remove(key);
            } catch (Exception discardFailure) {
                discardFailures++;
            }
        }
        assertThat(discardFailures)
                .as("清理阶段所有已记录对象的 discard 都必须成功（失败数=%d）", discardFailures)
                .isZero();

        // 2) listObjects 兜底：删除可能未被记录的残留（受限账号无 list 权限时退化，
        //    步骤 1 已保证全部已知对象被删除）。
        long remainingUnderPrefix = deleteAllByListing(testObjectPrefix);
        long remainingForeign = deleteAllByListing(FOREIGN_PREFIX_ROOT + runId + "/");

        // 3) 验证：list 可用时前缀必须清零。
        if (remainingUnderPrefix >= 0) {
            long left = countByListing(testObjectPrefix) + countByListing(FOREIGN_PREFIX_ROOT + runId + "/");
            assertThat(left)
                    .as("contract 结束后测试前缀必须清零（剩余 %d 个对象）", left)
                    .isZero();
        }
        log.info("contract 清理完成：记录对象已全部删除，listObjects 兜底删除残留 {} 个（跨前缀 {} 个），list 可用={}",
                Math.max(remainingUnderPrefix, 0), Math.max(remainingForeign, 0), remainingUnderPrefix >= 0);
    }

    // ------------------------------------------------------------------
    // 环境与夹具
    // ------------------------------------------------------------------

    private static void assumeContractEnvironment() {
        assumeTrue(configured, "SKIP 真实 MinIO contract：缺少必填连接信息"
                + "（endpoint/access key/secret key/bucket 任一缺失；支持"
                + " TEST_MINIO_* 系统属性或环境变量，兼容 MINIO_ENDPOINT/MINIO_ACCESS_KEY/"
                + "MINIO_SECRET_KEY/MINIO_ROOT_USER/MINIO_ROOT_PASSWORD/"
                + "MINIO_RESOURCES_BUCKET/MINIO_DEFAULT_BUCKET）");
    }

    private static void assumeUploadedMultipartObject() {
        assumeTrue(multipartObject != null,
                "SKIP：依赖用例 1 的 multipart 对象（用例 1 未执行或被跳过）");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 系统属性优先、环境变量兜底；两轮都按给定名字顺序取第一个非空值。 */
    private static String resolve(String... names) {
        for (String name : names) {
            String value = System.getProperty(name);
            if (notBlank(value)) {
                return value.strip();
            }
        }
        for (String name : names) {
            String value = System.getenv(name);
            if (notBlank(value)) {
                return value.strip();
            }
        }
        return null;
    }

    private static String resolveDefault(String fallback, String... names) {
        String value = resolve(names);
        return value == null ? fallback : value;
    }

    /** 已知公共 play/AWS 端点：写入即 fail，绝不静默跳过（防真实数据写入公共端点）。 */
    private static boolean isKnownPublicEndpoint(String endpointValue) {
        try {
            String host = URI.create(endpointValue.strip()).getHost();
            if (host == null) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.equals("play.min.io")
                    || normalized.endsWith(".play.min.io")
                    || normalized.equals("s3.amazonaws.com")
                    || normalized.endsWith(".amazonaws.com");
        } catch (IllegalArgumentException invalidUri) {
            return false;
        }
    }

    private static MinioClient buildClient(
            String endpointValue, String accessKeyValue, String secretKeyValue, String regionValue) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(300))
                .build();
        return MinioClient.builder()
                .endpoint(endpointValue.strip())
                .credentials(accessKeyValue, secretKeyValue)
                .region(regionValue)
                .httpClient(httpClient)
                .build();
    }

    private static void recordKey(String storageKey) {
        createdObjectKeys.add(storageKey);
    }

    /** key 尾段（uuid.ext）：日志与断言消息的定位信息上限（计划不变量 17）。 */
    private static String keySuffix(String storageKey) {
        int slash = storageKey.lastIndexOf('/');
        return slash >= 0 ? storageKey.substring(slash + 1) : storageKey;
    }

    private static void putProbeObject(String objectKey) throws Exception {
        appClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .contentType("text/plain")
                .stream(new ByteArrayInputStream(new byte[]{1}), 1L, -1L)
                .build());
    }

    private static boolean objectExists(String objectKey) {
        try {
            appClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            return true;
        } catch (Exception notFoundOrDenied) {
            return false;
        }
    }

    /**
     * 列出并删除指定前缀下全部对象。
     *
     * @return 删除的对象数；{@code -1} 表示 listObjects 权限被拒（受限账号预期行为），
     *         调用方退化依赖已记录 key 的逐一 discard
     */
    private static long deleteAllByListing(String prefix) {
        List<String> keys = listKeys(prefix);
        if (keys == null) {
            return -1L;
        }
        for (String key : keys) {
            try {
                appClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket).object(key).build());
            } catch (Exception removeFailure) {
                log.warn("contract 清理：listObjects 兜底删除失败 keySuffix={}", keySuffix(key));
            }
        }
        return keys.size();
    }

    /** 前缀下剩余对象数；{@code -1} 表示 listObjects 不可用。 */
    private static long countByListing(String prefix) {
        List<String> keys = listKeys(prefix);
        return keys == null ? -1L : keys.size();
    }

    private static List<String> listKeys(String prefix) {
        try {
            List<String> keys = new ArrayList<>();
            Iterable<Result<Item>> results = appClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            for (Result<Item> result : results) {
                keys.add(result.get().objectName());
            }
            return keys;
        } catch (Exception listNotPermitted) {
            // 受限账号无 ListBucket 权限时的预期路径（计划 §5.4 不预授宽权限）。
            log.info("contract 清理：listObjects 不可用（受限账号预期行为），退化按记录 key 清理");
            return null;
        }
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        return stream.readAllBytes();
    }

    private static byte[] sha256(InputStream stream) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 不可用", unavailable);
        }
    }

    // ------------------------------------------------------------------
    // 确定性生成流：种子化 1 MiB 块循环（不落盘，可重复读、可按区间取字节）
    // ------------------------------------------------------------------

    /** 确定性内容：第 i 字节 = block[i mod 1MiB]，block 由固定种子生成。 */
    static final class DeterministicContent {

        private static final int BLOCK_SIZE = 1024 * 1024;

        private final long totalSize;
        private final byte[] block;

        DeterministicContent(long totalSize) {
            this.totalSize = totalSize;
            this.block = new byte[BLOCK_SIZE];
            new Random(0xC0FFEE).nextBytes(block);
        }

        long totalSize() {
            return totalSize;
        }

        /** 打开一个全新内容流（单次可消费）。 */
        InputStream open() {
            return new InputStream() {
                private long position;

                @Override
                public int read() {
                    if (position >= totalSize) {
                        return -1;
                    }
                    return block[(int) (position++ % BLOCK_SIZE)] & 0xFF;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    if (position >= totalSize) {
                        return -1;
                    }
                    int chunk = (int) Math.min(length, totalSize - position);
                    for (int i = 0; i < chunk; i++) {
                        buffer[offset + i] = block[(int) ((position + i) % BLOCK_SIZE)];
                    }
                    position += chunk;
                    return chunk;
                }
            };
        }

        /** 与流等价的区段字节（Range 内容校验基准）。 */
        byte[] bytesAt(long offset, int length) {
            byte[] result = new byte[length];
            for (int i = 0; i < length; i++) {
                result[i] = block[(int) ((offset + i) % BLOCK_SIZE)];
            }
            return result;
        }
    }
}
