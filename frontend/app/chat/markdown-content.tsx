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
            return <blockquote className="my-2 border-l-4 border-stone-200 pl-3 text-stone-500">{children}</blockquote>;
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
