package com.h.otheragents.a2a.document;

import com.h.capabilities.common.exception.BusinessException;
import com.h.capabilities.document.application.DocumentWorkflow;
import com.h.capabilities.document.domain.DocumentTask;
import com.h.capabilities.document.infrastructure.parsing.LocalDocumentParser;
import org.springframework.jdbc.core.simple.JdbcClient;

record RemoteDocumentWork(DocumentWorkflow workflow, JdbcClient jdbc, LocalDocumentParser parser)
        implements AutoCloseable {
    DocumentTask find(long owner, String id) {
        String session = jdbc.sql("SELECT session_id FROM chat_document_tasks WHERE user_id = ? AND id = ?")
                .params(owner, id).query(String.class).optional()
                .orElseThrow(() -> new BusinessException(40404, "文档任务不存在"));
        return workflow.require(owner, session, id);
    }

    @Override
    public void close() { parser.close(); }
}
