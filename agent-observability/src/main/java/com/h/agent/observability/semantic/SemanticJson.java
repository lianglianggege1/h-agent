package com.h.agent.observability.semantic;

import java.util.List;

/**
 * Bounded JSON encoder for SemanticContent. Enforces depth, element count and byte limits;
 * never throws on malformed input - falls back to CAPTURE_ERROR state.
 */
public final class SemanticJson {

    private final ContentLimits limits;

    public SemanticJson(ContentLimits limits) {
        this.limits = limits == null ? ContentLimits.defaults() : limits;
    }

    public ContentCaptureState stateOf(SemanticContent content) {
        if (content == null) {
            return ContentCaptureState.SOURCE_UNAVAILABLE;
        }
        return content.captureState() == null ? ContentCaptureState.INLINE : content.captureState();
    }

    public String encode(SemanticContent content) {
        if (content == null) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            boolean truncated = encodeContent(content, sb, 0);
            if (truncated || sb.length() > limits.maxObservationBytes()) {
                return truncateWithState(sb, ContentCaptureState.TRUNCATED_BY_LIMIT);
            }
            return sb.toString();
        } catch (RuntimeException ex) {
            return "{\"capture_state\":\"CAPTURE_ERROR\"}";
        }
    }

    private boolean encodeContent(SemanticContent content, StringBuilder sb, int depth) {
        sb.append('{');
        boolean truncated = false;
        boolean first = true;
        if (content.captureState() != null) {
            sb.append("\"capture_state\":\"").append(content.captureState()).append('"');
            first = false;
        }
        if (content.messages() != null && !content.messages().isEmpty()) {
            if (!first) {
                sb.append(',');
            }
            sb.append("\"messages\":");
            truncated |= encodeMessages(content.messages(), sb, depth + 1);
            first = false;
        }
        if (content.blocks() != null && !content.blocks().isEmpty()) {
            if (!first) {
                sb.append(',');
            }
            sb.append("\"blocks\":");
            truncated |= encodeBlocks(content.blocks(), sb, depth + 1);
        }
        sb.append('}');
        return truncated;
    }

    private boolean encodeMessages(List<SemanticMessage> messages, StringBuilder sb, int depth) {
        if (depth > limits.maxStructureDepth()) {
            sb.append('[');
            return true;
        }
        sb.append('[');
        boolean truncated = false;
        int count = Math.min(messages.size(), limits.maxCollectionElements());
        if (count < messages.size()) {
            truncated = true;
        }
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            SemanticMessage message = messages.get(i);
            sb.append("{\"role\":").append(escape(message.role())).append(",\"blocks\":");
            truncated |= encodeBlocks(message.blocks() == null ? List.of() : message.blocks(), sb, depth + 1);
            sb.append('}');
        }
        sb.append(']');
        return truncated;
    }

    private boolean encodeBlocks(List<SemanticBlock> blocks, StringBuilder sb, int depth) {
        if (depth > limits.maxStructureDepth()) {
            sb.append('[');
            return true;
        }
        sb.append('[');
        boolean truncated = false;
        int count = Math.min(blocks.size(), limits.maxCollectionElements());
        if (count < blocks.size()) {
            truncated = true;
        }
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            truncated |= encodeBlock(blocks.get(i), sb);
        }
        sb.append(']');
        return truncated;
    }

    private boolean encodeBlock(SemanticBlock block, StringBuilder sb) {
        if (block instanceof TextBlock b) {
            return appendText(sb, "text", b.text());
        }
        if (block instanceof ThinkingBlock b) {
            return appendText(sb, "thinking", b.thinking());
        }
        if (block instanceof JsonBlock b) {
            return appendText(sb, "json", b.json());
        }
        if (block instanceof ToolCallBlock b) {
            sb.append("{\"type\":\"tool_call\",\"id\":").append(escape(b.id()))
                    .append(",\"name\":").append(escape(b.name()))
                    .append(",\"arguments\":").append(escape(b.argumentsJson()))
                    .append('}');
            return false;
        }
        if (block instanceof ToolResultBlock b) {
            sb.append("{\"type\":\"tool_result\",\"id\":").append(escape(b.id()))
                    .append(",\"name\":").append(escape(b.name()))
                    .append(",\"error\":").append(b.error())
                    .append(",\"content\":").append(escape(b.contentJson()))
                    .append('}');
            return false;
        }
        if (block instanceof ArtifactReferenceBlock b) {
            sb.append("{\"type\":\"artifact\",\"artifact\":");
            encodeArtifact(b.reference(), sb);
            sb.append('}');
            return false;
        }
        if (block instanceof ProviderExtensionBlock b) {
            return appendText(sb, "provider_extension", b.json());
        }
        sb.append("{\"type\":\"unknown\"}");
        return false;
    }

    private void encodeArtifact(ArtifactReference reference, StringBuilder sb) {
        if (reference == null) {
            sb.append("null");
            return;
        }
        sb.append('{');
        appendField(sb, "resource_id", reference.resourceId(), true);
        appendField(sb, "source_resource_id", reference.sourceResourceId(), false);
        appendField(sb, "kind", reference.kind() == null ? null : reference.kind().name(), false);
        appendField(sb, "use", reference.use() == null ? null : reference.use().name(), false);
        appendField(sb, "business_role", reference.businessRole(), false);
        appendField(sb, "mime_type", reference.mimeType(), false);
        if (reference.byteSize() != null) {
            sb.append(",\"byte_size\":").append(reference.byteSize());
        }
        if (reference.width() != null) {
            sb.append(",\"width\":").append(reference.width());
        }
        if (reference.height() != null) {
            sb.append(",\"height\":").append(reference.height());
        }
        appendField(sb, "file_name", reference.fileName(), false);
        appendField(sb, "application_view_url", reference.applicationViewUrl(), false);
        sb.append('}');
    }

    private boolean appendText(StringBuilder sb, String type, String text) {
        boolean truncated = text != null && text.length() > limits.maxInlineBlockBytes();
        sb.append("{\"type\":\"").append(type).append("\",\"text\":").append(escape(text));
        if (truncated) {
            sb.append(",\"truncated\":true");
        }
        sb.append('}');
        return truncated;
    }

    private void appendField(StringBuilder sb, String name, String value, boolean first) {
        sb.append(first ? "" : ",").append('"').append(name).append("\":").append(escape(value));
    }

    private String truncateWithState(StringBuilder sb, ContentCaptureState state) {
        return "{\"capture_state\":\"" + state + "\"}";
    }

    private String escape(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length() + 2, limits.maxInlineBlockBytes() + 64));
        sb.append('"');
        int limit = Math.min(value.length(), limits.maxInlineBlockBytes());
        for (int i = 0; i < limit; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
