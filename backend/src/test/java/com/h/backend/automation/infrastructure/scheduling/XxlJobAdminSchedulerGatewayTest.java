package com.h.backend.automation.infrastructure.scheduling;

import com.h.backend.automation.application.SchedulerProjectionGateway;
import com.h.backend.automation.application.SchedulerProjectionGateway.DesiredState;
import com.h.backend.automation.application.SchedulerProjectionGateway.ProjectionCommand;
import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XxlJobAdminSchedulerGatewayTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsStoppedJobWithCurrent34FieldsThenStartsIt() throws Exception {
        List<String> calls = new ArrayList<>();
        Map<String, Map<String, List<String>>> forms = new LinkedHashMap<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        respond("/auth/doLogin", exchange -> {
            calls.add(exchange.getRequestURI().getPath());
            forms.put("login", form(exchange));
            exchange.getResponseHeaders().add("Set-Cookie", "XXL_JOB_LOGIN_IDENTITY=test; Path=/");
            json(exchange, "{\"code\":200}");
        });
        respond("/jobinfo/pageList", exchange -> {
            calls.add(exchange.getRequestURI().getPath());
            forms.put("pageList", form(exchange));
            json(exchange, "{\"code\":200,\"data\":{\"data\":[],\"total\":0}}");
        });
        respond("/jobinfo/insert", exchange -> {
            calls.add(exchange.getRequestURI().getPath());
            forms.put("insert", form(exchange));
            json(exchange, "{\"code\":200,\"content\":42}");
        });
        respond("/jobinfo/start", exchange -> {
            calls.add(exchange.getRequestURI().getPath());
            forms.put("start", form(exchange));
            json(exchange, "{\"code\":200}");
        });
        server.start();

        XxlJobAdminSchedulerGateway gateway = new XxlJobAdminSchedulerGateway(properties(), new ObjectMapper());
        SchedulerProjectionGateway.ProjectionResult result = gateway.converge(new ProjectionCommand(
                "task-7", 3L, DesiredState.ACTIVE, null, "每日简报", "0 0 9 * * *"
                , "Asia/Shanghai"
        ));

        assertEquals(42L, result.jobId());
        assertEquals(List.of("/auth/doLogin", "/jobinfo/pageList", "/jobinfo/insert", "/jobinfo/start"), calls);
        assertEquals(List.of("0 0 9 * * ?"), forms.get("insert").get("scheduleConf"));
        assertEquals(List.of("automationDispatchHandler"), forms.get("insert").get("executorHandler"));
        assertTrue(forms.get("insert").get("executorParam").getFirst().contains("automation:v1:task-7"));
        assertEquals(List.of("42"), forms.get("start").get("ids[]"));
    }

    private AutomationProperties properties() {
        AutomationProperties properties = new AutomationProperties();
        properties.getXxlJob().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getXxlJob().setUsername("admin");
        properties.getXxlJob().setPassword("secret");
        properties.getXxlJob().setJobGroup(3);
        return properties;
    }

    private void respond(String path, ExchangeHandler handler) {
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
    }

    private static Map<String, List<String>> form(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (body.isBlank()) {
            return values;
        }
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return values;
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
