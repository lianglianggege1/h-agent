package com.h.backend.document.application;

import com.h.backend.document.interfaces.tool.AgentScopeDocumentTool;
import com.h.backend.document.interfaces.tool.LangChain4jDocumentTool;
import com.h.capabilities.chat.domain.memory.ChatMemoryIdFactory;
import com.h.capabilities.common.exception.BusinessException;
import com.h.capabilities.document.application.DocumentWorkflow;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "AGUI_TEST_DB_URL", matches = ".+")
class SessionDocumentCanvasTest {
    @Test
    void readsTheSavedCorrectionForBothRuntimesOnlyInTheOwningSession() throws Exception {
        try (var db = DriverManager.getConnection(System.getenv("AGUI_TEST_DB_URL"),
                System.getenv("AGUI_TEST_DB_USERNAME"), System.getenv("AGUI_TEST_DB_PASSWORD"))) {
            db.setAutoCommit(false);
            var source = new SingleConnectionDataSource(db, true);
            var jdbc = JdbcClient.create(source);
            jdbc.sql("""
                    CREATE TEMP TABLE document_canvases (
                        user_id bigint, session_id text, revision bigint, content text,
                        source_task_id text, source_resource_id text, title text
                    )
                    """).update();
            jdbc.sql("""
                    CREATE TEMP TABLE chat_document_tasks (
                        user_id bigint, session_id text, id text, status text, updated_at timestamptz DEFAULT now()
                    )
                    """).update();
            jdbc.sql("""
                    INSERT INTO chat_document_tasks (user_id, session_id, id, status)
                    VALUES (11, 'thread', 'pending', 'WAITING_CONFIRMATION'), (11, 'other', 'other-file', 'CONFIRMED')
                    """).update();
            var json = JsonMapper.builder().build();
            var canvas = new SessionDocumentCanvas(source, json);
            assertEquals("", canvas.promptHint(11, "thread"));

            jdbc.sql("UPDATE chat_document_tasks SET status='CONFIRMED' WHERE id='pending'").update();
            assertTrue(canvas.promptHint(11, "thread").contains("parse_document(taskId=\"pending\")"));

            String correction = "修正后识别内容 " + "字".repeat(6000) + "末尾";
            jdbc.sql("INSERT INTO document_canvases VALUES (11,'thread',3,?,'pending','resource',NULL)")
                    .param(correction).update();
            String prompt = canvas.promptHint(11, "thread");
            assertTrue(prompt.contains("read_session_document(revision=3)"));
            assertFalse(prompt.contains("修正后识别内容"));
            assertEquals("", canvas.promptHint(12, "thread"));

            var langChain = new LangChain4jDocumentTool(mock(DocumentWorkflow.class), canvas, new ChatMemoryIdFactory(), json);
            var agentScope = new AgentScopeDocumentTool(mock(DocumentWorkflow.class), canvas, json);
            var context = RuntimeContext.builder().userId("11").sessionId("thread").build();
            var first = json.readTree(langChain.readSessionDocument("11:2:thread", 3L, null));
            assertEquals(3, first.path("revision").asInt());
            assertTrue(first.path("text").asString().startsWith("修正后识别内容"));
            int offset = first.path("nextOffset").asInt();
            var next = json.readTree(agentScope.readSessionDocument(context, 3L, offset));
            assertEquals(correction, first.path("text").asString() + next.path("text").asString());
            assertTrue(next.path("nextOffset").isNull());
            assertFalse(canvas.readForChat(12, "thread", null, null).contains("修正后识别内容"));
            assertFalse(canvas.readForChat(11, "other", null, null).contains("修正后识别内容"));

            jdbc.sql("UPDATE document_canvases SET revision=4, content='最新修正' WHERE session_id='thread'").update();
            assertThrows(BusinessException.class, () -> agentScope.readSessionDocument(context, 3L, offset));
            assertTrue(canvas.promptHint(11, "thread").contains("revision=4"));
            assertEquals("最新修正", json.readTree(agentScope.readSessionDocument(context, 4L, null)).path("text").asString());
            db.rollback();
        }
    }
}
