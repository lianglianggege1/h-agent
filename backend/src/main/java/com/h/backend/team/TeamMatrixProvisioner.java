package com.h.backend.team;

import com.h.capabilities.seam.matrix.TeamHumanDirectory;
import com.h.matrixteam.MatrixClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * 按当前登录工作台用户开通并接入其专属 Matrix 身份与专属团队房间：
 * 首次访问时用 Synapse 共享密钥注册 u{id} 账号（随机密码存 users 表），之后凭库中账号密码自动登录；
 * 房间同样每用户独立——首次由用户账号创建 invite-only 私密房间并邀请三个 bot（存 users.matrix_room_id），
 * 之后复用该房间。bot 经各自桥接自动接受邀请进房，房间消息按用户天然隔离，取代「全体共享单一全局房间」的旧模型。
 */
@Component
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class TeamMatrixProvisioner {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 已登录用户专属 Matrix 客户端与其专属团队房间 id。 */
    public record TeamAccess(MatrixClient client, String roomId) {
    }

    private final TeamHumanDirectory directory;
    private final MatrixClient managerClient;
    private final String homeserverUrl;
    private final String sharedSecret;
    private final String workerUsername;
    private final String springWorkerUsername;

    public TeamMatrixProvisioner(
            TeamHumanDirectory directory,
            @Qualifier("teamMatrixClient") MatrixClient managerClient,
            @Value("${MATRIX_HOMESERVER_URL:http://localhost:8008}") String homeserverUrl,
            @Value("${MATRIX_REGISTRATION_SHARED_SECRET:}") String sharedSecret,
            @Value("${MATRIX_WORKER_USERNAME:worker}") String workerUsername,
            @Value("${MATRIX_SPRING_WORKER_USERNAME:spring-worker}") String springWorkerUsername) {
        this.directory = directory;
        this.managerClient = managerClient;
        this.homeserverUrl = homeserverUrl;
        this.sharedSecret = sharedSecret;
        this.workerUsername = workerUsername;
        this.springWorkerUsername = springWorkerUsername;
    }

    /** 返回已登录且已拥有专属团队房间的用户 Matrix 客户端与房间 id（账号/房间不存在则先开通）。 */
    public TeamAccess loginFor(long userId) {
        TeamHumanDirectory.MatrixAccount account = directory.accountOf(userId);
        MatrixClient client = new MatrixClient(homeserverUrl);
        String matrixUserId;
        if (account == null) {
            if (sharedSecret == null || sharedSecret.isBlank()) {
                throw new IllegalStateException("Set MATRIX_REGISTRATION_SHARED_SECRET to provision per-user Matrix accounts");
            }
            String password = randomPassword();
            matrixUserId = client.register(sharedSecret, "u" + userId, password);
            directory.bind(userId, matrixUserId, password);
        } else {
            matrixUserId = account.matrixUserId();
            client.login(localpartOf(matrixUserId), account.password());
        }
        String roomId = ensureTeamRoom(client, userId);
        return new TeamAccess(client, roomId);
    }

    /** 复用用户已绑定的专属房间；尚未建房则由用户账号创建私密房间并邀请三个 bot。 */
    private String ensureTeamRoom(MatrixClient client, long userId) {
        String existing = directory.roomIdOf(userId);
        if (existing != null && !existing.isBlank()) {
            // 幂等 re-join（用户已是创建者/成员），bot 早已在房内。
            client.join(existing);
            return existing;
        }
        String domain = MatrixClient.domainOf(managerClient.userId());
        List<String> bots = List.of(
                managerClient.userId(),
                "@" + workerUsername + ":" + domain,
                "@" + springWorkerUsername + ":" + domain);
        String roomId = client.createTeamRoom("团队聊天室", bots);
        directory.bindRoom(userId, roomId);
        return roomId;
    }

    private static String localpartOf(String matrixUserId) {
        String id = matrixUserId.startsWith("@") ? matrixUserId.substring(1) : matrixUserId;
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(0, colon);
    }

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}