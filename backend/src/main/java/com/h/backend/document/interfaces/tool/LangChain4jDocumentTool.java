package com.h.backend.document.interfaces.tool;

import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.document.application.DocumentWorkflow;

import dev.langchain4j.agent.tool.*;

import org.springframework.stereotype.Component;

import java.util.List;

/** LangChain4j 工具适配器，从框架注入的 memoryId 解析身份，调用共享文档应用服务。 */
@Component
public class LangChain4jDocumentTool {
    private final DocumentWorkflow workflow;
    private final ChatMemoryIdFactory memoryIds;
    private final tools.jackson.databind.ObjectMapper json;

    public LangChain4jDocumentTool(
            DocumentWorkflow workflow,
            ChatMemoryIdFactory memoryIds,
            tools.jackson.databind.ObjectMapper json) {
        this.workflow = workflow;
        this.memoryIds = memoryIds;
        this.json = json;
    }

    @Tool(
            name = "list_document_inputs",
            value = "列出当前会话文档附件的资源ID、文件名和类型。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String listInputs(@ToolMemoryId String memoryId) {
        var context = memoryIds.parse(memoryId);
        Long user = context.userId();
        String session = context.sessionId();
        return workflow.inputs(user, session);
    }

    @Tool(
            name = "parse_document",
            value = "识别文档或读取已完成任务的分页正文；识别不自动调用质量复核。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String parse(
            @ToolMemoryId String memoryId,
            @P(value = "新解析时传本会话资源ID", required = false) String resourceId,
            @P(value = "起始页，从1开始，默认1", required = false) Integer startPage,
            @P(value = "结束页，省略为全文", required = false) Integer endPage,
            @P(value = "查询或分页读取已有任务时传任务ID", required = false) String taskId,
            @P(value = "已放行正文字符偏移量，默认0", required = false) Integer offset) {
        var context = memoryIds.parse(memoryId);
        Long user = context.userId();
        String session = context.sessionId();
        return workflow.parse(user, session, resourceId, startPage, endPage, taskId, offset);
    }

    @Tool(
            name = "ocr_document",
            value = "升级本地低质量结果到 ModelBest OCR，识别完成即可读取，不自动质量复核。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String ocr(
            @ToolMemoryId String memoryId,
            @P(value = "已有低质量文档任务ID", required = true) String taskId) {
        var context = memoryIds.parse(memoryId);
        Long user = context.userId();
        String session = context.sessionId();
        return workflow.ocr(user, session, taskId);
    }

    @Tool(
            name = "review_ocr",
            value = "仅当用户明确要求审核、复核或核对识别准确性时调用。为已完成的识别结果创建独立 Jev 质量复核任务，不影响原正文可读性。查询复核进度请用 parse_document(taskId)。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String verify(
            @ToolMemoryId String memoryId, @P(value = "文档任务ID", required = true) String taskId) {
        var context = memoryIds.parse(memoryId);
        Long user = context.userId();
        String session = context.sessionId();
        return workflow.requestReview(user, session, taskId);
    }

    @Tool(
            name = "generate_document",
            value = "按完整Markdown正文生成DOCX/PDF/MD并检查后交付。改写原文必须传依赖的已放行任务ID。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String generate(
            @ToolMemoryId String memoryId,
            @P(value = "完整文档正文，支持标题、列表和表格", required = true) String markdown,
            @P(value = "docx、pdf或md，默认docx", required = false) String format,
            @P(value = "交付文件名", required = false) String fileName,
            @P(value = "依赖的全部原文任务ID，新建可为空", required = false) List<String> sourceTaskIds) {
        var context = memoryIds.parse(memoryId);
        Long user = context.userId();
        String session = context.sessionId();
        return workflow.generate(user, session, markdown, format, fileName, sourceTaskIds);
    }

    @Tool(
            name = "cancel_document_task",
            value = "用户明确取消原文档任务时调用，停止后续续跑。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String cancel(
            @ToolMemoryId String memoryId, @P(value = "待取消文档任务ID", required = true) String taskId) {
        var context = memoryIds.parse(memoryId);
        Long user = context.userId();
        String session = context.sessionId();
        return json.writeValueAsString(
                workflow.action(user, session, taskId, 0, "CANCEL", null, false));
    }
}
