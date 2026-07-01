package com.h.backend.voice.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voice.tts")
public class VoiceTtsProperties {

    private MiniMax minimax = new MiniMax();
    private int previewMaxTextLength = 240;
    private int messageMaxTextLength = 5000;

    public MiniMax getMinimax() {
        return minimax;
    }

    public void setMinimax(MiniMax minimax) {
        this.minimax = minimax == null ? new MiniMax() : minimax;
    }

    public int getPreviewMaxTextLength() {
        return previewMaxTextLength;
    }

    public void setPreviewMaxTextLength(int previewMaxTextLength) {
        this.previewMaxTextLength = Math.max(1, previewMaxTextLength);
    }

    public int getMessageMaxTextLength() {
        return messageMaxTextLength;
    }

    public void setMessageMaxTextLength(int messageMaxTextLength) {
        this.messageMaxTextLength = Math.max(1, messageMaxTextLength);
    }

    public static class MiniMax {
        private String baseUrl = "https://api.minimaxi.com";
        private String apiKey = "";
        private String model = "speech-2.8-turbo";
        private String voiceId = "male-qn-qingse";
        private String format = "mp3";
        private int sampleRate = 32000;
        private int bitrate = 128000;
        private int requestTimeoutSeconds = 60;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getVoiceId() {
            return voiceId;
        }

        public void setVoiceId(String voiceId) {
            this.voiceId = voiceId;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public int getSampleRate() {
            return sampleRate;
        }

        public void setSampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        public int getBitrate() {
            return bitrate;
        }

        public void setBitrate(int bitrate) {
            this.bitrate = bitrate;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
        }
    }
}
