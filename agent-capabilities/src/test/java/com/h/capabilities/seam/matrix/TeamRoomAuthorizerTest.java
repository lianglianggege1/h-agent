package com.h.capabilities.seam.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.matrixteam.MatrixClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamRoomAuthorizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HUMAN = "@u116:matrix.local";
    private static final String OTHER = "@u999:matrix.local";
    private static final String MANAGER = "@manager:matrix.local";
    private static final String ROOM = "!own:matrix.local";
    private static final String FOREIGN = "!foreign:matrix.local";

    private TeamHumanDirectory directory;
    private MatrixClient client;
    private TeamRoomAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        directory = mock(TeamHumanDirectory.class);
        client = mock(MatrixClient.class);
        authorizer = new TeamRoomAuthorizer(directory, List.of(client));
        when(directory.matrixUserIdOf(116L)).thenReturn(HUMAN);
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubMembers(String roomId, String... joined) {
        StringBuilder chunk = new StringBuilder();
        for (String userId : joined) {
            if (!chunk.isEmpty()) {
                chunk.append(',');
            }
            chunk.append("{\"state_key\":\"").append(userId)
                    .append("\",\"content\":{\"membership\":\"join\"}}");
        }
        when(client.roomMembers(roomId)).thenReturn(json("{\"chunk\":[" + chunk + "]}"));
    }

    @Test
    void allowsWhenUserIsJoinedMember() {
        stubMembers(ROOM, HUMAN, MANAGER);
        assertTrue(authorizer.canAccess(116L, ROOM));
    }

    @Test
    void deniesRoomWhereUserIsNotMember() {
        stubMembers(FOREIGN, OTHER, MANAGER);
        assertFalse(authorizer.canAccess(116L, FOREIGN));
    }

    @Test
    void deniesUnmappedUserWithoutQueryingRoom() {
        when(directory.matrixUserIdOf(999L)).thenReturn(null);
        assertFalse(authorizer.canAccess(999L, ROOM));
        verify(client, never()).roomMembers(anyString());
    }

    @Test
    void deniesBlankRoom() {
        assertFalse(authorizer.canAccess(116L, " "));
        assertFalse(authorizer.canAccess(116L, null));
    }

    @Test
    void failClosedWhenRoomUnreadable() {
        when(client.roomMembers(ROOM)).thenThrow(new IllegalStateException("Matrix GET -> 403"));
        assertFalse(authorizer.canAccess(116L, ROOM));
    }

    @Test
    void ignoresNonJoinMembership() {
        when(client.roomMembers(ROOM)).thenReturn(json("{\"chunk\":["
                + "{\"state_key\":\"" + HUMAN + "\",\"content\":{\"membership\":\"invite\"}}]}"));
        assertFalse(authorizer.canAccess(116L, ROOM));
    }

    @Test
    void scopeForBindsUserAccess() {
        stubMembers(ROOM, HUMAN, MANAGER);
        stubMembers(FOREIGN, OTHER, MANAGER);
        var scope = authorizer.scopeFor(116L);
        assertTrue(scope.test(ROOM));
        assertFalse(scope.test(FOREIGN));
    }

    @Test
    void cachesRoomMembersWithinTtl() {
        stubMembers(ROOM, HUMAN, MANAGER);
        assertTrue(authorizer.canAccess(116L, ROOM));
        assertTrue(authorizer.canAccess(116L, ROOM));
        verify(client, times(1)).roomMembers(ROOM);
    }

    @Test
    void usesAnyAvailableClientToVerifyMembership() {
        MatrixClient second = mock(MatrixClient.class);
        when(client.roomMembers(ROOM)).thenThrow(new IllegalStateException("Matrix GET -> 403"));
        StringBuilder chunk = new StringBuilder("{\"state_key\":\"" + HUMAN + "\",\"content\":{\"membership\":\"join\"}}");
        when(second.roomMembers(ROOM)).thenReturn(json("{\"chunk\":[" + chunk + "]}"));
        TeamRoomAuthorizer multi = new TeamRoomAuthorizer(directory, List.of(client, second));
        assertTrue(multi.canAccess(116L, ROOM));
    }
}