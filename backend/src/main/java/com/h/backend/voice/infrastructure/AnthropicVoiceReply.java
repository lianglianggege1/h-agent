package com.h.backend.voice.infrastructure;

import com.h.backend.chat.infrastructure.config.ChatModelEnvironment;
import com.h.backend.voice.application.VoiceReply;
import dev.langchain4j.data.message.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Same configured Java model, no tools or auto-writing ChatMemory. Owns the cancellable HTTP exchange. */
@Component
public class AnthropicVoiceReply implements VoiceReply {
    private final ChatModelEnvironment environment;
    private final HttpClient client;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    public AnthropicVoiceReply(ObjectMapper json) {
        this(ChatModelEnvironment.load(Path.of("")).orElse(null),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), json);
    }
    public AnthropicVoiceReply(ChatModelEnvironment environment, HttpClient client, ObjectMapper json) {
        this.environment = environment; this.client = client; this.json = json;
    }
    public String modelName() {
        if (environment == null) throw new com.h.backend.common.exception.BusinessException(50300, "普通 Agent 模型尚未配置");
        return environment.modelName();
    }
    public Execution prepare(String prompt, List<ChatMessage> history, Consumer<String> output, Consumer<String> terminal) {
        modelName();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage message : history) {
            if (message instanceof UserMessage u && u.hasSingleText()) messages.add(Map.of("role", "user", "content", u.singleText()));
            else if (message instanceof AiMessage a && a.text() != null && !a.text().isBlank()) messages.add(Map.of("role", "assistant", "content", a.text()));
        }
        var body = Map.of("model", modelName(), "system", prompt + "\n现在通过语音交谈。使用简短自然的中文纯文本，不使用Markdown，不输出思考过程。当前不具备工具能力，不声称已执行任何外部操作。",
                "messages", messages, "stream", true, "max_tokens", 2048);
        HttpRequest request = HttpRequest.newBuilder(URI.create(environment.baseUrl().replaceAll("/$", "") + "/messages"))
                .header("x-api-key", environment.apiKey()).header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return new Execution() {
            private final AtomicBoolean started = new AtomicBoolean();
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private volatile CompletableFuture<HttpResponse<InputStream>> response;
            private volatile InputStream stream;
            public void start() {
                if (!started.compareAndSet(false, true)) return;
                Thread.ofVirtual().name("voice-model").start(() -> {
                    String outcome = "FAILED";
                    try {
                        if (cancelled.get()) return;
                        response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
                        if (cancelled.get()) response.cancel(true);
                        var result = response.get();
                        stream = result.body();
                        if (cancelled.get()) return;
                        if (result.statusCode() != 200) throw new IOException("Model HTTP " + result.statusCode());
                        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                            String line;
                            boolean stopped = false;
                            while (!cancelled.get() && (line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) continue;
                                var event = json.readTree(line.substring(5).stripLeading());
                                if ("error".equals(event.path("type").asText())) throw new IOException("Model stream failed");
                                if ("content_block_delta".equals(event.path("type").asText())
                                        && "text_delta".equals(event.path("delta").path("type").asText())) {
                                    output.accept(event.path("delta").path("text").asText());
                                }
                                if ("message_stop".equals(event.path("type").asText())) { stopped = true; break; }
                            }
                            if (stopped) outcome = "GENERATED";
                        }
                    } catch (Exception ignored) {
                        // Provider bodies/prompts/credentials must not leak into logs or client errors.
                    } finally {
                        closeStream();
                        terminal.accept(cancelled.get() ? "CANCELLED" : outcome);
                    }
                });
            }
            public void cancel() {
                cancelled.set(true);
                var pending = response;
                if (pending != null) pending.cancel(true);
                closeStream();
            }
            private void closeStream() {
                try { if (stream != null) stream.close(); } catch (IOException ignored) { }
            }
        };
    }
}
