package com.h.backend.chat.infrastructure.storage;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 资源存储分层架构守卫（新计划任务 3）。
 *
 * <p>锁定三条不变量：
 * <ul>
 *   <li>业务层（Controller / Tools / Voice / Generation）写入必须经
 *       {@link ResourceWriteCoordinator}：禁止直接调用 {@link ResourceStorage#save}
 *       或 {@link ResourceStorage#discard}——绕过 Coordinator 会失去
 *       「对象已写、挂接失败、回滚补偿」的事务语义（计划 §4.3）。</li>
 *   <li>save / discard 的直接调用者白名单收敛为
 *       {@code com.h.backend.chat.infrastructure.storage} 包内部
 *       （Coordinator 实现与存储 Adapter 本体）。</li>
 *   <li>业务层禁止依赖 {@code io.minio.*}：MinIO SDK 是存储基础设施的实现细节，
 *       业务层只能看见 ResourceStorage / ResourceWriteCoordinator seam。</li>
 * </ul>
 *
 * <p>方案选型说明：采用 ArchUnit 核心库（{@code com.tngtech.archunit:archunit}）
 * + {@link ClassFileImporter} 显式导入 {@code target/classes} 生产字节码，写普通
 * JUnit 测试。理由：
 * <ul>
 *   <li>相比 archunit-junit5 引擎：无需引入 ArchUnit 自带的 JUnit 平台引擎，
 *       规避其与本项目 JUnit 6 内核的兼容性风险；规则失败即普通断言失败，
 *       与现有测试命令（{@code mvn -Dtest=...}）无缝集成。</li>
 *   <li>相比源码文本扫描：字节码级分析由 ArchUnit 保证语义准确
 *       （调用目标按持有者类型解析、包匹配用 {@code ..} 语法），
 *       不受注释、字符串字面量中的同名文本误报影响，且无需自行维护解析器。</li>
 * </ul>
 */
class ResourceStorageArchitectureTest {

    /** 业务层包：写入路径必须经 Coordinator 的四个区域（任务书指定）。 */
    private static final String[] BUSINESS_PACKAGES = {
            "com.h.backend.chat.interfaces.web..",
            "com.h.backend.chat.infrastructure.tools..",
            "com.h.backend.voice..",
            "com.h.backend.generation..",
    };

    /** 存储基础设施包：ResourceStorage.save / discard 唯一合法调用区域。 */
    private static final String STORAGE_PACKAGE = "com.h.backend.chat.infrastructure.storage..";

    /**
     * 只导入生产字节码（target/classes），排除测试类——测试代码中
     * mock ResourceStorage 的调用（如 Coordinator 自身的测试）不参与架构检查。
     * surefire 工作目录默认为模块 basedir（backend/），同时兼容从仓库根目录执行。
     */
    private static final JavaClasses PRODUCTION_CLASSES = importProductionClasses();

    private static JavaClasses importProductionClasses() {
        List<Path> candidates = List.of(
                Paths.get("target", "classes"),
                Paths.get("backend", "target", "classes"));
        return candidates.stream()
                .filter(Files::isDirectory)
                .findFirst()
                .map(path -> new ClassFileImporter().importPath(path))
                .orElseThrow(() -> new IllegalStateException(
                        "未找到 target/classes；请在 backend 模块内运行测试（mvn test）"));
    }

    @Test
    void businessLayersMustNotCallResourceStorageSave() {
        noClasses().that().resideInAnyPackage(BUSINESS_PACKAGES)
                .should().callMethod(ResourceStorage.class, "save", ResourceSaveCommand.class)
                .because("业务层写入必须经 ResourceWriteCoordinator 以获得事务补偿语义（计划 §4.3）")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void businessLayersMustNotCallResourceStorageDiscard() {
        noClasses().that().resideInAnyPackage(BUSINESS_PACKAGES)
                .should().callMethod(ResourceStorage.class, "discard", String.class)
                .because("discard 仅用于补偿未成功挂接的对象，只允许 Coordinator 调用（计划不变量 15）")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void resourceStorageSaveMayOnlyBeCalledFromStorageInfrastructure() {
        noClasses().that().resideOutsideOfPackage(STORAGE_PACKAGE)
                .should().callMethod(ResourceStorage.class, "save", ResourceSaveCommand.class)
                .because("ResourceStorage.save 是存储 seam 的底层入口，"
                        + "唯一生产调用者应为 TransactionalResourceWriteCoordinator")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void resourceStorageDiscardMayOnlyBeCalledFromStorageInfrastructure() {
        noClasses().that().resideOutsideOfPackage(STORAGE_PACKAGE)
                .should().callMethod(ResourceStorage.class, "discard", String.class)
                .because("discard 是 Coordinator 补偿路径的一部分，"
                        + "业务层直接调用会破坏「discard 恰好一次」语义")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void businessLayersMustNotDependOnMinioSdk() {
        noClasses().that().resideInAnyPackage(BUSINESS_PACKAGES)
                .should().dependOnClassesThat().resideInAPackage("io.minio..")
                .because("MinIO SDK 是存储基础设施的实现细节，"
                        + "业务层只能依赖 ResourceStorage / ResourceWriteCoordinator seam")
                .check(PRODUCTION_CLASSES);
    }
}
