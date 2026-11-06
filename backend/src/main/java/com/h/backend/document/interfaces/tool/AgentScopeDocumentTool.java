package com.h.backend.document.interfaces.tool;

import com.h.backend.document.application.DocumentWorkflow;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import org.springframework.stereotype.Component;

import java.util.List;

/** AgentScope 工具适配器，从运行上下文取得用户与会话身份，调用共享文档应用服务。 */
@Component
public class AgentScopeDocumentTool {
    private final DocumentWorkflow workflow;
    private final tools.jackson.databind.ObjectMapper json;

    public AgentScopeDocumentTool(
            DocumentWorkflow workflow, tools.jackson.databind.ObjectMapper json) {
        this.workflow = workflow;
        this.json = json;
    }

    @Tool(
            name = "list_document_inputs",
            description = "列出当前会话文档附件的资源ID、文件名和类型。",
            concurrencySafe = false)
    public String listInputs(RuntimeContext context) {
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return workflow.inputs(user, session);
    }

    @Tool(
            name = "parse_document",
            description = "识别文档或读取已完成任务的分页正文；识别不自动调用质量复核。",
            concurrencySafe = false)
    public String parse(
            RuntimeContext context,
            @ToolParam(name = "resourceId", description = "新解析时传本会话资源ID", required = false)
                    String resourceId,
            @ToolParam(name = "startPage", description = "起始页，从1开始，默认1", required = false)
                    Integer startPage,
            @ToolParam(name = "endPage", description = "结束页，省略为全文", required = false)
                    Integer endPage,
            @ToolParam(name = "taskId", description = "查询或分页读取已有任务时传任务ID", required = false)
                    String taskId,
            @ToolParam(name = "offset", description = "已放行正文字符偏移量，默认0", required = false)
                    Integer offset) {
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return workflow.parse(user, session, resourceId, startPage, endPage, taskId, offset);
    }

    @Tool(
            name = "ocr_document",
            description = "升级本地低质量结果到 ModelBest OCR，识别完成即可读取，不自动质量复核。",
            concurrencySafe = false)
    public String ocr(
            RuntimeContext context,
            @ToolParam(name = "taskId", description = "已有低质量文档任务ID", required = true)
                    String taskId) {
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return workflow.ocr(user, session, taskId);
    }

    @Tool(name = "review_ocr", description = "仅当用户明确要求审核、复核或核对识别准确性时调用。为已完成的识别结果创建独立 Jev 质量复核任务，不影响原正文可读性。查询复核进度请用 parse_document(taskId)。", concurrencySafe = false)
    public String verify(
            RuntimeContext context,
            @ToolParam(name = "taskId", description = "文档任务ID", required = true) String taskId) {
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return workflow.requestReview(user, session, taskId);
    }

    @Tool(
            name = "generate_document",
            description = "按完整Markdown正文生成DOCX/PDF/MD并检查后交付。改写原文必须传依赖的已放行任务ID。",
            concurrencySafe = false)
    public String generate(
            RuntimeContext context,
            @ToolParam(name = "markdown", description = "完整文档正文，支持标题、列表和表格", required = true)
                    String markdown,
            @ToolParam(name = "format", description = "docx、pdf或md，默认docx", required = false)
                    String format,
            @ToolParam(name = "fileName", description = "交付文件名", required = false) String fileName,
            @ToolParam(name = "sourceTaskIds", description = "依赖的全部原文任务ID，新建可为空", required = false)
                    List<String> sourceTaskIds) {
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return workflow.generate(user, session, markdown, format, fileName, sourceTaskIds);
    }

    @Tool(
            name = "cancel_document_task",
            description = "用户明确取消原文档任务时调用，停止后续续跑。",
            concurrencySafe = false)
    public String cancel(
            RuntimeContext context,
            @ToolParam(name = "taskId", description = "待取消文档任务ID", required = true) String taskId) {
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return json.writeValueAsString(
                workflow.action(user, session, taskId, 0, "CANCEL", null, false));
    }
}
