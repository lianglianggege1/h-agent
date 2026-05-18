# 聊天页抽屉菜单布局实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 移除聊天页“今日状态”卡片，并将“我的”入口改为左上角汉堡菜单触发的左侧抽屉，入口固定在抽屉左下角。

**架构：** 只修改聊天页客户端组件，在 `ChatPage` 内新增 `drawerOpen` 本地状态控制抽屉显隐。抽屉与遮罩作为聊天页 JSX 的同级覆盖层，不抽象全局 AppShell，不改变后端接口或 `/me` 页面。

**技术栈：** Next.js 16 App Router、React 19 Client Component、TypeScript、Tailwind CSS、现有 `Link`/`useRouter`/认证工具。

---

## 文件结构

- 修改：`frontend/app/chat/page.tsx`
  - 新增 `drawerOpen` 状态。
  - 将 header 右侧“我的/退出”按钮区改为左侧两横杠菜单按钮 + 标题。
  - 新增左侧抽屉和遮罩 JSX。
  - 删除“今日状态”卡片。
  - 保留聊天、系统提示词、退出登录和认证逻辑。
- 验证：`frontend/package.json`
  - 使用现有脚本 `npm run lint` 和 `npm run build`，无需修改。

## 实现任务

### 任务 1：新增抽屉状态和顶部菜单按钮

**文件：**
- 修改：`frontend/app/chat/page.tsx:106-122`
- 修改：`frontend/app/chat/page.tsx:248-273`

- [ ] **步骤 1：编写失败的检查**

运行：

```bash
source ~/.profile && python3 - <<'PY'
from pathlib import Path
text = Path('/Users/huajiang/Desktop/h-agent/frontend/app/chat/page.tsx').read_text()
assert 'const [drawerOpen, setDrawerOpen] = useState(false);' in text
assert 'aria-label="打开菜单"' in text
assert 'onClick={() => setDrawerOpen(true)}' in text
assert '我的\n              </Link>' not in text
PY
```

预期：FAIL，至少报 `AssertionError`，因为当前还没有 `drawerOpen` 状态和菜单按钮，且顶部仍有“我的”链接。

- [ ] **步骤 2：添加 `drawerOpen` 状态**

在 `ChatPage` 的现有状态声明区，放在 `authenticated` 后面：

```tsx
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [input, setInput] = useState("");
```

- [ ] **步骤 3：替换 header 结构**

将当前 header 内部：

```tsx
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs uppercase tracking-[0.28em] text-amber-700">H-Agent Chat</p>
              <h1 className="mt-2 text-xl font-semibold">AI 对话</h1>
            </div>
            <div className="flex gap-2">
              <Link
                className="rounded-full border border-stone-300 px-4 py-2 text-sm text-stone-600 transition hover:bg-stone-100"
                href="/me"
              >
                我的
              </Link>
              <button
                className="rounded-full border border-stone-300 px-4 py-2 text-sm text-stone-600 transition hover:bg-stone-100"
                type="button"
                onClick={handleLogout}
              >
                退出
              </button>
            </div>
          </div>
```

替换为：

```tsx
          <div className="flex items-center gap-3">
            <button
              className="flex h-11 w-11 shrink-0 flex-col items-center justify-center gap-1.5 rounded-full border border-stone-300 bg-white/80 transition hover:bg-stone-100"
              type="button"
              aria-label="打开菜单"
              onClick={() => setDrawerOpen(true)}
            >
              <span className="h-0.5 w-5 rounded-full bg-stone-800" />
              <span className="h-0.5 w-5 rounded-full bg-stone-800" />
            </button>
            <div>
              <p className="text-xs uppercase tracking-[0.28em] text-amber-700">H-Agent Chat</p>
              <h1 className="mt-2 text-xl font-semibold">AI 对话</h1>
            </div>
          </div>
```

- [ ] **步骤 4：运行检查验证通过**

运行：

```bash
source ~/.profile && python3 - <<'PY'
from pathlib import Path
text = Path('/Users/huajiang/Desktop/h-agent/frontend/app/chat/page.tsx').read_text()
assert 'const [drawerOpen, setDrawerOpen] = useState(false);' in text
assert 'aria-label="打开菜单"' in text
assert 'onClick={() => setDrawerOpen(true)}' in text
assert 'href="/me"\n              >\n                我的' not in text
PY
```

预期：PASS，无输出。

