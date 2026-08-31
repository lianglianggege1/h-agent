package com.h.backend.chat.domain.memory;

/**
 * 用户长期记忆管理错误；message 为面向用户的安全文案，禁止携带存储细节。
 */
public final class HarnessMemoryDocumentException extends RuntimeException {

    public enum Kind {
        REVISION_CONFLICT(40920, "记忆内容已被其他会话更新，请重新加载最新内容后再保存"),
        CONTENT_TOO_LARGE(41301, "记忆正文超过 64 KiB 上限，请精简后重试"),
        CONTENT_CORRUPT(50020, "记忆数据异常，请稍后重试或联系管理员"),
        STORE_UNAVAILABLE(50320, "记忆存储暂时不可用，请稍后重试");

        private final int code;
        private final String safeMessage;

        Kind(int code, String safeMessage) {
            this.code = code;
            this.safeMessage = safeMessage;
        }

        public int code() {
            return code;
        }
    }

    private final Kind kind;

    public HarnessMemoryDocumentException(Kind kind) {
        super(kind.safeMessage);
        this.kind = kind;
    }

    public HarnessMemoryDocumentException(Kind kind, Throwable cause) {
        super(kind.safeMessage, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
