package com.h.backend.outbound.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FreeswitchEslClientFrameTest {

    @Test
    void contentLengthUsesUtf8BytesAndDoesNotConsumeTheNextFrame() throws Exception {
        String firstBody = "Event-Name: CUSTOM\n说明: 已接通";
        String secondBody = "true";
        String wire = frame("text/event-plain", firstBody) + frame("api/response", secondBody);
        var input = new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8));

        var first = FreeswitchEslClient.readMessage(input);
        var second = FreeswitchEslClient.readMessage(input);

        assertThat(first.body()).isEqualTo(firstBody);
        assertThat(second.body()).isEqualTo("true");
    }

    private static String frame(String type, String body) {
        return "Content-Type: " + type + "\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\n\n" + body;
    }
}
