package com.h.backend.chat.infrastructure.capability;

import com.h.backend.capability.port.ResourceUrlPort;
import com.h.backend.chat.application.ChatResourceUrls;
import org.springframework.stereotype.Component;

/** 产品聊天资源 URL：受鉴权应用端点。 */
@Component
public class ChatResourceUrlAdapter implements ResourceUrlPort {

    private final ChatResourceUrls urls;

    public ChatResourceUrlAdapter(ChatResourceUrls urls) {
        this.urls = urls;
    }

    @Override
    public String view(String resourceId) {
        return urls.view(resourceId);
    }

    @Override
    public String download(String resourceId) {
        return urls.download(resourceId);
    }
}