package com.h.backend.team;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.common.context.LocalThread;
import com.h.matrixteam.MatrixClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已登录工作台用户接入团队聊天室：按当前登录态（LocalThread）解析用户，
 * 由其专属 Matrix 账号自动登录并加入房间，返回该用户的连接凭据。
 * 每个工作台用户拥有独立 Matrix 身份，房间事件 sender 即真实用户。
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
            String springWorkerUserId,
            String accessToken,
            String userId,
            String deviceId
    ) {
    }

    private final TeamMatrixProvisioner provisioner;
    private final String homeserverUrl;
    private final String roomId;
    private final String managerUsername;
    private final String workerUsername;
    private final String springWorkerUsername;
    private final Map<Long, TeamMatrixSession> cached = new ConcurrentHashMap<>();

    public TeamMatrixSessionController(
            TeamMatrixProvisioner provisioner,
            @Value("${MATRIX_HOMESERVER_URL:http://localhost:8008}") String homeserverUrl,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_MANAGER_USERNAME:manager}") String managerUsername,
            @Value("${MATRIX_WORKER_USERNAME:worker}") String workerUsername,
            @Value("${MATRIX_SPRING_WORKER_USERNAME:spring-worker}") String springWorkerUsername) {
        this.provisioner = provisioner;
        this.homeserverUrl = homeserverUrl;
        this.roomId = roomId;
        this.managerUsername = managerUsername;
        this.workerUsername = workerUsername;
        this.springWorkerUsername = springWorkerUsername;
    }

    @GetMapping("/matrix-session")
    public ApiResponse<TeamMatrixSession> session() {
        Long userId = LocalThread.getUserId();
        if (userId == null) {
            throw new IllegalStateException("No authenticated workbench user for team matrix session");
        }
        TeamMatrixSession session = cached.computeIfAbsent(userId, this::provision);
        return ApiResponse.ok(session);
    }

    private TeamMatrixSession provision(long userId) {
        MatrixClient client = provisioner.loginFor(userId);
        String domain = MatrixClient.domainOf(client.userId());
        return new TeamMatrixSession(
                homeserverUrl, roomId,
                "@" + managerUsername + ":" + domain,
                "@" + workerUsername + ":" + domain,
                "@" + springWorkerUsername + ":" + domain,
                client.accessToken(), client.userId(), client.deviceId());
    }
}