package com.h.backend.chat.model;

public class ChatMessagePayload {

    private String prompt;
    private String provider;
    private String providerRequestId;
    private String model;
    private String aspectRatio;
    private String status;
    private String triggerSource;

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public void setProviderRequestId(String providerRequestId) {
        this.providerRequestId = providerRequestId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAspectRatio() {
        return aspectRatio;
    }

    public void setAspectRatio(String aspectRatio) {
        this.aspectRatio = aspectRatio;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(String triggerSource) {
        this.triggerSource = triggerSource;
    }
}
