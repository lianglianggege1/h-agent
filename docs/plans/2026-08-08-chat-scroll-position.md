# 聊天滚动位置与拓扑卡片吸顶实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 固定领域智能体拓扑卡片，并在加载更早聊天消息后保持用户当前阅读位置。

**架构：** 聊天页使用视口高度内的独立消息滚动容器。历史 prepend 前保存滚动快照，React 提交后通过纯函数计算高度差补偿；存在历史锚点时跳过自动滚到底部。

**技术栈：** Next.js 16、React 19、TypeScript、Tailwind CSS、Node test runner

---

## 文件结构

- 创建 `frontend/lib/chat-scroll.ts`：无 DOM 依赖的 prepend 滚动位置计算。
- 创建 `frontend/lib/chat-scroll.test.mjs`：滚动位置计算的回归测试。
- 修改 `frontend/app/chat/page.tsx`：建立真实滚动容器、恢复 prepend 锚点、让拓扑卡片吸顶。

### 任务 1：滚动锚点计算

**文件：**
- 创建：`frontend/lib/chat-scroll.test.mjs`
- 创建：`frontend/lib/chat-scroll.ts`

- [ ] **步骤 1：编写失败测试**

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import { scrollTopAfterPrepend } from "./chat-scroll.ts";

test("scrollTopAfterPrepend offsets the viewport by the prepended content height", () => {
  assert.equal(scrollTopAfterPrepend({ previousScrollHeight: 1000, previousScrollTop: 120, nextScrollHeight: 1460 }), 580);
});

test("scrollTopAfterPrepend keeps the previous position when height is unchanged", () => {
  assert.equal(scrollTopAfterPrepend({ previousScrollHeight: 1000, previousScrollTop: 120, nextScrollHeight: 1000 }), 120);
});

test("scrollTopAfterPrepend never returns a negative position", () => {
  assert.equal(scrollTopAfterPrepend({ previousScrollHeight: 1000, previousScrollTop: 20, nextScrollHeight: 900 }), 0);
});
```

- [ ] **步骤 2：运行测试并确认因模块缺失失败**

运行：`cd frontend && npm test -- lib/chat-scroll.test.mjs`

预期：FAIL，提示找不到 `chat-scroll.ts`。

- [ ] **步骤 3：实现最小纯函数**

```ts
export type PrependScrollSnapshot = {
  previousScrollHeight: number;
  previousScrollTop: number;
};

export function scrollTopAfterPrepend({
  previousScrollHeight,
  previousScrollTop,
  nextScrollHeight,
}: PrependScrollSnapshot & { nextScrollHeight: number }) {
  return Math.max(0, previousScrollTop + nextScrollHeight - previousScrollHeight);
}
```

- [ ] **步骤 4：运行目标测试并确认通过**

运行：`cd frontend && node --test lib/chat-scroll.test.mjs`

预期：3 个测试全部 PASS。

### 任务 2：聊天页滚动生命周期

**文件：**
- 修改：`frontend/app/chat/page.tsx:1-20,280-470,639-655,960-1060`

- [ ] **步骤 1：接入滚动快照和 layout effect**

导入 `useLayoutEffect`、`PrependScrollSnapshot` 与 `scrollTopAfterPrepend`。新增 `pendingPrependScrollRef`。把原来的 `[messages]` effect 改为 layout effect：优先消费历史锚点并恢复 `historyContainerRef.current.scrollTop`；没有锚点时才调用 `messageEndRef.current.scrollIntoView`。

- [ ] **步骤 2：在 prepend 前记录真实容器位置**

在 `handleLoadOlderMessages` 收到分页结果后、调用 `setMessages` 前记录：

```ts
const container = historyContainerRef.current;
pendingPrependScrollRef.current = container
  ? { previousScrollHeight: container.scrollHeight, previousScrollTop: container.scrollTop }
  : null;
```

删除无效的 `historyContainerRef.current?.scrollTo({ top: 40 })`。

- [ ] **步骤 3：建立独立滚动容器并添加 sticky 包装层**

把聊天 section 设为 `h-dvh overflow-hidden`，消息区域设为 `min-h-0 flex-1 overflow-y-auto`。领域智能体卡片外层增加 `sticky top-0 z-[5]` 和不透明度足够的同色背景；标准聊天提示词卡片保持原行为。

- [ ] **步骤 4：运行目标测试、完整测试和 lint**

运行：

```bash
cd frontend
node --test lib/chat-scroll.test.mjs
npm test
npm run lint
```

预期：全部测试通过，ESLint 无错误。

### 任务 3：本地页面回归

**文件：** 无

- [ ] **步骤 1：刷新已登录聊天页并检查滚动归属**

预期：消息容器满足 `scrollHeight > clientHeight`；`document.documentElement.scrollHeight` 不再随长消息显著超过视口。

- [ ] **步骤 2：验证拓扑卡片吸顶**

滚动消息容器后，预期“拓扑详情”卡片的 `getBoundingClientRect().top` 保持在消息容器顶部。

- [ ] **步骤 3：验证历史加载锚点**

加载更早消息，预期原先可见消息仍在视口中，且不会跳到最后一条。

- [ ] **步骤 4：验证正常末尾滚动**

发送一条普通消息，预期页面仍自动跟随到最新回复。
