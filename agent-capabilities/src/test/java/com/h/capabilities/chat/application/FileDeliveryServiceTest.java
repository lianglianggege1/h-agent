package com.h.capabilities.chat.application;

import com.h.capabilities.seam.DeliveredArtifact;
import com.h.capabilities.seam.DeliveryResult;
import com.h.capabilities.seam.port.ArtifactDeliveryPort;
import com.h.capabilities.seam.port.LiveProjectionPort;
import com.h.capabilities.chat.infrastructure.content.ResourceContentInspector;
import com.h.capabilities.chat.infrastructure.filesystem.AssistantFileStorage;
import com.h.capabilities.chat.infrastructure.filesystem.AssistantFileStorage.AssistantSessionFileStream;
import com.h.capabilities.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.capabilities.chat.infrastructure.storage.StoredResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileDeliveryServiceTest {

    @TempDir
    Path tempDir;

    private final ResourceContentInspector contentInspector = new ResourceContentInspector();
    private final ResourceContentPolicy contentPolicy = new ResourceContentPolicy();
    private final RecordingDelivery delivery = new RecordingDelivery();
    private final RecordingProjection projection = new RecordingProjection();

    private FileDeliveryService newService(AssistantFileStorage assistantFileStorage) {
        return new FileDeliveryService(
                assistantFileStorage,
                delivery,
                projection,
                contentInspector,
                contentPolicy
        );
    }

    @Test
    void doesNotSendACopyOfADocumentAlreadyDeliveredInTheSameTurn() {
        var files = new AssistantFileStorage(tempDir.resolve("files"), 1024 * 1024);
        files.write("1:22:session-1", "/报告.md", "a rewritten copy");
        var service = newService(files);
        var documents = mock(com.h.capabilities.document.infrastructure.persistence.DocumentRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "documentTasks", documents);
        when(documents.activeAnchor(1L, "session-1")).thenReturn(42L);
        var task = new com.h.capabilities.document.domain.DocumentTask();
        task.setAnchorMessageId(42L);
        task.setKind("GENERATE");
        task.setStatus("COMPLETED");
        task.setFileName("报告.md");
        task.setOutputResourceId("generated-document");
        when(documents.list(1L, "session-1")).thenReturn(List.of(task));

        assertTrue(service.send("1:22:session-1", "/报告.md", "", "", "")
                .contains("generated-document"));
        assertTrue(delivery.calls.isEmpty(), "复用已交付文档时不得再次交付");
    }

    @Test
    void shouldSendSessionFileToCurrentChatStream() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        FileDeliveryService service = newService(assistantFileStorage);
        String memoryId = "1:22:session-1";
        assistantFileStorage.write(memoryId, "/deck.pptx", "ppt-bytes");

        // 交付边界 fake：模拟 ChatArtifactDeliveryAdapter 语义——保存命令后
        // 构造 DeliveredArtifact，onAttached 与消息挂接同事务同步执行。
        delivery.stored = new StoredResource(
                "resource-1",
                "OBJECT_STORAGE",
                "generated-files/2026/07/07/resource-1.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "generated-resource-1.pptx",
                9L,
                null,
                null
        );

        String result = service.send(memoryId, "/deck.pptx", "客户方案.pptx", null, null);

        assertEquals("文件已发送到聊天中。", result);

        // 交付参数必须与原 appendResourceMessage 契约一致：userId/scope/文案/类型/文件名/MIME
        assertEquals(1, delivery.calls.size());
        RecordingDelivery.Call call = delivery.calls.getFirst();
        assertEquals(1L, call.userId());
        assertEquals("session-1", call.scopeId());
        assertEquals("已发送文件：客户方案.pptx", call.text());
        assertEquals("FILE", call.resourceType());
        assertEquals("客户方案.pptx", call.fileName());
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", call.mimeType());

        // onAttached 在消息挂接之前（同事务内）执行，回调对象即交付结果
        assertEquals(List.of("onAttached", "appended"), delivery.events);
        assertSame(delivery.result.artifact(), delivery.attachedArtifact);
        assertEquals("resource-1", delivery.attachedArtifact.resourceId());
        assertEquals("FILE", delivery.attachedArtifact.resourceType());
        assertEquals("/api/chat/resources/resource-1/content", delivery.attachedArtifact.viewUrl());
        assertEquals("/api/chat/resources/resource-1/download", delivery.attachedArtifact.downloadUrl());
        assertEquals("客户方案.pptx", delivery.attachedArtifact.fileName());
        assertEquals(9L, delivery.attachedArtifact.fileSize());
        assertEquals("OBJECT_STORAGE", delivery.attachedArtifact.storageType());
        assertEquals("generated-files/2026/07/07/resource-1.pptx", delivery.attachedArtifact.storageKey());

        // 交付返回（挂接事务提交）后，投影必须收到同一 memoryId 与同一 handle
        assertEquals(1, projection.published.size());
        assertEquals(memoryId, projection.published.getFirst().memoryId());
        assertSame(delivery.result.projectionHandle(), projection.published.getFirst().handle());

        // 写入命令必须是流式形态：工作文件不再复制完整 byte array（计划任务 3）
        ResourceSaveCommand saveCommand = call.command();
        assertEquals("FILE", saveCommand.resourceType());
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", saveCommand.mimeType());
        assertEquals("pptx", saveCommand.extension());
        assertNull(saveCommand.content(), "工作文件必须以流式进入命令，不得整读为 byte[]");
        assertEquals(9L, saveCommand.declaredSize(), "工作文件已知大小作为 declaredSize");
    }

    @Test
    void shouldPropagateAttachmentFailureAndSkipStreamEventPublish() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        FileDeliveryService service = newService(assistantFileStorage);
        String memoryId = "1:22:session-1";
        assistantFileStorage.write(memoryId, "/deck.pptx", "ppt-bytes");

        delivery.stored = new StoredResource(
                "resource-1", "OBJECT_STORAGE", "key-1", "application/octet-stream", "deck.pptx", 9L, null, null);
        IllegalStateException boom = new IllegalStateException("消息挂接失败");
        delivery.attachFailure = boom;

        // 挂接失败必须原样上抛（交付事务 rollback 补偿对象），不得返回错误文案。
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.send(memoryId, "/deck.pptx", "客户方案.pptx", null, null));

        assertSame(boom, thrown);
        assertEquals(List.of("onAttached"), delivery.events, "挂接失败发生在 onAttached 之后、消息挂接完成之前");
        assertTrue(projection.published.isEmpty(), "挂接失败时流事件不得发布");
    }

    @Test
    void shouldRejectOversizedSessionFileWithoutTouchingDelivery() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 8);
        FileDeliveryService service = newService(assistantFileStorage);
        String memoryId = "1:22:session-1";
        assistantFileStorage.write(memoryId, "/deck.pptx", "ppt-bytes");

        String result = service.send(memoryId, "/deck.pptx", "客户方案.pptx", null, null);

        assertEquals("Error: File exceeds max readable size: /deck.pptx", result);
        assertTrue(delivery.calls.isEmpty(), "超限文件不得触碰交付端口");
    }

    @Test
    void shouldRejectSendingFilesOutsideSessionDirectory() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        FileDeliveryService service = newService(assistantFileStorage);

        assertEquals(
                "Error: Path traversal is not allowed",
                service.send("1:22:session-1", "/../secret.txt", "secret.txt", null, null)
        );
        assertTrue(delivery.calls.isEmpty());
    }

    // ------------------------------------------------------------------
    // 内容安全（新计划 §6.3 / §10 任务 4）：Agent 模型文件属于不可信输入，
    // 保存前必须经签名校验；模型声明的 MIME 只是提示（拒绝方案 10）。
    // ------------------------------------------------------------------

    @Test
    void shouldRejectFileWhoseDeclaredMimeConflictsWithContent() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        FileDeliveryService service = newService(assistantFileStorage);
        String memoryId = "1:22:session-1";
        // 模型声明 image/png，但文件内容是纯文本：签名校验拒绝
        assistantFileStorage.write(memoryId, "/fake.png", "plain text, definitely not a png");

        String result = service.send(memoryId, "/fake.png", "fake.png", "image/png", null);

        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("签名"));
        assertTrue(delivery.calls.isEmpty(), "签名校验失败不得触碰交付端口");
    }

    @Test
    void shouldRejectActiveContentDelivery() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        FileDeliveryService service = newService(assistantFileStorage);
        String memoryId = "1:22:session-1";
        // 伪装成图片的 HTML 主动内容：无论声明什么都拒绝（计划 §6.3）
        assistantFileStorage.write(memoryId, "/evil.png", "<html><script>alert(1)</script></html>");

        String result = service.send(memoryId, "/evil.png", "evil.png", "image/png", null);

        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("主动内容"));
        assertTrue(delivery.calls.isEmpty(), "主动内容不得触碰交付端口");
    }

    @Test
    void shouldDeliverRealPngFileWithMatchingDeclaredMime() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        FileDeliveryService service = newService(assistantFileStorage);
        String memoryId = "1:22:session-1";
        // 真实 PNG 魔数二进制内容：直接写入会话目录（write() 的字符串 API 会做
        // UTF-8 重编码破坏二进制），校验通过后保存流必须完整回放（不丢头字节）
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        try {
            Path sessionDir = tempDir.resolve("assistant-files").resolve("1").resolve("session-1");
            java.nio.file.Files.createDirectories(sessionDir);
            java.nio.file.Files.write(sessionDir.resolve("icon.png"), png);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        delivery.stored = new StoredResource(
                "resource-9", "OBJECT_STORAGE", "key-9", "image/png", "icon.png", 12L, null, null);

        String result = service.send(memoryId, "/icon.png", "icon.png", "image/png", null);

        assertEquals("文件已发送到聊天中。", result);
        assertEquals(1, delivery.calls.size());
        ResourceSaveCommand saveCommand = delivery.calls.getFirst().command();
        try (var in = saveCommand.openContentStream()) {
            assertArrayEquals(png, in.readAllBytes(), "保存流必须完整回放 PNG 内容（含已校验头字节）");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldCloseSourceStreamWhenInspectionFails() {
        // 审查修复第 4 项：inspect 抛 IOException 返回 Error 文案前必须关闭
        // file.stream()，否则会话文件句柄泄漏（fd 泄漏）。
        AssistantFileStorage assistantFileStorage = mock(AssistantFileStorage.class);
        TrackingInputStream trackingStream = new TrackingInputStream();
        when(assistantFileStorage.openSessionFileStream("1:22:session-1", "/broken.png"))
                .thenReturn(new AssistantSessionFileStream(
                        true, null, "/broken.png", "broken.png", "image/png", 10L, trackingStream));
        FileDeliveryService service = newService(assistantFileStorage);

        String result = service.send("1:22:session-1", "/broken.png", "broken.png", "image/png", null);

        assertTrue(result.startsWith("Error: 读取文件失败"));
        assertTrue(trackingStream.closed, "inspect 失败时源流必须被关闭（fd 泄漏修复）");
        assertTrue(delivery.calls.isEmpty(), "读取失败不得触碰交付端口");
    }

    /** read 即抛 IOException 的跟踪流：验证 inspect 失败路径的流关闭（TrackingStream 风格）。 */
    private static final class TrackingInputStream extends java.io.InputStream {

        boolean closed = false;

        @Override
        public int read() throws java.io.IOException {
            throw new java.io.IOException("simulated read failure");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** 交付实现私有的投影凭据：能力层不解释内容，LiveProjectionPort 必须原样收到同一对象。 */
    private record ProjectionHandle(String resourceId) {
    }

    /**
     * 与 ChatArtifactDeliveryAdapter 产品语义对齐的 fake：
     * 保存命令 → 构造 DeliveredArtifact → onAttached.accept（挂接事务内）→
     * 消息挂接返回 projectionHandle；attachFailure 模拟挂接失败原样上抛。
     * 不消费保存流，保留调用方对回放流的断言能力。
     */
    private static final class RecordingDelivery implements ArtifactDeliveryPort {

        record Call(
                long userId,
                String scopeId,
                ResourceSaveCommand command,
                String resourceType,
                String fileName,
                String mimeType,
                String text
        ) {
        }

        StoredResource stored = new StoredResource(
                "resource-default", "OBJECT_STORAGE", "key-default", "application/octet-stream", "file.bin", 0L, null, null);
        RuntimeException attachFailure;
        final List<Call> calls = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        DeliveredArtifact attachedArtifact;
        DeliveryResult result;

        @Override
        public DeliveryResult deliver(
                long userId,
                String scopeId,
                ResourceSaveCommand command,
                String resourceType,
                String fileName,
                String mimeType,
                String text,
                Consumer<DeliveredArtifact> onAttached) {
            calls.add(new Call(userId, scopeId, command, resourceType, fileName, mimeType, text));
            DeliveredArtifact artifact = new DeliveredArtifact(
                    stored.id(),
                    resourceType,
                    "/api/chat/resources/" + stored.id() + "/content",
                    "/api/chat/resources/" + stored.id() + "/download",
                    fileName,
                    mimeType,
                    stored.fileSize(),
                    stored.width(),
                    stored.height(),
                    stored.storageType(),
                    stored.storageKey());
            events.add("onAttached");
            onAttached.accept(artifact);
            attachedArtifact = artifact;
            if (attachFailure != null) {
                throw attachFailure;
            }
            events.add("appended");
            result = new DeliveryResult(artifact, new ProjectionHandle(stored.id()));
            return result;
        }
    }

    /** 记录 (memoryId, projectionHandle) 的实时投影 fake，替代原 ChatStreamEventBridge 捕获。 */
    private static final class RecordingProjection implements LiveProjectionPort {

        record Published(String memoryId, Object handle) {
        }

        final List<Published> published = new ArrayList<>();

        @Override
        public void publish(String memoryId, Object projectionHandle) {
            published.add(new Published(memoryId, projectionHandle));
        }
    }
}