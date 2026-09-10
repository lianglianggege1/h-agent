package com.h.backend.automation.infrastructure.scheduling;

import com.h.backend.automation.application.SchedulerProjectionGateway;
import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "automation.xxl-job", name = "enabled", havingValue = "true")
public class XxlJobAdminSchedulerGateway implements SchedulerProjectionGateway {

    private static final String MARKER_PREFIX = "automation:v1:";

    private final AutomationProperties.XxlJob properties;
    private final Duration executionTimeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile String cookie;

    public XxlJobAdminSchedulerGateway(AutomationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties.getXxlJob();
        this.executionTimeout = properties.getExecutionTimeout();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public synchronized ProjectionResult converge(ProjectionCommand command) {
        validateConfiguration();
        if (command.desiredState() == DesiredState.ACTIVE
                && !properties.getSchedulerZoneId().equals(command.zoneId())) {
            throw new IllegalArgumentException(
                    "XXL-Job 3.4.1 不支持任务级时区；任务时区必须为 " + properties.getSchedulerZoneId()
            );
        }
        String marker = MARKER_PREFIX + command.taskId();
        List<RemoteJob> jobs = findJobs(marker);
        if (command.knownJobId() != null && jobs.stream().noneMatch(job -> job.id() == command.knownJobId())) {
            jobs = new ArrayList<>(jobs);
            jobs.add(new RemoteJob(command.knownJobId(), null));
        }
        jobs = jobs.stream().sorted(Comparator.comparingLong(RemoteJob::id)).toList();

        if (command.desiredState() == DesiredState.DELETED) {
            for (RemoteJob job : jobs) {
                stop(job.id());
                delete(job.id());
            }
            return new ProjectionResult(null);
        }
        if (command.desiredState() == DesiredState.STOPPED) {
            for (RemoteJob job : jobs) {
                stop(job.id());
            }
            return new ProjectionResult(jobs.isEmpty() ? null : jobs.getFirst().id());
        }

        long canonicalId;
        if (jobs.isEmpty()) {
            canonicalId = insert(command, marker);
        } else {
            canonicalId = jobs.getFirst().id();
            update(canonicalId, command, marker);
            for (int index = 1; index < jobs.size(); index++) {
                stop(jobs.get(index).id());
                delete(jobs.get(index).id());
            }
        }
        start(canonicalId);
        return new ProjectionResult(canonicalId);
    }

    @Override
    public synchronized void trigger(TriggerCommand command) {
        validateConfiguration();
        String marker = MARKER_PREFIX + command.taskId();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("id", Long.toString(command.jobId()));
        form.put("executorParam", executorParam(marker, command.taskRevision(), "MANUAL", command.runId()));
        form.put("addressList", "");
        request("/jobinfo/trigger", form, true);
    }

    private List<RemoteJob> findJobs(String marker) {
        List<RemoteJob> jobs = new ArrayList<>();
        int start = 0;
        int pageSize = 100;
        while (true) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("start", Integer.toString(start));
            form.put("length", Integer.toString(pageSize));
            form.put("jobGroup", Integer.toString(properties.getJobGroup()));
            form.put("triggerStatus", "-1");
            form.put("jobDesc", "");
            form.put("executorHandler", properties.getExecutorHandler());
            form.put("author", "");
            JsonNode data = request("/jobinfo/pageList", form, true).path("data");
            JsonNode rows = data.path("data");
            int received = 0;
            for (JsonNode node : rows) {
                received++;
                String executorParam = node.path("executorParam").asText("");
                if (executorParam.contains(marker)) {
                    jobs.add(new RemoteJob(node.path("id").asLong(), executorParam));
                }
            }
            start += received;
            if (received == 0 || start >= data.path("total").asInt(0)) {
                break;
            }
        }
        return jobs;
    }

    private long insert(ProjectionCommand command, String marker) {
        JsonNode response;
        try {
            response = request("/jobinfo/insert", definition(command, marker, null), true);
        } catch (RuntimeException originalError) {
            List<RemoteJob> recovered = findJobs(marker);
            if (!recovered.isEmpty()) {
                return recovered.stream().mapToLong(RemoteJob::id).min().orElseThrow();
            }
            throw originalError;
        }
        long jobId = response.path("content").asLong(0);
        if (jobId <= 0) {
            List<RemoteJob> recovered = findJobs(marker);
            if (recovered.isEmpty()) {
                throw new IllegalStateException("XXL-Job 创建成功但未返回任务 ID");
            }
            return recovered.stream().mapToLong(RemoteJob::id).min().orElseThrow();
        }
        return jobId;
    }