- [ ] **步骤 5：运行前端 lint**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/frontend && npm run lint
```

预期：PASS，ESLint 不报告错误。

- [ ] **步骤 6：Commit**

```bash
git add /Users/huajiang/Desktop/h-agent/frontend/app/chat/page.tsx
git commit -m "feat: add chat drawer menu button"
```

### 任务 2：添加左侧抽屉和底部“我的”入口

**文件：**
- 修改：`frontend/app/chat/page.tsx:248-386`

- [ ] **步骤 1：编写失败的检查**

运行：

```bash
source ~/.profile && python3 - <<'PY'
from pathlib import Path
text = Path('/Users/huajiang/Desktop/h-agent/frontend/app/chat/page.tsx').read_text()
assert 'aria-label="关闭菜单遮罩"' in text
assert 'drawerOpen ? "translate-x-0" : "-translate-x-full"' in text
assert 'href="/me"' in text
assert '>我的</Link>' in text or '我的\n              </Link>' in text
assert 'onClick={handleLogout}' in text
PY
```

预期：FAIL，至少报 `AssertionError`，因为抽屉和遮罩尚未实现。

- [ ] **步骤 2：在 `<main>` 内添加抽屉覆盖层**

在 `return (` 后的 `<main ...>` 里面、`<section ...>` 之前插入：

```tsx
      {drawerOpen ? (
        <button
          className="fixed inset-0 z-30 bg-stone-950/30"
          type="button"
          aria-label="关闭菜单遮罩"
          onClick={() => setDrawerOpen(false)}
        />
      ) : null}

      <aside
        className={`fixed bottom-0 left-0 top-0 z-40 flex w-[280px] max-w-[82vw] flex-col justify-between bg-[#f8f5ec] px-5 pb-[calc(1.25rem+env(safe-area-inset-bottom))] pt-[max(1.5rem,env(safe-area-inset-top))] shadow-[24px_0_60px_rgba(58,45,28,0.18)] transition-transform duration-200 ${
          drawerOpen ? "translate-x-0" : "-translate-x-full"
        }`}
      >
        <div>
          <p className="text-xs uppercase tracking-[0.28em] text-amber-700">H-Agent</p>
          <h2 className="mt-2 text-2xl font-semibold">菜单</h2>
        </div>

        <div className="space-y-3">
          <Link
            className="block rounded-2xl bg-stone-900 px-4 py-4 text-sm font-semibold text-white"
            href="/me"
            onClick={() => setDrawerOpen(false)}
          >
            我的
          </Link>
          <button
            className="w-full rounded-2xl border border-stone-200 bg-white/70 px-4 py-4 text-left text-sm font-semibold text-stone-700"
            type="button"
            onClick={handleLogout}
          >
            退出登录
          </button>
        </div>
      </aside>
```

插入后的结构应为：

```tsx
  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] text-stone-900">
      {drawerOpen ? (...遮罩...) : null}

      <aside ...>...</aside>

      <section className="mx-auto flex min-h-screen w-full max-w-md flex-col">
```

- [ ] **步骤 3：运行检查验证通过**

运行：

```bash
source ~/.profile && python3 - <<'PY'
from pathlib import Path
text = Path('/Users/huajiang/Desktop/h-agent/frontend/app/chat/page.tsx').read_text()
assert 'aria-label="关闭菜单遮罩"' in text
assert 'drawerOpen ? "translate-x-0" : "-translate-x-full"' in text
assert 'href="/me"' in text
assert 'onClick={() => setDrawerOpen(false)}' in text
assert '退出登录' in text
assert 'onClick={handleLogout}' in text
PY
```

预期：PASS，无输出。

- [ ] **步骤 4：运行前端 lint**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/frontend && npm run lint
```

预期：PASS，ESLint 不报告错误。

- [ ] **步骤 5：Commit**

```bash
git add /Users/huajiang/Desktop/h-agent/frontend/app/chat/page.tsx
git commit -m "feat: add chat side drawer"
```

### 任务 3：移除“今日状态”卡片并调整正文间距

**文件：**
- 修改：`frontend/app/chat/page.tsx:275-312`

- [ ] **步骤 1：编写失败的检查**

运行：

```bash
source ~/.profile && python3 - <<'PY'
from pathlib import Path
text = Path('/Users/huajiang/Desktop/h-agent/frontend/app/chat/page.tsx').read_text()
assert '今日状态' not in text
assert '已连接流式 AI 对话服务' not in text
assert '支持登录后访问、历史上下文记忆、实时逐字返回。' not in text
assert 'className="rounded-[1.5rem] border border-stone-200 bg-white/90 p-4 shadow-sm"' in text
PY
```

预期：FAIL，至少报 `AssertionError`，因为当前仍有“今日状态”文案，系统提示词卡片仍带 `mt-4`。

- [ ] **步骤 2：删除“今日状态”卡片**

