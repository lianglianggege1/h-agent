package com.h.backend.document.infrastructure.provider;

import com.h.backend.document.domain.DocumentTask;
import com.h.backend.document.infrastructure.config.DocumentProperties;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/** MAP file service + asynchronous document-parse, followed by MiniCPM-OCR-Jev. */
@lombok.extern.slf4j.Slf4j
@Component
public class ModelBestDocumentClient {
    private final DocumentProperties properties;
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public ModelBestDocumentClient(DocumentProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public boolean available() {
        return !properties.getModelbestApiKey().isBlank();
    }

    public String upload(BufferedImage image) throws Exception {
        String boundary =
                "DocumentBoundary" + java.util.UUID.randomUUID().toString().replace("-", "");
        var body = new ByteArrayOutputStream();
        body.write(
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"page.png\"\r\n"
                                + "Content-Type: image/png\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        body.write(png(image));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        JsonNode result =
                send(
                        "POST",
                        "/files",
                        body.toByteArray(),
                        "multipart/form-data; boundary=" + boundary,
                        null);
        String id = result.path("id").asText(result.path("file_id").asText(""));
        if (id.isBlank()) throw new ProviderFailure("云端未返回文件 ID", false);
        return id;
    }

    public String submit(String fileId, String idempotencyKey) throws Exception {
        JsonNode result =
                send(
                        "POST",
                        "/document-parse/jobs",
                        json.writeValueAsBytes(
                                Map.of(
                                        "file_id",
                                        fileId,
                                        "model",
                                        properties.getOcrModel(),
                                        "upright",
                                        false,
                                        "recognize_seal",
                                        true)),
                        "application/json",
                        idempotencyKey);
        String id = result.path("id").asText("");
        if (id.isBlank()) throw new ProviderFailure("云端未返回作业 ID", false);
        return id;
    }

    public JsonNode query(String jobId) throws Exception {
        if (!jobId.matches("[A-Za-z0-9_-]{1,160}")) throw new ProviderFailure("云端作业 ID 无效", false);
        return send("GET", "/document-parse/jobs/" + jobId, null, null, null);
    }

    public JsonNode verify(BufferedImage image, DocumentTask.Block block) throws Exception {
        byte[] imageBytes = png(image);
        String data = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes);
        log.info(
                "ModelBest Jev review model={} blockId={} coverageCheck={} category={}"
                        + " imageWidth={} imageHeight={} imageBytes={} textCharacters={}",
                properties.getJevModel(), block.getId(), block.isCoverageCheck(),
                block.getCategory(), image.getWidth(), image.getHeight(), imageBytes.length,
                block.getText().length());
        JsonNode envelope =
                send(
                        "POST",
                        "/chat/completions",
                        json.writeValueAsBytes(
                                Map.of(
                                        "model",
                                        properties.getJevModel(),
                                        "stream",
                                        false,
                                        "messages",
                                        List.of(
                                                Map.of(
                                                        "role",
                                                        "user",
                                                        "content",
                                                        List.of(
                                                                Map.of(
                                                                        "type",
                                                                        "image_url",
                                                                        "image_url",
                                                                        Map.of("url", data)),
                                                                Map.of(
                                                                        "type",
                                                                        "text",
                                                                        "text",
                                                                        block.getText())))),
                                        "minicpm_ocr_jev",
                                        Map.of(
                                                "mode",
                                                "score",
                                                "category",
                                                block.getCategory()))),
                        "application/json",
                        null);
        JsonNode result =
                json.readTree(
                        envelope.path("choices")
                                .path(0)
                                .path("message")
                                .path("content")
                                .asText("{}"));
        JsonNode scores = result.path("results");
        if (!scores.isArray()
                || scores.size() != 1
                // A single messages input is assigned case_id "0" by the provider.
                || !"0".equals(scores.path(0).path("case_id").asText()))
            throw new ProviderFailure("Jev 未返回匹配的有效评分", false);
        return scores.path(0);
    }

    private JsonNode send(String method, String path, byte[] body, String contentType, String key)
            throws Exception {
        if (!available()) throw new ProviderFailure("文档识别服务尚未配置凭据", false);
        String base = properties.getModelbestBaseUrl().replaceAll("/+$", "");
        URI uri = URI.create(base + path);
        if (!"https".equals(uri.getScheme())
                && !List.of("localhost", "127.0.0.1").contains(uri.getHost()))
            throw new ProviderFailure("文档服务必须使用 HTTPS", false);
        var request =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                        .header("Authorization", "Bearer " + properties.getModelbestApiKey())
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) request.header("Content-Type", contentType);
        if (key != null) request.header("Idempotency-Key", key);
        long started = System.nanoTime();
        try {
            var response = http.send(request.build(), info -> HttpResponse.BodySubscribers.limiting(
                    HttpResponse.BodySubscribers.ofByteArray(), 16_777_216));
            byte[] bytes = response.body();
            int status = response.statusCode();
            log.info(
                    "ModelBest request completed operation={} httpStatus={} elapsedMs={}",
                    path.startsWith("/document-parse/jobs/") ? "query-document-job" : path,
                    status,
                    (System.nanoTime() - started) / 1_000_000);
            if (status < 200 || status >= 300) {
                boolean retryable = status == 408 || status == 429 || status >= 500;
                String requestId = response.headers().firstValue("x-request-id")
                        .or(() -> response.headers().firstValue("request-id"))
                        .or(() -> response.headers().firstValue("trace-id")).orElse("");
                String stage = path.equals("/chat/completions") ? "Jev 复核"
                        : path.equals("/files") ? "图片上传" : "OCR 解析";
                String detail = "文档服务请求失败（HTTP " + status + "，阶段：" + stage + "）";
                if ("content_policy_violation".equals(providerErrorCode(bytes))) {
                    detail = stage + "被供应商内容审核拒绝（HTTP " + status
                            + "，content_policy_violation），本次质量复核未完成，请联系服务方排查";
                    if (!requestId.isBlank()) detail += "。请求 ID：" + sanitize(requestId, null);
                }
                var failure = new ProviderFailure(detail, retryable);
                log.error(
                        "ModelBest request rejected operation={} httpStatus={} retryable={}"
                                + " requestId={} requestBytes={} responseBytes={} error={}",
                        path.startsWith("/document-parse/jobs/") ? "query-document-job" : path,
                        status, retryable, sanitize(requestId, null),
                        body == null ? 0 : body.length, bytes.length, errorSummary(bytes, body), failure);
                throw failure;
            }
            if (bytes.length > 16_777_216) throw new ProviderFailure("文档服务响应超过大小上限", false);
            return json.readTree(bytes);
        } catch (java.io.IOException e) {
            log.error("ModelBest transport failed operation={} timeoutSeconds={} elapsedMs={}",
                    path.startsWith("/document-parse/jobs/") ? "query-document-job" : path,
                    properties.getRequestTimeoutSeconds(),
                    (System.nanoTime() - started) / 1_000_000, e);
            throw new ProviderFailure("文档服务连接或响应读取失败", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("ModelBest request interrupted operation={}", path, e);
            throw e;
        }
    }

    private String providerErrorCode(byte[] responseBody) {
        try {
            return json.readTree(responseBody).path("error").path("code").asText("");
        } catch (Exception notStructuredError) {
            return "";
        }
    }

    /** Only diagnostic fields are logged: providers may echo the entire document request. */
    private String errorSummary(byte[] responseBody, byte[] requestBody) {
        try {
            JsonNode root = json.readTree(responseBody);
            var summary = new LinkedHashMap<String, String>();
            for (String field : List.of("error", "code", "type", "param", "message", "detail")) {
                JsonNode value = root.path(field);
                if (value.isValueNode() && !value.isNull())
                    summary.put(field, sanitize(value.asText(), requestBody));
            }
            JsonNode error = root.path("error");
            for (String field : List.of("code", "type", "param", "message", "detail")) {
                JsonNode value = error.path(field);
                if (value.isValueNode() && !value.isNull())
                    summary.put("error." + field, sanitize(value.asText(), requestBody));
            }
            return json.writeValueAsString(summary);
        } catch (Exception ignored) {
            // Never fall back to raw HTML/text: it can contain credentials or document content.
            return "<unavailable or non-JSON error body>";
        }
    }

    private String sanitize(String value, byte[] requestBody) {
        String safe = value.replace(properties.getModelbestApiKey(), "<REDACTED>");
        if (requestBody != null && requestBody.length > 0 && requestBody[0] == '{') {
            try {
                var secrets = new ArrayList<String>();
                for (JsonNode message : json.readTree(requestBody).path("messages"))
                    for (JsonNode content : message.path("content")) {
                        String text = content.path("text").asText("");
                        if (!text.isBlank()) {
                            secrets.add(text);
                            text.lines().map(String::trim).filter(line -> !line.isBlank())
                                    .forEach(secrets::add);
                        }
                    }
                secrets.sort(java.util.Comparator.comparingInt(String::length).reversed());
                for (String secret : secrets) safe = safe.replace(secret, "<REDACTED>");
            } catch (Exception ignored) {
                return "<redacted: request could not be inspected>";
            }
        }
        safe = safe.replaceAll("data:[^\\s\"']+", "<REDACTED>")
                .replaceAll("(?i)(?:Bearer\\s+)?sk-[A-Za-z0-9_-]+", "<REDACTED>")
                .replaceAll("[0-9]{11,18}[0-9Xx]?", "<REDACTED>")
                .replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ");
        return safe.length() > 512 ? safe.substring(0, 512) + "…" : safe;
    }

    public static byte[] png(BufferedImage image) throws java.io.IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    public static class ProviderFailure extends RuntimeException {
        private final boolean retryable;

        public boolean isRetryable() {
            return retryable;
        }

        public ProviderFailure(String message, boolean retryable) {
            this(message, retryable, null);
        }

        public ProviderFailure(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }
    }
}
