import Link from "next/link";

export default function Home() {
  return (
    <main className="min-h-screen bg-slate-50 px-5 py-10 text-slate-900">
      <section className="mx-auto flex min-h-[calc(100vh-5rem)] max-w-md flex-col justify-center">
        <div className="rounded-3xl bg-white p-6 shadow-sm ring-1 ring-slate-100">
          <p className="text-sm font-medium text-blue-600">H-Agent</p>
          <h1 className="mt-3 text-3xl font-semibold tracking-tight">欢迎使用 H-Agent</h1>
          <p className="mt-2 text-sm leading-6 text-slate-500">使用邮箱注册或登录后开始访问受保护功能。</p>
          <div className="mt-8 grid gap-3">
            <Link
              className="rounded-2xl bg-blue-600 px-4 py-3 text-center text-base font-semibold text-white transition hover:bg-blue-700"
              href="/auth/login"
            >
              登录
            </Link>
            <Link
              className="rounded-2xl border border-slate-200 px-4 py-3 text-center text-base font-semibold text-slate-700 transition hover:bg-slate-100"
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
