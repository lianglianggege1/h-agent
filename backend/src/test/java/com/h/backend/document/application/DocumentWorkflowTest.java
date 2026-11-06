package com.h.backend.document.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.h.backend.chat.application.*;
import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMessageMapper;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.document.domain.DocumentTask;
import com.h.backend.document.infrastructure.config.DocumentProperties;
import com.h.backend.document.infrastructure.generation.DocumentGenerator;
import com.h.backend.document.infrastructure.parsing.LocalDocumentParser;
import com.h.backend.document.infrastructure.persistence.DocumentRepository;
import com.h.backend.document.infrastructure.provider.ModelBestDocumentClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Consumer;

class DocumentWorkflowTest {
    final DocumentRepository repository = mock(DocumentRepository.class);
    final DocumentAssets assets = mock(DocumentAssets.class);
    final DocumentProperties config = new DocumentProperties();
    final ModelBestDocumentClient cloud = mock(ModelBestDocumentClient.class);
    final DocumentTask task = new DocumentTask();
    final ObjectMapper json = new ObjectMapper();
    final DocumentWorkflow workflow =
            new DocumentWorkflow(
                    repository,
                    assets,
                    new LocalDocumentParser(config),
                    config,
                    cloud,
                    mock(DocumentGenerator.class),
                    mock(ChatSessionMessageMapper.class),
                    mock(ResourceWriteCoordinator.class),
                    mock(ChatSessionService.class),
                    new ChatResourceUrls(""),
                    json,
                    new ResourceContentInspector(),
                    new ResourceContentPolicy());

    @BeforeEach
    void setup() {
        task.setId("task");
        task.setUserId(1L);
        task.setSessionId("chat");
        task.setResourceId("resource");
        task.setStatus("WAITING_CONFIRMATION");
        task.setRevision(7);
        task.setAnchorMessageId(11L);
        var page = new DocumentTask.Page();
        page.setNumber(1);
        page.setQuality("LOW");
        page.setLocalText("LOCAL_SECRET");
        var block = new DocumentTask.Block();
        block.setId("p1-b0");
        block.setText("OCR_SECRET");
        block.setVerdict("scored");
        block.setScore(.2);
        page.getBlocks().add(block);
        task.getPages().add(page);
        when(repository.find(1L, "chat", "task")).thenReturn(task);
        when(repository.change(eq(1L), eq("chat"), eq("task"), any()))
                .thenAnswer(
                        call -> {
                            Consumer<DocumentTask> mutation = call.getArgument(3);
                            mutation.accept(task);
                            task.setRevision(task.getRevision() + 1);
                            return task;
                        });
    }

    byte[] recognitionImage() throws Exception {
        var image = new java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
        return ModelBestDocumentClient.png(image);
    }

    @Test
    void ocrCompletesAndExposesTextWithoutCallingJev() throws Exception {
        task.setStatus("OCR_RUNNING");
        var page = task.getPages().getFirst();
        page.setFileId("file1");
        page.setJobId("job1");
        page.getBlocks().clear();
        when(assets.read(1L, "chat", "resource")).thenReturn(recognitionImage());
        when(cloud.available()).thenReturn(true);
        when(repository.checkpoint(task)).thenReturn(true);
        when(cloud.query("job1")).thenReturn(json.readTree("""
            {"status":"Success","pages_done":1,"pages_total":1,"pages":[{"ocr":{
            "image_width":100,"image_height":100,"blocks":[{"label":"text",
            "content":"recognized text","status":"ok","bbox":[0,0,100,100]}]}}]}
            """));
        workflow.process(task);
        workflow.process(task);
        assertEquals("RECOGNIZED", task.getStatus());
        assertTrue(task.released());
        assertTrue(workflow.toolView(task, 0).contains("recognized text"));
        verify(cloud, never()).verify(any(), any());
        assertEquals("QUEUED", task.getResumeStatus());
    }

