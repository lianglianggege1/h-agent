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
