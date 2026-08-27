package com.h.backend.chat.infrastructure.content;

import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.application.impl.ChatResourceServiceImpl;
import com.h.backend.chat.application.impl.ImageGenerationServiceImpl;
import com.h.backend.chat.infrastructure.tools.FileDeliveryTool;
import com.h.backend.chat.interfaces.web.ChatResourceController;
import com.h.backend.generation.infrastructure.storage.ResourceStorageGeneratedArtifactAdapter;
import com.h.backend.voice.application.CallTurnService;
import com.h.backend.voice.application.VoiceTtsService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 内容安全分层守卫（新计划 §6.3 / §10 任务 4 + 审查修复第 3 项）：
 * 锁定签名校验的接入面。
 *
 * <p>不可信输入（用户上传、Agent 模型文件）必须在保存前经
 * {@link ResourceContentInspector} + {@link ResourceContentPolicy} 校验。
 *
 * <p>审查修复后不再有服务端自产内容的豁免：计划 §6.2/§6.3 原文要求
 * 「上传**或图片生成**调用方在保存前完成内容校验并提供 width/height」、
 * 「JPEG、PNG、WebP、MP4、MP3、M4A、WAV、WebM Audio 必须校验基础文件签名」、
 * 不变量 16「预览只允许经过签名校验的安全图片、音视频」——因此全部六条
 * 写入路径（用户上传、Agent 模型文件、图片生成、TTS、通话录音、
 * 异步生成 provider 代理下载）都是 gatekeeper。
 *
 * <p>本守卫保证：只有白名单内的写入点可以做保存侧校验；其他类（以及未来
 * 新增写入点）既无法调用 validateForSave，也无法依赖 Inspector，
 * 从而「谁校验」在架构层面显式且不可悄悄扩散——新增写入点必须显式加入
 * 白名单并补充校验测试。
 */
class ResourceContentArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = importProductionClasses();

    /**
     * 保存侧校验白名单（审查修复第 3 项后无豁免）：用户上传、Agent 模型文件、
     * 图片生成、TTS、通话录音分片合并、异步生成 provider 代理下载六条写入路径。
     */
    private static final Class<?>[] SAVE_VALIDATION_GATEKEEPERS = {
            ChatResourceController.class,
            FileDeliveryTool.class,
            ImageGenerationServiceImpl.class,
            VoiceTtsService.class,
            CallTurnService.class,
            ResourceStorageGeneratedArtifactAdapter.class,
    };

    /** Inspector 合法依赖者：六条校验路径 + 策略组件（消费检测结果类型）。 */
    private static final Class<?>[] INSPECTOR_DEPENDENTS = {
            ChatResourceController.class,
            FileDeliveryTool.class,
            ImageGenerationServiceImpl.class,
            VoiceTtsService.class,
            CallTurnService.class,
            ResourceStorageGeneratedArtifactAdapter.class,
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
                .because("保存侧签名校验只允许六条写入路径（用户上传/Agent 模型文件/"
                        + "图片生成/TTS/通话录音/provider 代理下载，计划 §6.2/§6.3："
                        + "全部图片与音视频写入点必须校验基础文件签名，无豁免）；"
                        + "新增写入点若属于图片/音视频写入必须显式加入白名单并补充校验测试")
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
