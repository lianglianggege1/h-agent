package com.h.backend.chat.application;

import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 资源内容响应策略（新计划 §6.3 / §10 任务 4）。
 *
 * <p>决定某资源「如何被响应」与「能否被保存」：
 * <ul>
 *   <li>读取侧 {@link #dispositionFor(String)}：只有经过签名校验的图片与
 *       音视频白名单允许 inline 预览（计划不变量 16）；PDF/Office/Markdown/
 *       文本等已知附件类型允许保存并强制 attachment；未知/非法 MIME 一律
 *       attachment 且响应 {@code application/octet-stream}。</li>
 *   <li>保存侧 {@link #validateForSave(ResourceContentInspector.InspectionResult, String)}：
 *       覆盖全部资源写入路径（审查修复后无豁免）——MIME 与签名冲突拒绝
 *       （拒绝方案 10：用户/文件名/HTTP Client/Agent 模型/provider 元数据声明的
 *       MIME 只是提示）、HTML/SVG/JS 主动内容明确拒绝、白名单声明但签名
 *       无法验证拒绝。</li>
 * </ul>
 *
 * <p><b>写入校验覆盖面（审查修复后无豁免）</b>：除用户上传
 * （ChatResourceController）与 Agent 模型文件（FileDeliveryTool）两条不可信
 * 输入路径外，服务端写入点——图片生成（ImageGenerationServiceImpl）、TTS
 * （VoiceTtsService）、语音块（CallTurnService，合并后字节是用户输入，MIME
 * 服务端硬编码 audio/webm）与异步生成 provider 代理下载
 * （ResourceStorageGeneratedArtifactAdapter）——同样在保存前经 Inspector
 * 签名校验：provider 元数据（含 MIME/size）不可信（计划 §6.2/§6.3）。
 * 即使如此，读取侧白名单仍会把非白名单 MIME 强制 attachment 兜底。
 * 写入校验覆盖面由 {@code ResourceContentArchitectureTest} 以 gatekeeper
 * 白名单锁定。
 */
@Component
public final class ResourceContentPolicy {

    /** inline 预览白名单：与 Inspector 签名库一一对应的图片/音视频（计划不变量 16）。 */
    private static final Set<String> INLINE_PREVIEWABLE_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "video/mp4",
            "audio/mpeg",
            "audio/mp4",
            "audio/wav",
            "audio/webm"
    );

    private static final String OCTET_STREAM = "application/octet-stream";

    /** RFC 6838 简化形态校验：type/subtype，仅限合法 token 字符。 */
    private static final Pattern MIME_TYPE_PATTERN =
            Pattern.compile("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$");

    /**
     * 读取侧处置决策（保存校验的历史资源 DB MIME、以及历史数据的兜底）。
     */
    public Disposition dispositionFor(String storedMimeType) {
        String normalized = ResourceContentInspector.normalizeMimeType(storedMimeType);
        if (normalized == null) {
            return new Disposition(false, OCTET_STREAM);
        }
        if (INLINE_PREVIEWABLE_MIME_TYPES.contains(normalized)) {
            return new Disposition(true, normalized);
        }
        if (MIME_TYPE_PATTERN.matcher(normalized).matches()) {
            // 已知附件类型（PDF/Office/Markdown/文本/其他合法 MIME）：
            // 只允许 attachment，但响应真实 Content-Type
            return new Disposition(false, normalized);
        }
        // 未知类型：attachment + octet-stream（计划 §6.3）
        return new Disposition(false, OCTET_STREAM);
    }

    /**
     * 保存侧校验（全部写入路径：用户上传 / Agent 模型文件 / 服务端写入点）。
     *
     * <p>规则（计划 §6.3 + 审查修复）：
     * <ol>
     *   <li>主动内容（HTML/SVG/JS）→ 拒绝；</li>
     *   <li>声明 MIME 非法 token（含 CR/LF 等控制字符）→ 拒绝，
     *       不留给存储 SDK 在 contentType 上抛 IAE；</li>
     *   <li>签名命中且与声明 MIME（归一化后）一致（或声明为空以检测为准）→ 放行；
     *       audio/mp4 与 video/mp4 同属 MP4 容器家族，互不视为冲突（m4a 互认）；</li>
     *   <li>签名命中但与声明冲突 → 拒绝（模型声明 MIME 不能覆盖检测结果）；</li>
     *   <li>签名未知（空文件/短头/纯文本/未知二进制）且声明白名单类型 → 拒绝
     *       （白名单图片/音视频必须通过签名校验）；</li>
     *   <li>签名未知且声明非白名单附件类型 → 放行，读取侧 attachment 兜底。</li>
     * </ol>
     */
    public SaveDecision validateForSave(
            ResourceContentInspector.InspectionResult inspection,
            String declaredMimeType) {
        if (inspection == null) {
            throw new NullPointerException("inspection must not be null");
        }

        if (inspection.category() == ResourceContentInspector.ContentCategory.ACTIVE_CONTENT) {
            return SaveDecision.reject("文件包含 HTML/SVG/JavaScript 等主动内容，不允许上传");
        }

        String normalizedDeclared = ResourceContentInspector.normalizeMimeType(declaredMimeType);
        if (normalizedDeclared != null && !MIME_TYPE_PATTERN.matcher(normalizedDeclared).matches()) {
            return SaveDecision.reject("声明的文件类型格式非法，已拒绝保存");
        }

        String detectedType = inspection.detectedType();
        if (detectedType != null) {
            if (normalizedDeclared == null
                    || normalizedDeclared.equals(detectedType)
                    || isSameMp4ContainerFamily(detectedType, normalizedDeclared)) {
                return SaveDecision.allow();
            }
            return SaveDecision.reject("声明的文件类型与内容签名不符，已拒绝保存");
        }

        // 签名未知：白名单声明必须可验证，非白名单附件类型放行
        if (normalizedDeclared != null && INLINE_PREVIEWABLE_MIME_TYPES.contains(normalizedDeclared)) {
            return SaveDecision.reject("声明的图片或音视频类型未通过文件签名校验，已拒绝保存");
        }
        return SaveDecision.allow();
    }

    /**
     * MP4 容器家族互认（审查修复）：Inspector 检测层只认 ftyp major brand
     * "M4A "，合法 m4a（isom/iso2/mp42 等 brand）被检测为 video/mp4；
     * 声明 audio/mp4 与检测 video/mp4（及反向）属同类 ISO BMFF 容器，不视为
     * 冲突。其他 MIME 与 MP4 容器仍按冲突拒绝。
     */
    private static boolean isSameMp4ContainerFamily(String detected, String declared) {
        return ("audio/mp4".equals(detected) && "video/mp4".equals(declared))
                || ("video/mp4".equals(detected) && "audio/mp4".equals(declared));
    }

    /** 读取侧处置：inlineSafe 表示允许 inline 预览，否则必须 attachment。 */
    public record Disposition(boolean inlineSafe, String responseContentType) {
    }

    /** 保存侧决策：allowed=false 时 reason 为固定安全文案（不含路径/key 等敏感信息）。 */
    public record SaveDecision(boolean allowed, String reason) {

        static SaveDecision allow() {
            return new SaveDecision(true, null);
        }

        static SaveDecision reject(String reason) {
            return new SaveDecision(false, reason);
        }
    }
}
