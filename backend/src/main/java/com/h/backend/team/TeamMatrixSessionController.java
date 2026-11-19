package com.h.backend.team;

import com.h.backend.common.api.ApiResponse;
import com.h.matrixteam.MatrixClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已登录工作台用户自动接入团队聊天室：返回人类 Matrix 账号的连接凭据。
 * v1 所有工作台用户共享同一个人类 Matrix 身份。
 */
@RestController
@RequestMapping("/api/team")
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class TeamMatrixSessionController {

    public record TeamMatrixSession(
            String homeserverUrl,
            String roomId,
            String managerUserId,
            String workerUserId,
            String accessToken,
            String userId,
            String deviceId
    ) {
    }

    private final String homeserverUrl;
    private final String roomId;
    private final String humanUsername;
    private final String humanPassword;
    private final String managerUsername;
    private final String workerUsername;
    private volatile TeamMatrixSession cached;

    public TeamMatrixSessionController(
            @Value("${MATRIX_HOMESERVER_URL:http://localhost:8008}") String homeserverUrl,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_HUMAN_USERNAME:huajiang}") String humanUsername,
            @Value("${MATRIX_HUMAN_PASSWORD:}") String humanPassword,
            @Value("${MATRIX_MANAGER_USERNAME:manager}") String managerUsername,
            @Value("${MATRIX_WORKER_USERNAME:worker}") String workerUsername) {
        this.homeserverUrl = homeserverUrl;
        this.roomId = roomId;
        this.humanUsername = humanUsername;
        this.humanPassword = humanPassword;
        this.managerUsername = managerUsername;
        this.workerUsername = workerUsername;
    }

    @GetMapping("/matrix-session")
    public synchronized ApiResponse<TeamMatrixSession> session() {
        if (cached == null) {
            MatrixClient client = new MatrixClient(homeserverUrl);
            client.login(humanUsername, humanPassword);
            String domain = domainOf(client.userId());
            cached = new TeamMatrixSession(
                    homeserverUrl, roomId,
                    "@" + managerUsername + ":" + domain,
                    "@" + workerUsername + ":" + domain,
                    client.accessToken(), client.userId(), client.deviceId());
        }
        return ApiResponse.ok(cached);
    }

    private static String domainOf(String userId) {
        int colon = userId.indexOf(':');
        return colon < 0 ? "matrix.local" : userId.substring(colon + 1);
    }
}