    @Test
    void requestedReviewIsSeparateAndItsFailureDoesNotRevokeRecognition() throws Exception {
        task.setStatus("RECOGNIZED");
        var sourceBlock = task.getPages().getFirst().getBlocks().getFirst();
        sourceBlock.setVerdict("UNVERIFIED");
        sourceBlock.setRegions(List.of(List.of(0d, 0d, 100d, 100d)));
        sourceBlock.setImageWidth(100);
        sourceBlock.setImageHeight(100);
        when(cloud.available()).thenReturn(true);
        when(repository.insert(any())).thenAnswer(call -> call.getArgument(0));
        var response = json.readTree(workflow.requestReview(1L, "chat", "task"));
        var capture = org.mockito.ArgumentCaptor.forClass(DocumentTask.class);
        verify(repository).insert(capture.capture());
        var audit = capture.getValue();
        assertEquals("REVIEW", audit.getKind());
        assertNotEquals(task.getId(), audit.getId());
        assertEquals(List.of(task.getId()), audit.getSources());
        assertEquals("VERIFYING", response.path("status").asText());
        assertNotSame(sourceBlock, audit.getPages().getFirst().getBlocks().getFirst());
        when(assets.read(1L, "chat", "resource")).thenReturn(recognitionImage());
        when(cloud.verify(any(), any())).thenThrow(new ModelBestDocumentClient.ProviderFailure(
                "content_policy_violation", false));
        when(repository.checkpoint(audit)).thenReturn(true);
        workflow.process(audit);
        assertEquals("FAILED", audit.getStatus());
        assertEquals("RECOGNIZED", task.getStatus());
        assertTrue(workflow.toolView(task, 0).contains("OCR_SECRET"));
        assertEquals("UNVERIFIED", sourceBlock.getVerdict());
        verify(cloud, never()).upload(any());
        verify(cloud, never()).submit(any(), any());
    }

    @Test
    void lowReviewStillProvidesManualCorrectionWithoutChangingOriginalText() throws Exception {
        task.setStatus("RECOGNIZED");
        var block = task.getPages().getFirst().getBlocks().getFirst();
        block.setRegions(List.of(List.of(0d, 0d, 100d, 100d)));
        block.setImageWidth(100);
        block.setImageHeight(100);
        when(cloud.available()).thenReturn(true);
        when(repository.insert(any())).thenAnswer(call -> call.getArgument(0));
        workflow.requestReview(1L, "chat", "task");
        var capture = org.mockito.ArgumentCaptor.forClass(DocumentTask.class);
        verify(repository).insert(capture.capture());
        var audit = capture.getValue();
        when(repository.find(1L, "chat", audit.getId())).thenReturn(audit);
        when(repository.change(eq(1L), eq("chat"), eq(audit.getId()), any())).thenAnswer(call -> {
            Consumer<DocumentTask> mutation = call.getArgument(3);
            mutation.accept(audit);
            audit.setRevision(audit.getRevision() + 1);
            return audit;
        });
        when(assets.read(1L, "chat", "resource")).thenReturn(recognitionImage());
        when(repository.checkpoint(audit)).thenReturn(true);
        when(cloud.verify(any(), any())).thenReturn(json.readTree("{\"verdict\":\"scored\",\"prob_yes\":0.2}"));
        workflow.process(audit);
        workflow.process(audit);
        assertEquals("WAITING_CONFIRMATION", audit.getStatus());
        var view = json.valueToTree(workflow.review(1L, "chat", audit.getId()));
        assertTrue(view.path("pages").path(0).path("blocks").path(0).path("canAccept").asBoolean());
        workflow.decide(1L, "chat", audit.getId(), new DocumentWorkflow.Decision(audit.getRevision(),
                List.of(new DocumentWorkflow.BlockDecision(block.getId(), "CORRECT", "manual correction"))));
        assertEquals("CONFIRMED", audit.getStatus());
        assertTrue(workflow.toolView(audit, 0).contains("manual correction"));
        assertTrue(workflow.toolView(task, 0).contains("OCR_SECRET"));
        assertFalse(workflow.toolView(task, 0).contains("manual correction"));
    }