删除正文区域开头这段：

```tsx
          <div className="rounded-[1.75rem] bg-stone-900 px-5 py-5 text-stone-50 shadow-[0_20px_40px_rgba(58,45,28,0.18)]">
            <p className="text-sm text-stone-300">今日状态</p>
            <p className="mt-2 text-lg font-semibold">已连接流式 AI 对话服务</p>
            <p className="mt-2 text-sm leading-6 text-stone-300">
              支持登录后访问、历史上下文记忆、实时逐字返回。
            </p>
          </div>

```

- [ ] **步骤 3：调整系统提示词卡片顶部间距**

将紧随其后的系统提示词卡片 class 从：

```tsx
          <div className="mt-4 rounded-[1.5rem] border border-stone-200 bg-white/90 p-4 shadow-sm">
```

改为：

```tsx
          <div className="rounded-[1.5rem] border border-stone-200 bg-white/90 p-4 shadow-sm">
```

- [ ] **步骤 4：运行检查验证通过**

运行：

```bash
source ~/.profile && python3 - <<'PY'
from pathlib import Path
text = Path('/Users/huajiang/Desktop/h-agent/frontend/app/chat/page.tsx').read_text()
assert '今日状态' not in text
assert '已连接流式 AI 对话服务' not in text
assert '支持登录后访问、历史上下文记忆、实时逐字返回。' not in text
assert 'className="rounded-[1.5rem] border border-stone-200 bg-white/90 p-4 shadow-sm"' in text
PY
```

预期：PASS，无输出。

- [ ] **步骤 5：运行前端 lint**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/frontend && npm run lint
```

预期：PASS，ESLint 不报告错误。

- [ ] **步骤 6：Commit**

```bash
git add /Users/huajiang/Desktop/h-agent/frontend/app/chat/page.tsx
git commit -m "feat: remove chat status card"
```

### 任务 4：最终验证

**文件：**
- 验证：`frontend/app/chat/page.tsx`
- 验证：`frontend/package.json`

- [ ] **步骤 1：运行静态检查**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/frontend && npm run lint
```

预期：PASS，ESLint 不报告错误。

- [ ] **步骤 2：运行前端构建**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/frontend && npm run build
```

预期：PASS，Next.js build 成功完成。

- [ ] **步骤 3：运行现有前端测试**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/frontend && npm test
```

预期：PASS，Node test 全部通过。

- [ ] **步骤 4：最终内容检查**

运行：

```bash
source ~/.profile && python3 - <<'PY'
from pathlib import Path
text = Path('/Users/huajiang/Desktop/h-agent/frontend/app/chat/page.tsx').read_text()
required = [
    'const [drawerOpen, setDrawerOpen] = useState(false);',
    'aria-label="打开菜单"',
    'aria-label="关闭菜单遮罩"',
    'drawerOpen ? "translate-x-0" : "-translate-x-full"',
    'href="/me"',
    '退出登录',
]
for item in required:
    assert item in text, item
for item in ['今日状态', '已连接流式 AI 对话服务', '支持登录后访问、历史上下文记忆、实时逐字返回。']:
    assert item not in text, item
PY
```

预期：PASS，无输出。

- [ ] **步骤 5：手动验证清单**

启动开发服务器：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/frontend && npm run dev
```

在浏览器访问现有本地开发地址后检查：

- `/chat` 页面不显示“今日状态”卡片。
- 左上角显示两条横杠菜单按钮。
- 点击菜单按钮后，左侧抽屉滑出。
- 点击半透明遮罩后，抽屉关闭。
- 抽屉左下角显示“我的”，点击后跳转 `/me`。
- 抽屉底部“退出登录”仍能退出。
- 系统提示词选择、快捷提示词、聊天发送输入区仍显示并可用。

- [ ] **步骤 6：Commit 最终验证记录（如有代码调整）**

如果最终验证过程中修复了代码，运行：

```bash
git add /Users/huajiang/Desktop/h-agent/frontend/app/chat/page.tsx
git commit -m "fix: polish chat drawer layout"
```

如果没有代码调整，不创建空 commit。

## 自检结果

- 规格覆盖度：目标 1 由任务 3 覆盖；目标 2、3、4 由任务 1 和任务 2 覆盖；目标 5 由任务 2 保留退出能力、任务 4 验证现有功能覆盖。
- 占位符扫描：计划不包含“待定”“TODO”“后续实现”“类似任务”等占位描述。
- 类型一致性：仅新增 `drawerOpen`/`setDrawerOpen`，与 React `useState` 命名一致；路由继续使用现有 `Link` 和 `handleLogout`。
