# 聊天回复 Markdown 渲染实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让聊天页的大模型回复支持 Markdown 渲染，同时用户消息、思考过程、安全拦截原因和媒体消息维持现有展示行为。

**架构：** 新增一个专用 `MarkdownContent` React 组件承接 assistant 正文渲染，使用 `react-markdown` 和 `remark-gfm` 支持常见 LLM Markdown；链接安全策略抽为纯函数并单测；聊天页只把 assistant 普通文本段交给 Markdown 组件，其他消息路径不变。

**技术栈：** TypeScript / React 19 / Next.js 16 App Router / Tailwind CSS 4 / node:test / react-markdown 10.1.0 / remark-gfm 4.0.1

**设计决策：**
- 仅 assistant 最终回复启用 Markdown。
- 用户消息继续纯文本展示。
- `<think>...</think>`、独立 reasoning、blocked 原因继续纯文本展示。
- 不启用原始 HTML 渲染，避免把大模型输出当作可信 HTML。

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `frontend/lib/markdown-links.ts` | Markdown 链接安全白名单，供渲染组件和单测复用 |
| `frontend/lib/markdown-links.test.mjs` | 验证允许 http/https/mailto/tel/相对链接，拒绝 javascript/data/空链接 |
| `frontend/app/chat/markdown-content.tsx` | 聊天气泡内的 Markdown 渲染组件和元素样式映射 |

### 修改文件

| 文件 | 变化 |
|------|------|
| `frontend/package.json` | 新增 `react-markdown`、`remark-gfm` 依赖 |
| `frontend/package-lock.json` | 随 `npm install` 更新锁文件 |
| `frontend/app/chat/page.tsx` | 在 `AssistantMessageContent` 的 text segment 中使用 `MarkdownContent` |

---

## 执行前注意

- 工作区当前已有未提交改动，尤其 `frontend/app/chat/page.tsx` 可能由用户或其他任务修改过。执行前先运行 `source ~/.profile && git status --short`，不要覆盖无关改动。
- 前端约定见 `frontend/AGENTS.md`：这是 Next.js 16，需要在写代码前查阅本地 `frontend/node_modules/next/dist/docs/` 中相关文档。此任务涉及 Client Component，至少阅读 `frontend/node_modules/next/dist/docs/01-app/03-api-reference/01-directives/use-client.md`。
- 当前项目没有 React component test 基础。不要为了这个小改动引入 testing-library；组件行为用 `npm run lint`、`npm run build` 和页面手测验证，安全策略用纯函数单测覆盖。

---

### 任务 1：为 Markdown 链接安全策略补测试

**文件：**
- 创建：`frontend/lib/markdown-links.test.mjs`
- 后续创建：`frontend/lib/markdown-links.ts`

- [ ] **步骤 1：编写失败的测试**

创建 `frontend/lib/markdown-links.test.mjs`：

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import { safeMarkdownHref } from "./markdown-links.ts";

test("safeMarkdownHref allows common web and relative links", () => {
  assert.equal(safeMarkdownHref("https://example.com/a?b=1"), "https://example.com/a?b=1");
  assert.equal(safeMarkdownHref("http://example.com"), "http://example.com");
  assert.equal(safeMarkdownHref("mailto:hello@example.com"), "mailto:hello@example.com");
  assert.equal(safeMarkdownHref("tel:+8613800138000"), "tel:+8613800138000");
  assert.equal(safeMarkdownHref("/me/knowledge"), "/me/knowledge");
  assert.equal(safeMarkdownHref("./local"), "./local");
  assert.equal(safeMarkdownHref("../parent"), "../parent");
  assert.equal(safeMarkdownHref("#section"), "#section");
});

