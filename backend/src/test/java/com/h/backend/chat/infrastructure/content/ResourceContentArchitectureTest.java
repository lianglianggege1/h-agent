package com.h.backend.chat.infrastructure.content;

import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.application.impl.ChatResourceServiceImpl;
import com.h.backend.chat.infrastructure.tools.FileDeliveryTool;
import com.h.backend.chat.interfaces.web.ChatResourceController;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 内容安全分层守卫（新计划 §6.3 / §10 任务 4）：锁定签名校验的接入面
 * 与服务端自产内容的豁免边界。
 *
 * <p>不可信输入（用户上传、Agent 模型文件）必须在保存前经
 * {@link ResourceContentInspector} + {@link ResourceContentPolicy} 校验；
 * 服务端自产内容（图片生成、TTS、语音块、异步生成 provider 代理下载）
 * 豁免签名校验——其 MIME 由服务端代码与受信 provider 契约决定，
 * 读取侧白名单仍会把非白名单 MIME 强制 attachment 兜底。
 *
 * <p>本守卫保证：只有两条不可信输入路径（ChatResourceController、
 * FileDeliveryTool）可以做保存侧校验；ImageGenerationServiceImpl、
 * VoiceTtsService、CallTurnService、ResourceStorageGeneratedArtifactAdapter
 * 等（以及未来新增写入点）既无法调用 validateForSave，也无法依赖 Inspector，
 * 从而「谁校验、谁豁免」在架构层面显式且不可悄悄扩散。
 */
class ResourceContentArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = importProductionClasses();

    /** 保存侧校验白名单：用户上传与 Agent 模型文件两条不可信输入路径。 */
    private static final Class<?>[] SAVE_VALIDATION_GATEKEEPERS = {
            ChatResourceController.class,
            FileDeliveryTool.class,
    };

    /** Inspector 合法依赖者：两条校验路径 + 策略组件（消费检测结果类型）。 */
    private static final Class<?>[] INSPECTOR_DEPENDENTS = {
            ChatResourceController.class,
            FileDeliveryTool.class,
            ResourceContentPolicy.class,
    };

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
    void onlyUntrustedInputGatekeepersMayCallSaveValidation() {
        noClasses().that().doNotBelongToAnyOf(SAVE_VALIDATION_GATEKEEPERS)
                .should().callMethod(
                        ResourceContentPolicy.class,
                        "validateForSave",
                        ResourceContentInspector.InspectionResult.class,
                        String.class)
                .because("保存侧签名校验只允许用于用户上传与 Agent 模型文件两条不可信输入路径；"
                        + "服务端自产内容（图片生成/TTS/语音块/provider 代理下载）按计划 §6.3 豁免，"
                        + "新增写入点若属于不可信输入必须显式加入白名单并补充校验测试")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void resourceContentInspectorMayOnlyBeUsedByPolicyAndGatekeepers() {
        noClasses().that().doNotBelongToAnyOf(INSPECTOR_DEPENDENTS)
                .should().dependOnClassesThat().areAssignableTo(ResourceContentInspector.class)
                .because("Inspector 是内容安全的基础设施细节，依赖面收敛在两条校验路径"
                        + "与策略组件内，防止签名校验语义被绕开或扩散到豁免点")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void readDispositionMayOnlyBeUsedByChatResourceService() {
        noClasses().that().doNotBelongToAnyOf(ChatResourceServiceImpl.class, ResourceContentPolicy.class)
                .should().callMethod(ResourceContentPolicy.class, "dispositionFor", String.class)
                .because("读取侧处置策略由 ChatResourceService 统一执行；"
                        + "Controller 只消费 ResourceResponse 的既定处置结果")
                .check(PRODUCTION_CLASSES);
    }
}
