package com.h.backend.chat.application;

import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容响应策略测试（新计划 §6.3 / §10 任务 4）。
 *
 * <p>读取侧（dispositionFor）：白名单 inline、非白名单 attachment、
 * 未知/非法 MIME → application/octet-stream。
 *
 * <p>保存侧（validateForSave）：签名与声明一致放行、冲突拒绝（模型声明
 * MIME 只是提示，不能覆盖检测结果——拒绝方案 10）、HTML/SVG/JS 主动内容
 * 拒绝、白名单声明但签名无法验证拒绝、非白名单附件类型（PDF/Office/
 * 文本等）放行（读取侧 attachment 兜底）。
 */
class ResourceContentPolicyTest {

    private final ResourceContentPolicy policy = new ResourceContentPolicy();

    // ------------------------------------------------------------------
    // 读取侧：inline 白名单（计划不变量 16）
    // ------------------------------------------------------------------

    @Test
    void whitelistImageAudioVideoMimeTypesAreInlineSafe() {
        for (String mime : new String[]{
                "image/jpeg", "image/png", "image/webp",
                "video/mp4",
                "audio/mpeg", "audio/mp4", "audio/wav", "audio/webm"}) {
            ResourceContentPolicy.Disposition disposition = policy.dispositionFor(mime);
            assertTrue(disposition.inlineSafe(), mime + " 应允许 inline 预览");
            assertEquals(mime, disposition.responseContentType());
        }
    }

    @Test
    void storedMimeIsNormalizedBeforeWhitelistCheck() {
        ResourceContentPolicy.Disposition disposition =
                policy.dispositionFor("IMAGE/PNG; charset=binary");

        assertTrue(disposition.inlineSafe(), "归一化（大小写/参数）后按白名单判定");
        assertEquals("image/png", disposition.responseContentType());
    }

    @Test
    void pdfIsAttachmentWithRealContentType() {
        ResourceContentPolicy.Disposition disposition = policy.dispositionFor("application/pdf");

        assertFalse(disposition.inlineSafe());
        assertEquals("application/pdf", disposition.responseContentType());
    }

    @Test
    void officeAndMarkdownAndTextAreAttachment() {
        assertFalse(policy.dispositionFor("application/vnd.openxmlformats-officedocument.wordprocessingml.document").inlineSafe());
        assertFalse(policy.dispositionFor("text/markdown").inlineSafe());
        assertFalse(policy.dispositionFor("text/plain").inlineSafe());
        assertEquals("text/markdown", policy.dispositionFor("text/markdown").responseContentType());
    }

    @Test
    void svgAndHtmlAreNeverInlineSafe() {
        assertFalse(policy.dispositionFor("image/svg+xml").inlineSafe());
        assertFalse(policy.dispositionFor("text/html").inlineSafe());
        assertEquals("image/svg+xml", policy.dispositionFor("image/svg+xml").responseContentType());
    }

    @Test
    void blankStoredMimeFallsBackToOctetStream() {
        ResourceContentPolicy.Disposition disposition = policy.dispositionFor("  ");

        assertFalse(disposition.inlineSafe());
        assertEquals("application/octet-stream", disposition.responseContentType());
    }

    @Test
    void nullStoredMimeFallsBackToOctetStream() {
        ResourceContentPolicy.Disposition disposition = policy.dispositionFor(null);

        assertFalse(disposition.inlineSafe());
        assertEquals("application/octet-stream", disposition.responseContentType());
    }

    @Test
    void malformedStoredMimeFallsBackToOctetStream() {
        ResourceContentPolicy.Disposition disposition = policy.dispositionFor("not-a-mime-type");

        assertFalse(disposition.inlineSafe());
        assertEquals("application/octet-stream", disposition.responseContentType(),
                "非法 MIME 语法按未知类型处理");
    }

    // ------------------------------------------------------------------
    // 保存侧：签名校验（用户/模型输入路径）
    // ------------------------------------------------------------------

    @Test
    void signatureMatchingDeclaredMimeIsAllowed() {
        var inspection = new ResourceContentInspector.InspectionResult(
                "image/png", ResourceContentInspector.ContentCategory.IMAGE, true);

        ResourceContentPolicy.SaveDecision decision = policy.validateForSave(inspection, "image/png");

        assertTrue(decision.allowed());
        assertNull(decision.reason());
    }