    @Test
    void localRecognitionCanBeReviewedWithoutUploadingOrReRecognizing() throws Exception {
        task.setStatus("GOOD");
        var page = task.getPages().getFirst();
        page.setQuality("GOOD");
        page.getBlocks().clear();
        when(cloud.available()).thenReturn(true);
        when(repository.insert(any())).thenAnswer(call -> call.getArgument(0));
        workflow.requestReview(1L, "chat", "task");
        var capture = org.mockito.ArgumentCaptor.forClass(DocumentTask.class);
        verify(repository).insert(capture.capture());
        var audit = capture.getValue();
        when(assets.read(1L, "chat", "resource")).thenReturn(recognitionImage());
        when(cloud.verify(any(), any())).thenReturn(json.readTree("{\"verdict\":\"scored\",\"prob_yes\":0.99}"));
        workflow.process(audit);
        workflow.process(audit);
        assertEquals("VERIFIED", audit.getStatus());
        assertEquals("GOOD", task.getStatus());
        assertTrue(workflow.toolView(audit, 0).contains("LOCAL_SECRET"));
        verify(cloud, never()).upload(any());
        verify(cloud, never()).submit(any(), any());
        verify(cloud, never()).query(any());
    }

    @Test
    void recognizedTextCanBeUsedForDocumentGenerationWithoutReview() {
        task.setStatus("RECOGNIZED");
        when(repository.insert(any())).thenAnswer(call -> call.getArgument(0));
        String result = workflow.generate(1L, "chat", "summary", "md", "summary", List.of("task"));
        assertEquals("GENERATING", json.readTree(result).path("status").asText());
        verifyNoInteractions(cloud);
    }

    @Test
    void unverifiedTextNeverAppearsInAnyAgentView() {
        for (String state :
                List.of(
                        "LOW",
                        "PARSING",
                        "OCR_RUNNING",
                        "VERIFYING",
                        "WAITING_CONFIRMATION",
                        "VERIFICATION_FAILED",
                        "FAILED",
                        "CANCELLED",
                        "SUPERSEDED")) {
            task.setStatus(state);
            String result = workflow.verify(1L, "chat", "task");
            assertFalse(result.contains("OCR_SECRET"), state);
            assertFalse(result.contains("LOCAL_SECRET"), state);
        }
    }

    @Test
    void acceptingScoredLowBlockReleasesAndQueuesOnce() {
        var decision =
                new DocumentWorkflow.Decision(
                        7, List.of(new DocumentWorkflow.BlockDecision("p1-b0", "ACCEPT", null)));
        workflow.decide(1L, "chat", "task", decision);
        assertEquals("CONFIRMED", task.getStatus());
        assertEquals("QUEUED", task.getResumeStatus());
        assertTrue(workflow.verify(1L, "chat", "task").contains("OCR_SECRET"));
        task.setResumeStatus("DONE");
        workflow.decide(1L, "chat", "task", decision);
        assertEquals("DONE", task.getResumeStatus());
    }

    @Test
    void invalidScoresAndStaleRevisionCannotBeAccepted() {
        task.getPages().getFirst().getBlocks().getFirst().setVerdict("forward_error");
        task.setStatus("VERIFICATION_FAILED");
        assertThrows(
                RuntimeException.class,
                () ->
                        workflow.decide(
                                1L,
                                "chat",
                                "task",
                                new DocumentWorkflow.Decision(
                                        7,
                                        List.of(
                                                new DocumentWorkflow.BlockDecision(
                                                        "p1-b0", "ACCEPT", null)))));
        assertFalse(task.released());
        assertThrows(
                RuntimeException.class,
                () ->
                        workflow.decide(
                                1L,
                                "chat",
                                "task",
                                new DocumentWorkflow.Decision(
                                        6,
                                        List.of(
                                                new DocumentWorkflow.BlockDecision(
                                                        "p1-b0", "CORRECT", "用户核对文本")))));
    }

