package com.h.backend.document.interfaces.web;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.document.application.SessionDocumentCanvas;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import com.h.capabilities.common.exception.BusinessException;
import com.h.capabilities.document.application.DocumentWorkflow;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "AGUI_TEST_DB_URL", matches = ".+")
class DocumentCanvasControllerTest {
    @Test
    void titlesAnExistingDraftAndUsesTheSavedNameForExports() throws Exception {
        try (var db = DriverManager.getConnection(System.getenv("AGUI_TEST_DB_URL"),
                System.getenv("AGUI_TEST_DB_USERNAME"), System.getenv("AGUI_TEST_DB_PASSWORD"))) {
            db.setAutoCommit(false);
            var dataSource = new SingleConnectionDataSource(db, true);
            var jdbc = JdbcClient.create(dataSource);
            jdbc.sql("""
                    CREATE TEMP TABLE document_canvases (
                        session_id varchar(255) PRIMARY KEY, user_id bigint, revision bigint, content text,
                        source_task_id text, source_resource_id text, updated_at timestamptz DEFAULT now()
                    )
                    """).update();
            String manuscript = "会议纪要\n讨论仓库设备巡检安排";
            jdbc.sql("INSERT INTO document_canvases VALUES ('thread',11,3,?,'source','resource',now())")
                    .param(manuscript).update();
            try (var stream = getClass().getResourceAsStream("/db/migration/V20261123_01__document_canvas_titles.sql")) {
                assertNotNull(stream);
                jdbc.sql(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).update();
            }

            var json = JsonMapper.builder().build();
            var sessions = mock(ChatSessionService.class);
            var documents = mock(DocumentWorkflow.class);
            var model = mock(ChatModel.class);
            var canvas = new SessionDocumentCanvas(dataSource, json);
            var controller = new DocumentCanvasController(dataSource, sessions, documents, canvas, model, json);
            var owner = new AuthUserPrincipal(11L, "fixture@example.com", "USER");

            assertNull(controller.read(owner, "thread").data().title());
            assertThrows(BusinessException.class,
                    () -> controller.generateTitle(owner, "thread", new DocumentCanvasController.GenerateTitle(2)));
            verifyNoInteractions(model);
            when(model.chat(any(SystemMessage.class), any(UserMessage.class))).thenAnswer(call -> {
                assertEquals(manuscript, call.getArgument(1, UserMessage.class).singleText());
                return ChatResponse.builder().aiMessage(AiMessage.from("### 标题： “仓库设备/巡检安排”\n忽略其他行")).build();
            });

            var named = controller.generateTitle(owner, "thread", new DocumentCanvasController.GenerateTitle(3)).data();
            assertEquals("仓库设备 巡检安排", named.title());
            assertEquals(4, named.revision());
            assertEquals("source", named.sourceTaskId());
            assertEquals(manuscript, named.content());
            assertEquals(named, controller.read(owner, "thread").data());

            String revised = manuscript + "\n每周五核对记录。";
            var edited = controller.edit(owner, "thread", new DocumentCanvasController.Edit(4, revised)).data();
            assertEquals(named.title(), edited.title());
            when(documents.generate(11L, "thread", revised, "docx", named.title(), List.of("source"))).thenReturn("{}");
            controller.export(owner, "thread", new DocumentCanvasController.Export(5, "docx", null));
            verify(documents).generate(11L, "thread", revised, "docx", named.title(), List.of("source"));

            doAnswer(call -> {
                controller.edit(owner, "thread", new DocumentCanvasController.Edit(5, "并发保存的新正文"));
                return ChatResponse.builder().aiMessage(AiMessage.from("历史旧标题")).build();
            }).when(model).chat(any(SystemMessage.class), any(UserMessage.class));
            assertThrows(BusinessException.class,
                    () -> controller.generateTitle(owner, "thread", new DocumentCanvasController.GenerateTitle(5)));
            var latest = controller.read(owner, "thread").data();
            assertEquals(6, latest.revision());
            assertEquals(named.title(), latest.title());
            assertEquals("并发保存的新正文", latest.content());
            db.rollback();
        }
    }
}
