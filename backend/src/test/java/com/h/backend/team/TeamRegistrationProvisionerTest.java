package com.h.backend.team;

import com.h.backend.auth.application.UserRegisteredEvent;
import com.h.matrixteam.MatrixClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册即建房监听器：提交后调用幂等开通路径；开通失败只告警不抛出（注册不受影响，团队页兜底）。
 */
class TeamRegistrationProvisionerTest {

    @Test
    void provisionsRoomForRegisteredUser() {
        TeamMatrixProvisioner provisioner = mock(TeamMatrixProvisioner.class);
        when(provisioner.loginFor(42L)).thenReturn(
                new TeamMatrixProvisioner.TeamAccess(mock(MatrixClient.class), "!room:matrix.local"));
        TeamRegistrationProvisioner listener = new TeamRegistrationProvisioner(provisioner);

        listener.onUserRegistered(new UserRegisteredEvent(42L));

        verify(provisioner).loginFor(42L);
    }

    @Test
    void provisioningFailureDoesNotBreakRegistration() {
        TeamMatrixProvisioner provisioner = mock(TeamMatrixProvisioner.class);
        when(provisioner.loginFor(42L)).thenThrow(new IllegalStateException("synapse down"));
        TeamRegistrationProvisioner listener = new TeamRegistrationProvisioner(provisioner);

        assertDoesNotThrow(() -> listener.onUserRegistered(new UserRegisteredEvent(42L)));
    }
}