package com.h.capabilities.chart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.h.matrixteam.MatrixClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamChartRoomTest {
    private static final String ROOM = "!team:matrix.local";
    private static final String EVENT = TeamChartRoom.EVENT_TYPE;
    private final MatrixClient publisher = mock(MatrixClient.class);
    private final MatrixClient reader = mock(MatrixClient.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void anotherMemberCanReadAnInlineChartFromTheSameRoom() throws Exception {
        var chart = chart(4);
        var event = publish(chart);
        assertEquals(chart.id(), event.path("chart").path("id").asText());
        when(reader.messages(ROOM, 50, null, EVENT)).thenReturn(history(event));
        assertEquals(json.valueToTree(chart), json.readTree(new TeamChartRoom(reader, ROOM).read(chart.id())));
        verifyNoInteractionsForOtherRooms();
    }

    @Test
    void storesLargeNativeOptionsAsMediaAndReadsThemBackByChartId() throws Exception {
        var chart = chart(42_000);
        String url = "mxc://matrix.local/chart";
        when(publisher.upload(eq(chart.id() + ".json"), eq("application/json"), any(byte[].class))).thenReturn(url);
        var event = publish(chart);
        assertEquals(url, event.path("chartUrl").asText());
        assertTrue(event.path("chart").isMissingNode());
        when(reader.download(eq(url), anyInt())).thenReturn(json.writeValueAsBytes(chart));
        when(reader.messages(ROOM, 50, null, EVENT)).thenReturn(history(event));
        assertEquals(json.valueToTree(chart), json.readTree(new TeamChartRoom(reader, ROOM).read(chart.id())));
        verifyNoInteractionsForOtherRooms();
    }

    private ObjectNode publish(ChatChart chart) {
        when(publisher.sendEvent(eq(ROOM), eq(EVENT), any(ObjectNode.class))).thenReturn("$chart");
        new TeamChartRoom(publisher, ROOM).publish(chart);
        var content = ArgumentCaptor.forClass(ObjectNode.class);
        verify(publisher).sendEvent(eq(ROOM), eq(EVENT), content.capture());
        return content.getValue();
    }

    private ObjectNode history(ObjectNode event) {
        var history = json.createObjectNode();
        history.putArray("chunk").addObject().put("type", EVENT).set("content", event);
        return history;
    }

    private ChatChart chart(int textSize) {
        return new ChatChart("5f91ce40-f2f9-448b-a876-e1102959e468", "用量对比", null,
                Map.of("series", List.of(Map.of("type", "bar", "data", List.of(1, 2))),
                        "title", Map.of("text", "x".repeat(textSize))),
                null, List.of(), null);
    }

    private void verifyNoInteractionsForOtherRooms() {
        verify(reader, never()).messages(argThat(room -> !ROOM.equals(room)), anyInt(), nullable(String.class), anyString());
    }
}