    private void update(long jobId, ProjectionCommand command, String marker) {
        request("/jobinfo/update", definition(command, marker, jobId), true);
    }

    private void start(long jobId) {
        request("/jobinfo/start", Map.of("ids[]", Long.toString(jobId)), true);
    }

    private void stop(long jobId) {
        request("/jobinfo/stop", Map.of("ids[]", Long.toString(jobId)), true);
    }

    private void delete(long jobId) {
        request("/jobinfo/delete", Map.of("ids[]", Long.toString(jobId)), true);
    }

    private Map<String, String> definition(ProjectionCommand command, String marker, Long jobId) {
        Map<String, String> form = new LinkedHashMap<>();
        if (jobId != null) {
            form.put("id", Long.toString(jobId));
        }
        form.put("jobGroup", Integer.toString(properties.getJobGroup()));
        form.put("jobDesc", abbreviate(command.taskName(), 50));
        form.put("author", properties.getAuthor());
        form.put("alarmEmail", "");
        form.put("scheduleType", "CRON");
        form.put("scheduleConf", XxlCronExpression.fromSpring(command.cronExpression()));
        form.put("glueType", "BEAN");
        form.put("glueSource", "");
        form.put("glueRemark", "GLUE代码初始化");
        form.put("executorHandler", properties.getExecutorHandler());
        form.put("executorParam", executorParam(marker, command.taskRevision(), "SCHEDULED", null));
        form.put("executorRouteStrategy", "CONSISTENT_HASH");
        form.put("executorBlockStrategy", "DISCARD_LATER");
        form.put("misfireStrategy", "DO_NOTHING");
        form.put("executorTimeout", Long.toString(Math.max(1, executionTimeout.toSeconds() + 30)));
        form.put("executorFailRetryCount", "0");
        form.put("childJobId", "");
        return form;
    }

    private String executorParam(String marker, long revision, String triggerType, String runId) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("marker", marker);
            value.put("revision", revision);
            value.put("triggerType", triggerType);
            if (runId != null) {
                value.put("runId", runId);
            }
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("无法序列化 XXL-Job 任务参数", error);
        }
    }

    private JsonNode request(String path, Map<String, String> form, boolean allowRelogin) {
        ensureLoggedIn();
        HttpResponse<String> response = send(path, form, cookie);
        if ((response.statusCode() == 401 || response.statusCode() == 302) && allowRelogin) {
            cookie = null;
            ensureLoggedIn();
            response = send(path, form, cookie);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("XXL-Job Admin 请求失败：HTTP " + response.statusCode());
        }
        JsonNode json = parse(response.body());
        if (json.path("code").asInt(500) != 200) {
            throw new IllegalStateException("XXL-Job Admin 操作失败：" + json.path("msg").asText("未知错误"));
        }
        return json;
    }

    private void ensureLoggedIn() {
        if (cookie != null) {
            return;
        }
        Map<String, String> login = Map.of(
                "userName", properties.getUsername(),
                "password", properties.getPassword()
        );
        HttpResponse<String> response = send("/auth/doLogin", login, null);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("XXL-Job Admin 登录失败：HTTP " + response.statusCode());
        }
        cookie = response.headers().allValues("Set-Cookie").stream()
                .map(value -> value.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("XXL-Job Admin 登录未返回 Cookie"));
        JsonNode json = parse(response.body());
        if (json.path("code").asInt(500) != 200) {
            cookie = null;
            throw new IllegalStateException("XXL-Job Admin 登录失败");
        }
    }

    private HttpResponse<String> send(String path, Map<String, String> form, String requestCookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                .timeout(nonNullDuration(properties.getReadTimeout(), Duration.ofSeconds(8)))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)));
        if (requestCookie != null) {
            builder.header("Cookie", requestCookie);
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("XXL-Job Admin 请求被中断", error);
        } catch (IOException error) {
            throw new IllegalStateException("XXL-Job Admin 不可用", error);
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception error) {
            throw new IllegalStateException("XXL-Job Admin 返回了无效 JSON", error);
        }
    }

    private URI resolve(String path) {
        String baseUrl = properties.getBaseUrl();
        return URI.create((baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + path);
    }

    private void validateConfiguration() {
        properties.requireSecureConfiguration();
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "automation";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static Duration nonNullDuration(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    private record RemoteJob(long id, String executorParam) {
    }
}
