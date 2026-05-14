import Link from "next/link";

export default function Home() {
  return (
    <main className="min-h-screen bg-[radial-gradient(circle_at_top,#f5ecd4_0%,#f7f4ea_32%,#ede7da_100%)] px-5 py-8 text-stone-900">
      <section className="mx-auto flex min-h-[calc(100vh-5rem)] max-w-md flex-col justify-center">
        <div className="overflow-hidden rounded-[2rem] border border-stone-200/80 bg-white/90 p-6 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <p className="text-sm font-medium uppercase tracking-[0.28em] text-amber-700">H-Agent</p>
          <h1 className="mt-3 text-3xl font-semibold tracking-tight">独立 AI 对话入口</h1>
          <p className="mt-2 text-sm leading-6 text-stone-500">
            先登录，再进入移动端风格的聊天页面。当前版本聚焦最小可启动闭环。
          </p>
          <div className="mt-6 rounded-[1.5rem] bg-stone-900 px-5 py-5 text-stone-50">
            <p className="text-sm text-stone-300">本期能力</p>
            <p className="mt-2 text-lg font-semibold">登录鉴权 + AI 流式聊天 + H5 底部菜单栏</p>
          </div>
          <div className="mt-8 grid gap-3">
            <Link
              className="rounded-2xl bg-stone-900 px-4 py-3 text-center text-base font-semibold text-white transition hover:bg-stone-800"
              href="/chat"
            >
              进入聊天
            </Link>
            <Link
              className="rounded-2xl border border-stone-200 px-4 py-3 text-center text-base font-semibold text-stone-700 transition hover:bg-stone-100"
              href="/auth/login"
            >
              登录
            </Link>
            <Link
              className="rounded-2xl border border-stone-200 px-4 py-3 text-center text-base font-semibold text-stone-700 transition hover:bg-stone-100"
              href="/auth/register"
            >
              注册
            </Link>
          </div>
        </div>
      </section>
    </main>
  );
}
