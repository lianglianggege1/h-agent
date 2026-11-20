package com.h.backend.team;

import com.h.capabilities.seam.matrix.TeamHumanDirectory;
import com.h.matrixteam.MatrixClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 按当前登录工作台用户开通并接入其专属 Matrix 身份：
 * 首次访问时用 Synapse 共享密钥注册 u{id} 账号（随机密码存 users 表），
 * 之后凭库中账号密码自动登录，并由 Manager 邀请进团队房间后自动加入。
 * 取代「全体共享一个人类 Matrix 账号」的旧模型，房间事件 sender 即真实用户。
 */
@Component
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class TeamMatrixProvisioner {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TeamHumanDirectory directory;
    private final MatrixClient managerClient;
    private final String homeserverUrl;
    private final String roomId;
    private final String sharedSecret;

    public TeamMatrixProvisioner(
            TeamHumanDirectory directory,
            @Qualifier("teamMatrixClient") MatrixClient managerClient,
            @Value("${MATRIX_HOMESERVER_URL:http://localhost:8008}") String homeserverUrl,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_REGISTRATION_SHARED_SECRET:}") String sharedSecret) {
        this.directory = directory;
        this.managerClient = managerClient;
        this.homeserverUrl = homeserverUrl;
        this.roomId = roomId;
        this.sharedSecret = sharedSecret;
    }

    /** 返回已登录且已加入团队房间的用户 Matrix 客户端（账号不存在则先开通）。 */
    public MatrixClient loginFor(long userId) {
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
        ensureMembership(client, matrixUserId);
        return client;
    }

    private void ensureMembership(MatrixClient client, String matrixUserId) {
        if (roomId == null || roomId.isBlank()) {
            return;
        }
        // invite-only 房间：由已在房内的 Manager 邀请（invite 权限级别 0，任何成员可邀请）。
        // 用户已在房内时 invite 返回 403，容忍；随后 join 幂等。
        try {
            managerClient.invite(roomId, matrixUserId);
        } catch (RuntimeException alreadyMemberOrForbidden) {
            // 已加入或无需邀请，继续 join
        }
        client.join(roomId);
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