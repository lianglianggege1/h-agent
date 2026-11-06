package com.h.backend.document.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Internal persisted state. Only DocumentWorkflow's explicit views may leave the service. */
@Data
public class DocumentTask {
    /** 创建任务的追踪标识，跨数据库租约与服务重启保存。 */
    private String traceId;

    private String id;
    private Long userId;
    private String sessionId;
    private String resourceId;
    private String fileName;

    /** 原附件内容 SHA-256，避免跨文件版本错误复用。 */
    private String sourceHash;

    private String status;
    private String detail = "";
    private String kind = "PARSE";

    /** 同一用户会话中的稳定幂等键，用于防止重复解析与交付。 */
    private String requestKey;

    /** 乐观版本，每次状态更新递增，过期工作线程不得覆盖用户决定。 */
    private long revision;

    private int startPage;
    private int endPage;
    private int totalPages;

    /** 原始用户消息锚点，续跑沿用它而不追加伪造用户消息。 */
    private Long anchorMessageId;

    private String originalRequest;

    /** 原请求续跑状态，与内容放行状态独立，取消续跑不撤销人工核对。 */
    private String resumeStatus = "NONE";

    private Long resumedRunId;
    private boolean continuationCancelled;
    private long spentMillis;
    private long lastWorkAt;
    /** Recognition completion time; distinguishes a provisional chat acknowledgment from its final reply. */
    private long recognizedAtMillis;

    /** Jev 自动放行阈值；当前值是初始配置，需使用目标样本校准。 */
    private double threshold = 0.9;

    private int retries;
    private List<Page> pages = new ArrayList<>();
    private String markdown;
    private String format;
    private String outputResourceId;
    private List<String> sources = new ArrayList<>();

    public boolean released() {
        return "RECOGNIZED".equals(status) || "GOOD".equals(status) || "VERIFIED".equals(status) || "CONFIRMED".equals(status);
    }

    @Data
    public static class Page {
        private int number;
        private String quality;
        private String diagnostic = "";
        private String localText = "";
        private String fileId;
        private String jobId;
        private List<Block> blocks = new ArrayList<>();
    }

    @Data
    public static class Block {
        private String id;

        /** 覆盖疑点交给 Jev 复核；此块仅作为完整性证据，不重复加入已放行正文。 */
        private boolean coverageCheck;

        private String category = "text";
        private String text = "";
        private String verdict = "UNVERIFIED";
        private Double score;
        private int attempts;
        private List<List<Double>> regions = new ArrayList<>();
        private int imageWidth;
        private int imageHeight;
        private String decision;
        private String correction;

        public boolean approved(double threshold) {
            return "CORRECT".equals(decision)
                    || "ACCEPT".equals(decision)
                    || "scored".equals(verdict) && score != null && score >= threshold;
        }

        public String approvedText() {
            return "CORRECT".equals(decision) ? correction : text;
        }
    }
}