    @Test
    void manualTextCanResolveFailedVerificationAndCancelOnlyPreventsContinuation() {
        task.setStatus("VERIFICATION_FAILED");
        task.getPages().getFirst().getBlocks().getFirst().setVerdict("forward_error");
        workflow.action(1L, "chat", "task", 7, "CANCEL", null, false);
        workflow.decide(
                1L,
                "chat",
                "task",
                new DocumentWorkflow.Decision(
                        8,
                        List.of(
                                new DocumentWorkflow.BlockDecision(
                                        "p1-b0", "CORRECT", "用户核对后的完整文本"))));
        assertTrue(task.released());
        assertEquals("CANCELLED", task.getResumeStatus());
        String result = workflow.verify(1L, "chat", "task");
        assertTrue(result.contains("用户核对后的完整文本"));
        assertFalse(result.contains("OCR_SECRET"));
    }

    @Test
    void wholePageJevCheckDoesNotDuplicateReleasedTextAndLowScoreRequiresReview() {
        var page = task.getPages().getFirst();
        page.getBlocks().getFirst().setScore(.99);
        var coverage = new DocumentTask.Block();
        coverage.setId("p1-coverage");
        coverage.setCoverageCheck(true);
        coverage.setText("OCR_SECRET");
        coverage.setVerdict("scored");
        coverage.setScore(.2);
        page.getBlocks().add(coverage);
        var review = json.readTree(json.writeValueAsString(workflow.review(1L, "chat", "task")));
        assertEquals(1, review.path("pages").path(0).path("blocks").size());
        assertEquals(
                "p1-coverage",
                review.path("pages").path(0).path("blocks").path(0).path("id").asText());
        assertFalse(workflow.toolView(task, null).contains("OCR_SECRET"));
        workflow.decide(
                1L,
                "chat",
                "task",
                new DocumentWorkflow.Decision(
                        7,
                        List.of(
                                new DocumentWorkflow.BlockDecision(
                                        "p1-coverage", "ACCEPT", null))));
        var text = json.readTree(workflow.toolView(task, null)).path("text").asText();
        assertEquals(1, text.split("OCR_SECRET", -1).length - 1);
        assertEquals("11", workflow.summary(task).get("anchorMessageId"));
        assertEquals("chat", workflow.summary(task).get("sessionId"));
    }

    @Test
    void generationRefusesPendingSourceBeforeAnySideEffect() {
        assertThrows(
                RuntimeException.class,
                () -> workflow.generate(1L, "chat", "内容", "docx", "report", List.of("task")));
        verify(repository, never()).insert(any());
    }

    @Test
    void ocrIsAvailableByDefaultWithoutSessionSwitch() {
        task.setStatus("LOW");
        when(cloud.available()).thenReturn(true);
        String result = workflow.ocr(1L, "chat", "task");
        assertTrue(result.contains("QUEUED"));
        assertFalse(result.contains("NEEDS_EXTERNAL_CONSENT"));
    }

    @Test
    void lowLocalParseAutomaticallyQueuesOcr() throws Exception {
        task.setStatus("PARSING");
        task.setStartPage(1);
        var image =
                new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        when(assets.read(1L, "chat", "resource")).thenReturn(output.toByteArray());
        when(repository.list(1L, "chat")).thenReturn(List.of());
        when(cloud.available()).thenReturn(true);
        workflow.process(task);
        assertEquals("QUEUED", task.getStatus());
        assertEquals("NONE", task.getResumeStatus());
    }

    @Test
    void retrySupersedesOldVersionAndResetsOnlyProblemPages() {
        when(repository.insert(any())).thenAnswer(call -> call.getArgument(0));
        // The old card must stop accepting decisions even before the candidate OCR starts.
        assertThrows(
                RuntimeException.class,
                () -> workflow.action(1L, "chat", "task", 7, "REOCR", "竖排", false));
        assertEquals("WAITING_CONFIRMATION", task.getStatus());
    }
}
