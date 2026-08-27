package com.h.otheragents.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置多个 MCP Endpoint：同一进程内、按 URL 路径区分、拥有独立服务身份、工具集和认证凭证的
 * MCP Server 暴露单元。详见 docs/adr/0004。
 */
@ConfigurationProperties(prefix = "other-agents.mcp")
public class McpEndpointProperties {

    private Map<String, Endpoint> endpoints = new LinkedHashMap<>();

    public Map<String, Endpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, Endpoint> endpoints) {
        this.endpoints = endpoints;
    }

    public static class Endpoint {

        /** MCP 协议挂载路径，如 /test1/mcp，endpoint 之间不可重复 */
        private String path;

        /** Bearer Token；缺失即启动失败（fail-fast），不允许匿名 endpoint */
        private String token;

        /** MCP serverInfo.name；缺省为 "<endpointId>-mcp" */
        private String name;

        /** MCP serverInfo.version；缺省为 0.0.1 */
        private String version;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }
}
