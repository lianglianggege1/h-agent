package com.h.otheragents.team.tool.langchain4j;

import com.h.capabilities.chat.domain.memory.ChatMemoryIdFactory;
import com.h.capabilities.document.application.DocumentWorkflow;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** LangChain4j 原生文档工具入口：身份取自框架注入的 memoryId，业务委托共享 DocumentWorkflow。 */
public class WorkerDocumentTools {

    private final DocumentWorkflow workflow;
    private final ChatMemoryIdFactory memoryIds;
    private final ObjectMapper json;

    public WorkerDocumentTools(DocumentWorkflow workflow, ChatMemoryIdFactory memoryIds, ObjectMapper json) {
        this.workflow = workflow;
        this.memoryIds = memoryIds;
        this.json = json;
    }

    @Tool(name = "list_document_inputs", value = "列出当前会话文档附件的资源ID、文件名和类型。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String listInputs(@ToolMemoryId String memoryId) {
        var context = memoryIds.parse(memoryId);
        return workflow.inputs(context.userId(), context.sessionId());
    }

    @Tool(name = "parse_document", value = "识别文档或读取已完成任务的分页正文；识别不自动调用质量复核。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String parse(
            @ToolMemoryId String memoryId,
            @P(value = "新解析时传本会话资源ID", required = false) String resourceId,
            @P(value = "起始页，从1开始，默认1", required = false) Integer startPage,
            @P(value = "结束页，省略为全文", required = false) Integer endPage,
            @P(value = "查询或分页读取已有任务时传任务ID", required = false) String taskId,
            @P(value = "已放行正文字符偏移量，默认0", required = false) Integer offset) {
        var context = memoryIds.parse(memoryId);
        return workflow.parse(context.userId(), context.sessionId(), resourceId, startPage, endPage, taskId, offset);
    }

    @Tool(name = "ocr_document", value = "升级本地低质量结果到 ModelBest OCR，识别完成即可读取，不自动质量复核。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String ocr(
            @ToolMemoryId String memoryId,
            @P(value = "已有低质量文档任务ID") String taskId) {
        var context = memoryIds.parse(memoryId);
        return workflow.ocr(context.userId(), context.sessionId(), taskId);
    }

    @Tool(name = "review_ocr", value = "仅当用户明确要求审核、复核或核对识别准确性时调用。为已完成的识别结果创建独立 Jev 质量复核任务，不影响原正文可读性。查询复核进度请用 parse_document(taskId)。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String verify(
            @ToolMemoryId String memoryId,
            @P(value = "文档任务ID") String taskId) {
        var context = memoryIds.parse(memoryId);
        return workflow.requestReview(context.userId(), context.sessionId(), taskId);
    }

    @Tool(name = "generate_document", value = "按完整Markdown正文生成DOCX/PDF/MD并检查后交付。改写原文必须传依赖的已放行任务ID。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String generate(
            @ToolMemoryId String memoryId,
            @P(value = "完整文档正文，支持标题、列表和表格") String markdown,
            @P(value = "docx、pdf或md，默认docx", required = false) String format,
            @P(value = "交付文件名", required = false) String fileName,
            @P(value = "依赖的全部原文任务ID，新建可为空", required = false) List<String> sourceTaskIds) {
        var context = memoryIds.parse(memoryId);
        return workflow.generate(context.userId(), context.sessionId(), markdown, format, fileName, sourceTaskIds);
    }

    @Tool(name = "cancel_document_task", value = "用户明确取消原文档任务时调用，停止后续续跑。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String cancel(
            @ToolMemoryId String memoryId,
            @P(value = "待取消文档任务ID") String taskId) {
        var context = memoryIds.parse(memoryId);
        return json.writeValueAsString(
                workflow.action(context.userId(), context.sessionId(), taskId, 0, "CANCEL", null, false));
    }
}