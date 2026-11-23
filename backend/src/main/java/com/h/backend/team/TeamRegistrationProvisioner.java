package com.h.backend.team;

import com.h.backend.auth.application.UserRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 注册即建房：用户注册事务提交后，立即为其开通专属 Matrix 身份与专属团队房间
 * （复用 TeamMatrixProvisioner 的幂等开通路径，bot 经各自桥接自动受邀进房）。
 * 开通失败只记录告警、不阻断注册——首次访问团队页（/api/team/matrix-session）会兜底重试。
 * 监听器运行在已提交事务之后，目录绑定的 DB 写入须在新事务中执行（REQUIRES_NEW），
 * 否则 JdbcTemplate 仍绑定已完成事务的连接，写入不会提交。
 */
@Component
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class TeamRegistrationProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TeamRegistrationProvisioner.class);

    private final TeamMatrixProvisioner provisioner;

    public TeamRegistrationProvisioner(TeamMatrixProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserRegistered(UserRegisteredEvent event) {
        try {
            TeamMatrixProvisioner.TeamAccess access = provisioner.loginFor(event.userId());
            log.info("用户 {} 注册即开通专属团队房间 {}", event.userId(), access.roomId());
        } catch (RuntimeException failure) {
            log.warn("用户 {} 注册时开通团队房间失败，首次访问团队页时将兜底重试: {}",
                    event.userId(), failure.getMessage());
        }
    }
}