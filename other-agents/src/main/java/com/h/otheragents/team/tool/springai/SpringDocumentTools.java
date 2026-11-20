package com.h.otheragents.team.tool.springai;

import com.h.backend.document.application.DocumentWorkflow;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Spring AI 原生文档工具入口：身份取自 ToolContext，业务委托共享 DocumentWorkflow。 */
public class SpringDocumentTools {

    private final DocumentWorkflow workflow;
    private final ObjectMapper json;

    public SpringDocumentTools(DocumentWorkflow workflow, ObjectMapper json) {
        this.workflow = workflow;
        this.json = json;
    }

    @Tool(name = "list_document_inputs", description = "列出当前会话文档附件的资源ID、文件名和类型。")
    public String listInputs(ToolContext toolContext) {
        SpringToolContext ctx = SpringToolContext.from(toolContext);
        return workflow.inputs(ctx.userId(), ctx.roomId());
    }

    @Tool(name = "parse_document", description = "识别文档或读取已完成任务的分页正文；识别不自动调用质量复核。")
    public String parse(
            ToolContext toolContext,
            @ToolParam(required = false, description = "新解析时传本会话资源ID") String resourceId,
            @ToolParam(required = false, description = "起始页，从1开始，默认1") Integer startPage,
            @ToolParam(required = false, description = "结束页，省略为全文") Integer endPage,
            @ToolParam(required = false, description = "查询或分页读取已有任务时传任务ID") String taskId,
            @ToolParam(required = false, description = "已放行正文字符偏移量，默认0") Integer offset) {
        SpringToolContext ctx = SpringToolContext.from(toolContext);
        return workflow.parse(ctx.userId(), ctx.roomId(), resourceId, startPage, endPage, taskId, offset);
    }

    @Tool(name = "ocr_document", description = "升级本地低质量结果到 ModelBest OCR，识别完成即可读取，不自动质量复核。")
    public String ocr(
            ToolContext toolContext,
            @ToolParam(description = "已有低质量文档任务ID") String taskId) {
        SpringToolContext ctx = SpringToolContext.from(toolContext);
        return workflow.ocr(ctx.userId(), ctx.roomId(), taskId);
    }

    @Tool(name = "review_ocr", description = "仅当用户明确要求审核、复核或核对识别准确性时调用。为已完成的识别结果创建独立 Jev 质量复核任务，不影响原正文可读性。查询复核进度请用 parse_document(taskId)。")
    public String verify(
            ToolContext toolContext,
            @ToolParam(description = "文档任务ID") String taskId) {
        SpringToolContext ctx = SpringToolContext.from(toolContext);
        return workflow.requestReview(ctx.userId(), ctx.roomId(), taskId);
    }

    @Tool(name = "generate_document", description = "按完整Markdown正文生成DOCX/PDF/MD并检查后交付。改写原文必须传依赖的已放行任务ID。")
    public String generate(
            ToolContext toolContext,
            @ToolParam(description = "完整文档正文，支持标题、列表和表格") String markdown,
            @ToolParam(required = false, description = "docx、pdf或md，默认docx") String format,
            @ToolParam(required = false, description = "交付文件名") String fileName,
            @ToolParam(required = false, description = "依赖的全部原文任务ID，新建可为空") List<String> sourceTaskIds) {
        SpringToolContext ctx = SpringToolContext.from(toolContext);
        return workflow.generate(ctx.userId(), ctx.roomId(), markdown, format, fileName, sourceTaskIds);
    }

    @Tool(name = "cancel_document_task", description = "用户明确取消原文档任务时调用，停止后续续跑。")
    public String cancel(
            ToolContext toolContext,
            @ToolParam(description = "待取消文档任务ID") String taskId) {
        SpringToolContext ctx = SpringToolContext.from(toolContext);
        return json.writeValueAsString(
                workflow.action(ctx.userId(), ctx.roomId(), taskId, 0, "CANCEL", null, false));
    }
}