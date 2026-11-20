package com.h.backend.capability.port;

/** 资源应用 URL 构造：产品为受鉴权应用端点，团队为 homeserver 媒体下载地址。 */
public interface ResourceUrlPort {

    String view(String resourceId);

    String download(String resourceId);
}