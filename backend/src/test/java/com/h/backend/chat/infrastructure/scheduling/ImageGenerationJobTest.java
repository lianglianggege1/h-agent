package com.h.backend.chat.infrastructure.scheduling;

import com.h.backend.chat.application.impl.ImageGenerationTaskService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageGenerationJobTest {
    @Test
    void slowImageRequestDoesNotBlockSchedulerOrQueueAnotherLocalWorker() throws Exception {
        var tasks = mock(ImageGenerationTaskService.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(tasks.processNext()).thenAnswer(call -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return true;
        });
        var job = new ImageGenerationJob(tasks, 3600);
        try {
            job.generateNext();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(1, release.getCount());
            job.generateNext();
            job.recoverStale();
            verify(tasks, times(1)).processNext();
            verify(tasks).recoverStale(3600);
        } finally {
            release.countDown();
            job.stop();
        }
    }
}
