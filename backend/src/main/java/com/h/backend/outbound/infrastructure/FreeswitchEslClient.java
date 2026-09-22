package com.h.backend.outbound.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Component
public class FreeswitchEslClient {
    private final String host;
    private final int port;
    private final String password;

    private Socket socket;
    private InputStream reader;
    private PrintWriter writer;
    private volatile boolean connected;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "esl-reader");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Consumer<EslEvent>> eventHandlers = new ConcurrentHashMap<>();
    private final List<Consumer<EslEvent>> wildcardHandlers = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> authStatus = new AtomicReference<>();

    public FreeswitchEslClient(
            @Value("${freeswitch.esl.host:127.0.0.1}") String host,
            @Value("${freeswitch.esl.port:8021}") int port,
            @Value("${freeswitch.esl.password:ClueCon}") String password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    public synchronized void connect() throws IOException {
        if (connected) return;
        socket = new Socket(host, port);
        socket.setSoTimeout(10_000);
        reader = socket.getInputStream();
        writer = new PrintWriter(socket.getOutputStream(), true);
        EslMessage greeting = readMessage(reader);
        if (!"auth/request".equals(greeting.contentType())) throw new IOException("ESL did not request authentication");
        writer.print("auth " + password + "\n\n");
        writer.flush();
        EslMessage auth = readMessage(reader);
        if (!auth.headersText().contains("+OK") && !auth.body().contains("+OK")) {
            throw new IOException("ESL authentication failed");
        }
        socket.setSoTimeout(0);
        connected = true;
        io.submit(this::readLoop);
        log.info("[ESL] Connected to {}:{}", host, port);
    }

    public boolean isConnected() { return connected; }

    public void subscribe(String eventType, Consumer<EslEvent> handler) {
        eventHandlers.put(eventType, handler);
        sendCommand("event plain " + eventType);
    }

    public void subscribeAll(Consumer<EslEvent> handler) {
        wildcardHandlers.add(handler);
        sendCommand("event plain all");
    }

    public EslResponse originate(String uuid, String extension, String domain, String wsUrl) {
        String arg = "{origination_uuid=" + uuid + ",ignore_early_media=true}" +
                "sofia/internal/" + extension + "@" + domain + " &park()";
        return sendBgApi("originate", arg);
    }

    public EslResponse startAudioFork(String uuid, String wsUrl) {
        // mod_audio_fork expects its symbolic sample-rate token and an explicit
        // metadata argument.  "16000" without metadata is not valid for the
        // drachtio command grammar used by the acceptance probe.
        return sendApi("uuid_audio_fork", uuid + " start " + wsUrl + " mono 16k {}");
    }

    public EslResponse hangup(String uuid, String cause) {
        return sendApi("uuid_kill", uuid + " " + (cause == null ? "NORMAL_CLEARING" : cause));
    }

    public Optional<Boolean> channelExists(String uuid) {
        EslResponse response = sendApi("uuid_exists", uuid);
        if (!response.ok() || response.body() == null) return Optional.empty();
        String value = response.body().trim();
        if ("true".equalsIgnoreCase(value)) return Optional.of(true);
        if ("false".equalsIgnoreCase(value)) return Optional.of(false);
        return Optional.empty();
    }

    public EslResponse sendApi(String command, String args) {
        return sendAuthenticatedCommand("api", command, args);
    }

    public EslResponse sendBgApi(String command, String args) {
        return sendAuthenticatedCommand("bgapi", command, args);
    }

    private EslResponse sendAuthenticatedCommand(String mode, String command, String args) {
        String body = mode + " " + command + " " + (args == null ? "" : args);
        // Commands use a short-lived authenticated socket.  The long-lived socket is
        // exclusively owned by readLoop for events, so two threads never consume the
        // same ESL byte stream.
        try (Socket commandSocket = new Socket(host, port);
             PrintWriter commandWriter = new PrintWriter(commandSocket.getOutputStream(), true)) {
            commandSocket.setSoTimeout(10_000);
            InputStream commandReader = commandSocket.getInputStream();
            EslMessage greeting = readMessage(commandReader);
            if (!"auth/request".equals(greeting.contentType())) return new EslResponse("ERROR: missing auth request");
            commandWriter.print("auth " + password + "\n\n");
            commandWriter.flush();
            EslMessage auth = readMessage(commandReader);
            if (!auth.body().contains("+OK") && !auth.headers().toString().contains("+OK")) {
                return new EslResponse("ERROR: authentication failed");
            }
            commandWriter.print(body + "\n\n");
            commandWriter.flush();
            EslMessage response = readMessage(commandReader);
            return new EslResponse(response.body().isBlank() ? response.headersText() : response.body());
        } catch (IOException e) {
            return new EslResponse("ERROR: " + e.getMessage());
        }
    }

    private void sendCommand(String command) {
        if (!connected) return;
        String body = command + "\n\n";
        writer.print(body);
        writer.flush();
    }

    static EslMessage readMessage(InputStream source) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        StringBuilder rawHeaders = new StringBuilder();
        String line;
        while ((line = readAsciiLine(source)) != null && !line.isEmpty()) {
            rawHeaders.append(line).append('\n');
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        int length = Integer.parseInt(headers.getOrDefault("Content-Length", "0"));
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = source.read(bytes, offset, length - offset);
            if (read < 0) throw new IOException("ESL response ended early");
            offset += read;
        }
        return new EslMessage(headers.getOrDefault("Content-Type", ""), headers,
                new String(bytes, StandardCharsets.UTF_8), rawHeaders.toString().trim());
    }

    private static String readAsciiLine(InputStream source) throws IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = source.read()) >= 0) {
            if (next == '\n') break;
            if (next != '\r') line.append((char) next);
        }
        if (next < 0 && line.isEmpty()) return null;
        return line.toString();
    }

    private void readLoop() {
        try {
            while (connected) {
                EslMessage message = readMessage(reader);
                if (message.headers().isEmpty() && message.body().isEmpty()) break;
                if ("auth/request".equals(message.contentType())) {
                    writer.print("auth " + password + "\n\n");
                    writer.flush();
                } else if (!message.body().isBlank()) {
                    parseAndDispatch(message.body());
                }
            }
        } catch (IOException e) {
            if (connected) log.warn("[ESL] Reader stopped: {}", e.getMessage());
        }
        connected = false;
        log.info("[ESL] Reader loop exited");
    }

    private void parseAndDispatch(String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        String[] lines = body.split("\n");
        StringBuilder eventBody = new StringBuilder();
        boolean readingBody = false;
        for (String l : lines) {
            if (l.isEmpty()) {
                readingBody = true;
                continue;
            }
            if (readingBody) {
                if (!eventBody.isEmpty()) eventBody.append('\n');
                eventBody.append(l);
                continue;
            }
            int colon = l.indexOf(':');
            if (colon > 0) {
                String key = l.substring(0, colon).trim();
                String val = decodeHeader(l.substring(colon + 1).trim());
                headers.put(key, val);
            }
        }
        if (!eventBody.isEmpty()) headers.put("_body", eventBody.toString());
        String eventType = headers.get("Event-Name");
        EslEvent event = new EslEvent(eventType, headers);
        if (eventType != null) {
            var handler = eventHandlers.get(eventType);
            if (handler != null) {
                try { handler.accept(event); } catch (Exception e) { log.warn("[ESL] Handler error for {}: {}", eventType, e.getMessage()); }
            }
        }
        for (var wh : wildcardHandlers) {
            try { wh.accept(event); } catch (Exception e) { log.warn("[ESL] Wildcard handler error: {}", e.getMessage()); }
        }
    }

    private String decodeHeader(String value) {
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEncoding) {
            return value;
        }
    }

    @PreDestroy
    public void disconnect() {
        connected = false;
        io.shutdownNow();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        log.info("[ESL] Disconnected");
    }

    public record EslEvent(String type, Map<String, String> headers) {
        public String get(String key) { return headers.get(key); }
        public String callId() {
            return java.util.stream.Stream.of(
                    headers.get("variable_origination_uuid"), headers.get("Channel-Call-UUID"), headers.get("Unique-ID"))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        }
        public String answerState() { return headers.get("Answer-State"); }
        public String hangupCause() { return headers.get("Hangup-Cause"); }
    }

    record EslMessage(String contentType, Map<String, String> headers, String body, String headersText) {}

    public record EslResponse(String body) {
        public boolean ok() { return body != null && !body.startsWith("ERROR") && !body.startsWith("-ERR"); }
    }
}
