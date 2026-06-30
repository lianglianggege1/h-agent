package com.h.otheragents.a2a;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "other-agents.a2a")
public class OtherAgentsA2AProperties {

    private String publicUrl = "http://localhost:8082";

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }
}
