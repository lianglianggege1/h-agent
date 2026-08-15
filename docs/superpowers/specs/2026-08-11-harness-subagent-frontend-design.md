# Harness 协作 Agent 前端设计

- 日期：2026-08-12
- 状态：已确认并实施
- 依据：[Harness Agent PRD](../../prd/2026-08-08-harness-agent-prd.md)
- 后端契约：[Harness 协作 Agent 后端设计](./2026-08-11-harness-subagent-backend-design.md)

## 1. 交互模型

子 Agent 不是新路由和替换页面，而是 `/chat` 父页上方的抽屉：

```text
父聊天（始终挂载、独立 SSE、独立输入）
└── 子 Agent 抽屉（独立消息、独立 SSE、独立输入与附件）
```

- 点击协作者头像只设置本地选中的协作者 Session，不调用 `router.push/replace`。
- 父页面、消息列表和流订阅不卸载，因此父回复继续刷新跳动。
- 关闭抽屉只隐藏 UI，不取消子请求；回到父页面可以立即继续操作。
- 再次打开运行中的子 Agent 可看到按实际 Session 保存的实时进度。
- 同一子 Agent 的第二次发送被禁用且后端也会拒绝；父与其他子 Agent 不受影响。

## 2. 父页协作条

仅 `runtimeType === HARNESS_STREAMING` 渲染。顶级父页只显示 `parentSessionId === rootSessionId` 的直接协作者，按 `displayOrder` 横向滚动，展示名称和产品状态，不展示 thinking、工具日志或诊断事件。

服务端快照返回完整后代树，孙级节点保存在前端状态中，为后续在抽屉内继续下钻保留数据基础。

## 3. 子抽屉

抽屉包含：

1. 返回父 Agent、协作者名称和当前状态。
2. 历史顶部的原始委托；它是后端持久化的首条标准 `SYSTEM` 消息，UI 标注“父 Agent 的委托”，不是独立卡片或前端拼接内容。
3. 独立历史消息与 Markdown/资源渲染。
4. 独立附件上传和追加要求输入区。

`AVAILABLE/COMPLETED/FAILED` 可追加；`RUNNING` 禁用输入。关闭抽屉明确提示不会中断运行。

## 4. 状态结构

```ts
type HarnessSubagentSummary = {
  sessionId: string;        // 实际 Agent Session
  parentSessionId: string;  // 直接父节点
  displayName: string;
  assignment: string;
  status: HarnessSubagentStatus;
  displayOrder: number;
  updatedAt: string;
};

type HarnessSubagents = HarnessSubagentSummary[];
```

没有 snapshot 对象或 revision。接口直接返回最新协作者列表。父请求中的 `harness_event` 只更新协作状态投影；子抽屉通过指定 child session 的独立观察流消费思考、正文和工具增量。流结束后重新请求最新列表校准。

父和子运行状态分开保存：

```ts
streaming: boolean;
messages: UiChatMessage[];
input: string;

subagentStreamingById: Record<string, boolean>;
subagentMessagesById: Record<string, UiChatMessage[]>;
subagentInput: string;
```

## 5. API

- `GET /api/chat/sessions/{root}/subagents`：直接返回协作者数组，用于恢复最新协作树。
- `GET /api/chat/sessions/{sessionId}/messages`：父子统一历史接口，`sessionId` 就是实际消息会话。
- `GET /api/chat/agent-sessions/{childSessionId}/events`：只观察该子会话的实时事件。
- `POST /api/chat/messages/stream`：父子请求结构一致，只传实际执行 `sessionId`。

前端不接收或提交 Gateway 句柄，也不把 `source.path` 当作身份。子 Agent 定向调用由后端从实际 `sessionId` 完成归属校验并解析内部 Gateway 句柄。

## 6. 验收场景

1. 父流运行时打开/关闭子抽屉，父消息继续增长。
2. 子流运行时关闭抽屉，重新打开仍看到同一流进度。
3. 子 A 运行时子 A 不能再次发送，但父和子 B 可以发送。
4. 刷新页面后恢复协作者、状态、直接父关系和独立子历史。
5. 子结果不出现在父历史，父结果不出现在子历史。
6. 再次进入子对话时，首条 `SYSTEM` 委托仍在；后续回答同时保留原委托和此前对话上下文。
7. 父请求流不包含子 Agent 的思考、正文或工具增量，只包含协作状态投影。
8. 非法或跨用户 `sessionId` 不进入抽屉历史。
