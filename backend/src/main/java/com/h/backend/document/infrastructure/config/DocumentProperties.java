package com.h.backend.document.infrastructure.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 文档处理配置，通过 Spring 绑定显式配置、系统环境及本地 .env，配置值在启动时校验。 */
@Data
@Component
@ConfigurationProperties("chat.documents")
public class DocumentProperties {
    /** 单个输入或输出文件的字节上限。 */
    private long maxBytes = 52_428_800;

    /** 一次请求可处理的页数上限。 */
    private int maxPages = 200;

    /** 完整文档正文的字符上限。 */
    private int maxCharacters = 2_000_000;

    /** 单次工具读取的正文字符预算，超出部分通过偏移量分页。 */
    private int readCharacters = 40_000;

    /** 累计处理预算秒数，等待人工确认不占预算。 */
    private int budgetSeconds = 600;

    /** 单次云端请求的超时秒数。 */
    private int requestTimeoutSeconds = 60;

    /** ModelBest 官方服务地址，生产环境要求 HTTPS。 */
    private String modelbestBaseUrl = "https://api.modelbest.cn/v1";

    @lombok.ToString.Exclude
    /** ModelBest 凭据；由显式配置、系统环境或本地 .env 注入，禁止进入日志。 */
    private String modelbestApiKey = System.getenv().getOrDefault("MODELBEST_API_KEY", "");

    /** 云端文档识别模型标识。 */
    private String ocrModel = "MiniCPM-OCR-1.0";

    /** OCR 质量复核模型标识，与 TypeSafe Jev 配置独立。 */
    private String jevModel = "MiniCPM-OCR-Jev";

    /** Jev 自动放行阈值；当前值是初始配置，需使用目标样本校准。 */
    private double threshold = 0.9;

    /** 无界面 LibreOffice 可执行文件路径。 */
    private String sofficeCommand = "soffice";

    /** 新文件生成时使用的默认字体族。 */
    private String documentFont = "Noto Sans SC";

    @jakarta.annotation.PostConstruct
    void validate() {
        if (maxBytes < 1
                || maxBytes > 67_108_864
                || maxPages < 1
                || maxPages > 2000
                || readCharacters < 100
                || maxCharacters < readCharacters
                || maxCharacters > 10_000_000
                || budgetSeconds < 1
                || budgetSeconds > 600
                || requestTimeoutSeconds < 1
                || requestTimeoutSeconds > 120
                || !Double.isFinite(threshold)
                || threshold < 0
                || threshold > 1) throw new IllegalStateException("chat.documents 处理上限或质量阈值配置无效");
    }
}
