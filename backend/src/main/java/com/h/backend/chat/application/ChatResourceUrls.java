package com.h.backend.chat.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 应用层资源 URL 构造（计划 §2.4.3/§4.4）。
 *
 * <p>URL 构造职责已从存储 seam 移出：数据库与响应继续保存稳定的受鉴权应用 URL
 * {@code /api/chat/resources/{id}/content|download}，不保存 MinIO URL 或签名 URL。
 *
 * <p>{@code publicBaseUrl} 来自 {@code chat.resources.public-base-url}（默认空串），
 * 用于跨域部署时补全绝对地址前缀。
 */
@Component
public class ChatResourceUrls {

    private final String publicBaseUrl;

    public ChatResourceUrls(@Value("${chat.resources.public-base-url:}") String publicBaseUrl) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.stripTrailing();
        this.publicBaseUrl = base;
    }

    /** 预览（content）端点。 */
    public String view(String resourceId) {
        return publicBaseUrl + "/api/chat/resources/" + resourceId + "/content";
    }

    /** 下载端点。 */
    public String download(String resourceId) {
        return publicBaseUrl + "/api/chat/resources/" + resourceId + "/download";
    }
}