test("safeMarkdownHref rejects empty and executable links", () => {
  assert.equal(safeMarkdownHref(undefined), null);
  assert.equal(safeMarkdownHref(""), null);
  assert.equal(safeMarkdownHref("   "), null);
  assert.equal(safeMarkdownHref("//example.com/protocol-relative"), null);
  assert.equal(safeMarkdownHref("javascript:alert(1)"), null);
  assert.equal(safeMarkdownHref("data:text/html,<script>alert(1)</script>"), null);
  assert.equal(safeMarkdownHref("vbscript:msgbox(1)"), null);
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd frontend && npm test
```

预期：FAIL，报错包含 `Cannot find module` 或 `ERR_MODULE_NOT_FOUND`，因为 `frontend/lib/markdown-links.ts` 还不存在。

- [ ] **步骤 3：实现最少安全策略**

创建 `frontend/lib/markdown-links.ts`：

```ts
const ALLOWED_ABSOLUTE_PROTOCOLS = new Set(["http:", "https:", "mailto:", "tel:"]);

export function safeMarkdownHref(href: string | undefined): string | null {
  const value = href?.trim();
  if (!value) {
    return null;
  }

  if (
    value.startsWith("#") ||
    (value.startsWith("/") && !value.startsWith("//")) ||
    value.startsWith("./") ||
    value.startsWith("../")
  ) {
    return value;
  }

  try {
    const url = new URL(value);
    return ALLOWED_ABSOLUTE_PROTOCOLS.has(url.protocol) ? value : null;
  } catch {
    return null;
  }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd frontend && npm test
```

预期：PASS，新增 `markdown-links.test.mjs` 和既有 `lib/*.test.mjs` 都通过。

- [ ] **步骤 5：Commit**

```bash
source ~/.profile && git add frontend/lib/markdown-links.ts frontend/lib/markdown-links.test.mjs
source ~/.profile && git commit -m "test: cover markdown link safety"
```

---

### 任务 2：安装 Markdown 渲染依赖并新增组件

**文件：**
- 修改：`frontend/package.json`
- 修改：`frontend/package-lock.json`
- 创建：`frontend/app/chat/markdown-content.tsx`

- [ ] **步骤 1：安装依赖**

运行：

```bash
source ~/.profile && cd frontend && npm install react-markdown@10.1.0 remark-gfm@4.0.1
```

预期：
- `frontend/package.json` 的 `dependencies` 增加 `react-markdown` 和 `remark-gfm`。
- `frontend/package-lock.json` 更新对应锁定版本。

- [ ] **步骤 2：阅读 Next Client Component 文档**

运行：

```bash
source ~/.profile && sed -n '1,180p' frontend/node_modules/next/dist/docs/01-app/03-api-reference/01-directives/use-client.md
```

确认点：
- `frontend/app/chat/page.tsx` 已经是 `'use client'` 入口。
- 新增的 `markdown-content.tsx` 只被该 Client Component 引入，不需要额外声明 `'use client'`。

- [ ] **步骤 3：创建 Markdown 渲染组件**

创建 `frontend/app/chat/markdown-content.tsx`：

```tsx
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { safeMarkdownHref } from "@/lib/markdown-links";

type MarkdownContentProps = {
  content: string;
};

export function MarkdownContent({ content }: MarkdownContentProps) {
  return (
    <div className="min-w-0 break-words">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        skipHtml
        components={{
          a({ children, href }) {
            const safeHref = safeMarkdownHref(href);
            if (!safeHref) {
              return <span>{children}</span>;
            }

            return (
              <a
                className="font-medium text-amber-700 underline decoration-amber-300 underline-offset-2 hover:text-amber-800"
                href={safeHref}
                rel="noreferrer"
                target={safeHref.startsWith("#") || safeHref.startsWith("/") ? undefined : "_blank"}
              >
                {children}
              </a>
            );
          },
          blockquote({ children }) {
            return (
              <blockquote className="my-2 border-l-4 border-stone-200 pl-3 text-stone-500">
                {children}
              </blockquote>
            );
          },
          code({ children, className }) {
            return (
              <code
                className={[
                  "rounded bg-stone-100 px-1 py-0.5 font-mono text-[0.85em] text-stone-800",
                  className,
                ]
                  .filter(Boolean)
                  .join(" ")}
              >
                {children}
              </code>
            );
          },
          h1({ children }) {
            return <h1 className="mb-2 mt-1 text-base font-semibold leading-6 text-stone-900">{children}</h1>;
          },
          h2({ children }) {
            return <h2 className="mb-2 mt-3 text-sm font-semibold leading-6 text-stone-900">{children}</h2>;
          },
          h3({ children }) {
            return <h3 className="mb-1 mt-3 text-sm font-semibold leading-6 text-stone-800">{children}</h3>;
          },
          hr() {
            return <hr className="my-3 border-stone-200" />;
          },
          li({ children }) {
            return <li className="pl-1">{children}</li>;
          },
          ol({ children }) {
            return <ol className="my-2 list-decimal space-y-1 pl-5">{children}</ol>;
          },
          p({ children }) {
            return <p className="my-2 first:mt-0 last:mb-0">{children}</p>;
          },
          pre({ children }) {
            return (
              <pre className="my-2 max-w-full overflow-x-auto rounded-xl bg-stone-950 p-3 text-xs leading-5 text-stone-50 [&_code]:block [&_code]:bg-transparent [&_code]:p-0 [&_code]:text-inherit">
                {children}
              </pre>
            );
          },
          table({ children }) {
            return (
              <div className="my-3 max-w-full overflow-x-auto rounded-xl border border-stone-200">
                <table className="min-w-full border-collapse text-left text-xs">{children}</table>
              </div>
            );
          },
          tbody({ children }) {
            return <tbody className="divide-y divide-stone-100">{children}</tbody>;
          },
          td({ children }) {
            return <td className="whitespace-nowrap px-3 py-2 align-top">{children}</td>;
          },
          th({ children }) {
            return <th className="whitespace-nowrap bg-stone-50 px-3 py-2 font-semibold">{children}</th>;
          },
          thead({ children }) {
            return <thead className="border-b border-stone-200">{children}</thead>;
          },
          ul({ children }) {
            return <ul className="my-2 list-disc space-y-1 pl-5">{children}</ul>;
          },
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}
```

- [ ] **步骤 4：运行类型和 lint 验证**

运行：

```bash
source ~/.profile && cd frontend && npm run lint
source ~/.profile && cd frontend && npm run build
```

预期：
- `npm run lint` PASS。
- `npm run build` PASS。

如果 TypeScript 对 `ReactMarkdown` component props 的类型推断报错，优先根据实际错误给局部参数补类型；不要改成 `dangerouslySetInnerHTML`。

- [ ] **步骤 5：Commit**

```bash
source ~/.profile && git add frontend/package.json frontend/package-lock.json frontend/app/chat/markdown-content.tsx
source ~/.profile && git commit -m "feat: add chat markdown renderer"
```

---

### 任务 3：把 assistant 正文接入 Markdown 渲染

**文件：**
- 修改：`frontend/app/chat/page.tsx`

- [ ] **步骤 1：确认当前聊天页差异**

运行：

```bash
source ~/.profile && git diff -- frontend/app/chat/page.tsx
```

预期：
- 看清楚用户已有改动。
- 本任务只编辑 `AssistantMessageContent` 附近和 import 区域，不重排无关代码。

- [ ] **步骤 2：引入组件**

在 `frontend/app/chat/page.tsx` 的 import 区域添加：

```tsx
import { MarkdownContent } from "./markdown-content";
```

- [ ] **步骤 3：替换 assistant text segment 渲染**

在 `AssistantMessageContent` 中，将 text segment 当前的纯文本段：

```tsx
return (
  <p key={`text-${index}`} className="whitespace-pre-wrap">
    {segment.content}
  </p>
);
```

替换为：

```tsx
return <MarkdownContent key={`text-${index}`} content={segment.content} />;
```

保持 think segment 的 `<details>` 分支不变。

- [ ] **步骤 4：确认用户消息仍是纯文本**

检查 `turn.kind === "user"` 分支仍然保留：

```tsx
{turn.content ? <p className="whitespace-pre-wrap">{turn.content}</p> : null}
```

不要把用户消息改成 `MarkdownContent`。

- [ ] **步骤 5：运行验证**

运行：

```bash
source ~/.profile && cd frontend && npm test
source ~/.profile && cd frontend && npm run lint
source ~/.profile && cd frontend && npm run build
```

预期：
- `npm test` PASS。
- `npm run lint` PASS。
- `npm run build` PASS。

- [ ] **步骤 6：本地页面手测**

启动：

```bash
source ~/.profile && cd frontend && npm run dev
```

在浏览器访问 dev server，发送或模拟包含以下内容的 assistant 回复：

````md
# 标题

- 列表 A
- 列表 B

```ts
const answer = "hello";
```

| 名称 | 值 |
| ---- | -- |
| a | 1 |

[安全链接](https://example.com)
[危险链接](javascript:alert(1))
````

验收：
- assistant 气泡中标题、列表、代码块、表格和安全链接正确排版。
- `javascript:` 链接不可点击，文本仍可见。
- 代码块和表格在窄屏不撑破 `max-w-[85%]` 气泡。
- 用户发送相同 Markdown 文本时仍按纯文本显示。
- `<think>内部内容</think>` 仍进入“思考过程”折叠，内部不进行 Markdown 排版。

- [ ] **步骤 7：Commit**

```bash
source ~/.profile && git add frontend/app/chat/page.tsx
source ~/.profile && git commit -m "feat: render assistant markdown replies"
```

---

## 最终验证

全部任务完成后运行：

```bash
source ~/.profile && cd frontend && npm test
source ~/.profile && cd frontend && npm run lint
source ~/.profile && cd frontend && npm run build
source ~/.profile && git status --short
```

预期：
- 前端测试、lint、build 全部通过。
- `git status --short` 中只包含本计划范围内的文件，或者包含执行前已经存在且已确认无关的用户改动。

## 风险和回退

- 如果 `react-markdown` 在 Next 16 build 中出现 ESM 兼容问题，先查看实际 build 错误和该包发布说明，再考虑通过动态 import 或组件边界调整解决；不要降级到不安全 HTML 字符串渲染。
- 如果 Tailwind 任意选择器 `[&_code]` 触发 lint/build 问题，把代码块样式移到 `frontend/app/globals.css` 的 `.chat-markdown pre code` 选择器中，并保持选择器只服务聊天 Markdown。
- 如果用户希望后续支持代码高亮，另开任务评估 `rehype-highlight` 或 Shiki；本计划不包含高亮，避免扩大范围。

## 交付说明

计划实现后，向用户说明：
- 大模型回复已支持 GFM Markdown。
- 用户消息、思考过程、安全拦截原因和媒体消息保持原行为。
- 已运行的验证命令及结果。
