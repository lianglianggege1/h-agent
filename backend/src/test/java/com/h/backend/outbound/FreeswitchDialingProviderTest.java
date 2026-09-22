package com.h.backend.outbound;

import com.h.backend.outbound.infrastructure.DialingProvider;
import com.h.backend.outbound.infrastructure.FreeswitchDialingProvider;
import com.h.backend.outbound.infrastructure.FreeswitchEslClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FreeswitchDialingProviderTest {

    @Test
    void audioForkUsesSymbolicSampleRateAndMetadataArgument() {
        FreeswitchEslClient esl = spy(new FreeswitchEslClient("127.0.0.1", 8021, "secret"));
        doReturn(new FreeswitchEslClient.EslResponse("+OK"))
                .when(esl).sendApi("uuid_audio_fork", "call-1 start ws://worker/media mono 16k {}");

        assertThat(esl.startAudioFork("call-1", "ws://worker/media").ok()).isTrue();

        verify(esl).sendApi("uuid_audio_fork", "call-1 start ws://worker/media mono 16k {}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void backgroundOriginateFailureIsCorrelatedByJobUuid() {
        FreeswitchEslClient esl = mock(FreeswitchEslClient.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(esl.isConnected()).thenReturn(true);
        when(esl.originate("call-1", "1000", "127.0.0.1", "ws://media"))
                .thenReturn(new FreeswitchEslClient.EslResponse(
                        "Reply-Text: +OK Job-UUID: 11111111-1111-1111-1111-111111111111"));
        ArgumentCaptor<Consumer<FreeswitchEslClient.EslEvent>> subscriber =
                ArgumentCaptor.forClass(Consumer.class);
        FreeswitchDialingProvider provider = new FreeswitchDialingProvider(
                esl, "127.0.0.1", publisher);

        var result = provider.originate("call-1", "1000", "127.0.0.1", "ws://media");
        verify(esl).subscribeAll(subscriber.capture());
        subscriber.getValue().accept(new FreeswitchEslClient.EslEvent("BACKGROUND_JOB", Map.of(
                "Job-UUID", "11111111-1111-1111-1111-111111111111",
                "_body", "-ERR USER_BUSY"
        )));

        assertThat(result.status()).isEqualTo(DialingProvider.DialStatus.ORIGINATED);
        assertThat(result.jobId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(provider.query("call-1")).isEqualTo(DialingProvider.RemoteCallState.UNKNOWN);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(
                new FreeswitchDialingProvider.CallFact("call-1", "DIAL_FAILED", "-ERR USER_BUSY"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulBackgroundJobPublishesOriginateSettled() {
        FreeswitchEslClient esl = mock(FreeswitchEslClient.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(esl.isConnected()).thenReturn(true);
        when(esl.originate("call-2", "1001", "127.0.0.1", "ws://media"))
                .thenReturn(new FreeswitchEslClient.EslResponse(
                        "Reply-Text: +OK Job-UUID: 22222222-2222-2222-2222-222222222222"));
        ArgumentCaptor<Consumer<FreeswitchEslClient.EslEvent>> subscriber =
                ArgumentCaptor.forClass(Consumer.class);
        FreeswitchDialingProvider provider = new FreeswitchDialingProvider(
                esl, "127.0.0.1", publisher);

        provider.originate("call-2", "1001", "127.0.0.1", "ws://media");
        verify(esl).subscribeAll(subscriber.capture());
        subscriber.getValue().accept(new FreeswitchEslClient.EslEvent("BACKGROUND_JOB", Map.of(
                "Job-UUID", "22222222-2222-2222-2222-222222222222",
                "_body", "+OK 7b8efdf2"
        )));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(
                new FreeswitchDialingProvider.CallFact("call-2", "ORIGINATE_SETTLED", "+OK 7b8efdf2"));
    }
}