    @Test
    void declaredMimeCannotOverrideDetectedSignature() {
        // 模型声明 PNG，字节实际是 JPEG：拒绝（保存时拒绝——计划 §6.3）
        var inspection = new ResourceContentInspector.InspectionResult(
                "image/jpeg", ResourceContentInspector.ContentCategory.IMAGE, false);

        ResourceContentPolicy.SaveDecision decision = policy.validateForSave(inspection, "image/png");

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("签名"));
    }

    @Test
    void declaredMimeIsNormalizedBeforeSaveCheck() {
        var inspection = new ResourceContentInspector.InspectionResult(
                "audio/mpeg", ResourceContentInspector.ContentCategory.AUDIO, false);

        ResourceContentPolicy.SaveDecision decision = policy.validateForSave(inspection, "AUDIO/MPEG; charset=binary");

        assertTrue(decision.allowed(), "归一化后与签名一致应放行");
    }

    @Test
    void blankDeclaredMimeWithDetectedSignatureIsAllowed() {
        var inspection = new ResourceContentInspector.InspectionResult(
                "video/mp4", ResourceContentInspector.ContentCategory.VIDEO, false);

        ResourceContentPolicy.SaveDecision decision = policy.validateForSave(inspection, " ");

        assertTrue(decision.allowed(), "无声明时以检测结果为准");
    }

    @Test
    void activeContentIsRejectedOnSave() {
        var inspection = new ResourceContentInspector.InspectionResult(
                null, ResourceContentInspector.ContentCategory.ACTIVE_CONTENT, false);

        ResourceContentPolicy.SaveDecision decision = policy.validateForSave(inspection, "text/html");

        assertFalse(decision.allowed(), "HTML/SVG/JS 主动内容保存侧明确拒绝");
        assertTrue(decision.reason().contains("主动内容"));
    }

    @Test
    void svgDeclaredAsImageIsRejected() {
        var inspection = new ResourceContentInspector.InspectionResult(
                null, ResourceContentInspector.ContentCategory.ACTIVE_CONTENT, false);

        ResourceContentPolicy.SaveDecision decision = policy.validateForSave(inspection, "image/svg+xml");

        assertFalse(decision.allowed(), "SVG 无论声明什么都按主动内容拒绝");
    }

    @Test
    void whitelistDeclarationWithoutVerifiableSignatureIsRejected() {
        // 无法验证签名（空文件/纯文本/未知二进制）却声明白名单图片：拒绝
        var inspection = new ResourceContentInspector.InspectionResult(
                null, ResourceContentInspector.ContentCategory.UNKNOWN, false);

        ResourceContentPolicy.SaveDecision decision = policy.validateForSave(inspection, "image/png");

        assertFalse(decision.allowed(), "白名单类型必须通过签名校验");
    }

    @Test
    void unknownCategoryWithNonWhitelistMimeIsAllowedForAttachment() {
        // 纯文本/PDF/Office 等附件：签名未知但声明非白名单 → 放行，读取侧 attachment 兜底
        var inspection = new ResourceContentInspector.InspectionResult(
                null, ResourceContentInspector.ContentCategory.UNKNOWN, false);

        assertTrue(policy.validateForSave(inspection, "application/pdf").allowed());
        assertTrue(policy.validateForSave(inspection, "text/markdown").allowed());
        assertTrue(policy.validateForSave(inspection, "application/octet-stream").allowed());
    }

    @Test
    void emptyFileDeclaredAsWhitelistTypeIsRejected() {
        var inspection = new ResourceContentInspector.InspectionResult(
                null, ResourceContentInspector.ContentCategory.UNKNOWN, false);

        ResourceContentPolicy.SaveDecision decision = policy.validateForSave(inspection, "audio/mpeg");

        assertFalse(decision.allowed());
    }

    @Test
    void nullInspectionIsProgrammingError() {
        assertThrows(NullPointerException.class, () -> policy.validateForSave(null, "image/png"));
    }
}
