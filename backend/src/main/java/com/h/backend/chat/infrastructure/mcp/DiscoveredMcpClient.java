package com.h.backend.chat.infrastructure.mcp;

import dev.langchain4j.mcp.client.McpClient;
import java.lang.reflect.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Interface adapter: one transport per address, with leases so retired sessions drain safely. */
public final class DiscoveredMcpClient implements InvocationHandler {
    private final Function<String, String> resolve;
    private final Function<String, McpClient> create;
    private final String key;
    private Session current;
    private boolean closed;

    private static final class Session {
        final String url;
        final McpClient client;
        int users;
        boolean retired;
        Session(String url, McpClient client) { this.url = url; this.client = client; }
    }

    private DiscoveredMcpClient(String key, Function<String, String> resolve, Function<String, McpClient> create) {
        this.key = key; this.resolve = resolve; this.create = create;
    }

    public static McpClient create(String key, Function<String, String> resolve, Function<String, McpClient> create) {
        return (McpClient) Proxy.newProxyInstance(McpClient.class.getClassLoader(), new Class<?>[]{McpClient.class},
                new DiscoveredMcpClient(key, resolve, create));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "DiscoveredMcpClient[" + key + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        if (method.getName().equals("key")) return key;
        if (method.getName().equals("close")) { close(); return null; }
        Session session = acquire();
        boolean async = false;
        try {
            Object result = method.invoke(session.client, args);
            if (result instanceof CompletableFuture<?> future) {
                async = true;
                return future.whenComplete((value, error) -> release(session));
            }
            return result;
        } catch (InvocationTargetException error) {
            throw error.getCause();
        } finally {
            if (!async) release(session);
        }
    }

    private synchronized Session acquire() {
        if (closed) throw new IllegalStateException("MCP client is closed");
        String url = resolve.apply(current == null ? null : current.url);
        if (current == null || !current.url.equals(url)) {
            Session next = new Session(url, create.apply(url));
            Session previous = current;
            current = next;
            if (previous != null) retire(previous);
        }
        current.users++;
        return current;
    }

    private synchronized void release(Session session) {
        if (--session.users == 0 && session.retired) closeClient(session.client);
    }

    private void retire(Session session) {
        session.retired = true;
        if (session.users == 0) closeClient(session.client);
    }

    private synchronized void close() {
        if (closed) return;
        closed = true;
        if (current != null) retire(current);
    }

    private static void closeClient(McpClient client) {
        try { client.close(); }
        catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(DiscoveredMcpClient.class).warn("Failed to close retired MCP client", error);
        }
    }
}
