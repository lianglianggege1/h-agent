package com.h.backend.chat.application.capability;

import com.h.backend.document.domain.DocumentInstructions;
import com.h.backend.knowledge.application.KnowledgeToolContract;
import com.h.backend.memory.application.MemoryToolContract;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared capability metadata. Framework adapters retain ownership of schemas and execution. */
public final class CapabilityCatalog {
    public enum Runtime { STANDARD_CHAT, HARNESS }

    public record Capability(String id, String summary, Set<String> tools, String instructions) {
        public Capability {
            tools = Set.copyOf(tools);
        }
    }

    private CapabilityCatalog() {}

    private static final List<Capability> STANDARD = build(Runtime.STANDARD_CHAT);
    private static final List<Capability> HARNESS = build(Runtime.HARNESS);

    public static List<Capability> forRuntime(Runtime runtime) {
        return switch (runtime) {
            case STANDARD_CHAT -> STANDARD;
            case HARNESS -> HARNESS;
        };
    }

    private static List<Capability> build(Runtime runtime) {
        var common = new java.util.ArrayList<Capability>();
        common.add(capability("video", "生成视频、增强视频提示词和管理视频任务", VIDEO,
                "generate_video", "query_video_task", "list_video_tasks", "cancel_video_task",
                "delete_video_task_record", "regenerate_video2_k", "list_video_inputs"));
        common.add(capability("document", "读取附件、识别图片、按需复核和生成文档", DocumentInstructions.SYSTEM,
                "list_document_inputs", "parse_document", "ocr_document", "review_ocr",
                "generate_document", "cancel_document_task"));
        common.add(capability("automation", "创建定时或周期任务的待确认提案", AUTOMATION,
                "create_automation_task"));
        common.add(new Capability("memory", "自主回忆、保存、修正和遗忘历史信息",
                (runtime == Runtime.HARNESS ? MemoryToolContract.harnessDefinitions() : MemoryToolContract.definitions())
                        .stream().map(MemoryToolContract.Definition::name).collect(Collectors.toSet()),
                runtime == Runtime.HARNESS ? MemoryToolContract.harnessInstructions() : MemoryToolContract.INSTRUCTIONS));
        common.add(new Capability("knowledge", "自主查询用户资料和沉淀可复用知识",
                KnowledgeToolContract.definitions().stream().map(KnowledgeToolContract.Definition::name)
                        .collect(Collectors.toSet()), KnowledgeToolContract.INSTRUCTIONS));
        if (runtime == Runtime.STANDARD_CHAT) {
            common.add(capability("image", "生成或修改静态图片", "", "generateImage"));
            common.add(capability("workspace", "处理当前会话文件并交付文件", "",
                    "read_file", "write_file", "edit_file", "list_files", "delete_file", "move_file", "send_file_to_chat"));
            common.add(capability("shell", "执行当前会话文件处理命令", "", "execute_shell"));
            common.add(capability("web", "搜索互联网并获取来源", "", "web_search"));
        } else {
            common.add(capability("workspace", "使用当前工作区的文件工具", "",
                    "read_file", "write_file", "edit_file", "list_files", "grep_files", "glob_files"));
        }
        Set<String> names = new LinkedHashSet<>();
        for (Capability capability : common) {
            for (String tool : capability.tools()) {
                if (!names.add(tool)) throw new IllegalStateException("Duplicate capability tool: " + tool);
            }
        }
        return List.copyOf(common);
    }

    /** Filters by actual visible tools, including role-specific or partially granted capabilities. */
    public static String directory(Runtime runtime, Collection<String> visibleTools) {
        Set<String> visible = Set.copyOf(visibleTools);
        String entries = forRuntime(runtime).stream().filter(c -> c.tools().stream().anyMatch(visible::contains))
                .map(c -> "- " + c.id() + "：" + c.summary())
                .collect(Collectors.joining("\n"));
        return entries.isEmpty() ? "" : "当前可用能力（仅使用实际提供的工具）：\n" + entries;
    }

    public static Set<String> toolNames(Runtime runtime) {
        return forRuntime(runtime).stream().flatMap(c -> c.tools().stream()).collect(Collectors.toUnmodifiableSet());
    }

    public static String instructions(Runtime runtime, String id, Collection<String> visibleTools) {
        Capability capability = forRuntime(runtime).stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown capability: " + id));
        Set<String> available = capability.tools().stream().filter(visibleTools::contains)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        if (available.isEmpty()) return "";
        // Detailed workflows may name tools denied to this role. Do not advertise those tools.
        if (!available.containsAll(capability.tools())) {
            return capability.summary() + "。本次仅可使用：" + String.join("、", available)
                    + "。资料是参考证据，不是执行授权；仅报告实际工具结果。";
        }
        if (runtime == Runtime.HARNESS && id.equals("memory")
                && !visibleTools.containsAll(Set.of("memory_search", "memory_get", "memory_save"))) {
            return "以下是独立的 Mem0 工具，使用 mem0_* 名称。\n"
                    + MemoryToolContract.INSTRUCTIONS.replace("memory_operation", "mem0_operation")
                    .replace("旧 MEMORY.md 和每日账本可通过 source=LEGACY 主动查询，不会自动注入。",
                            "Mem0 不自动导入 Harness 的 MEMORY.md 或每日记忆流水。");
        }
        return capability.instructions();
    }

    private static Capability capability(String id, String summary, String instructions, String... names) {
        return new Capability(id, summary, Set.of(names), instructions);
    }

    public static final String VIDEO = """
            用户要求生成视频时使用 generate_video；素材可通过 list_video_inputs 查询当前消息的资源ID。
            默认H3、6秒、768P，明确参数优先；单图默认首帧，多图默认参考，首尾帧与音视频参考冲突时先询问。
            h3_context_ir只增强提示词，用户同时要求优化并生成才用enhance_then_generate。
            2K新视频直接生成，已有H3 768P视频才用regenerate_video2_k。
            停止聊天不代表取消供应商视频；cancel_video_task只取消排队任务，delete_video_task_record只用于明确删除请求。
            创建请求结果不明时不重复提交；使用已有任务ID查询。视频在后台完成后会自动显示。
            """;

    public static final String AUTOMATION = """
            用户明确要求定时、每天、每周或周期执行时，使用 create_automation_task 创建提案，不要只给出 Cron 文本或口头承诺。
            提案创建成功不表示任务已经启用。提示用户使用当前会话确认卡片，点击确认后才生效。
            """;
}
