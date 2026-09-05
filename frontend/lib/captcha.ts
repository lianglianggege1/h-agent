export const TAC_SDK_BASE_PATH = "/vendor/tianai-captcha/1.5.5/tac";
export const TAC_LOADER_SCRIPT_SRC = "/vendor/tianai-captcha/1.5.5/load.min.js";

export const CAPTCHA_CHALLENGE_URL = "/api/captcha/challenges";
export const CAPTCHA_VERIFICATION_URL = "/api/captcha/verifications";

export type CaptchaPurpose = "LOGIN" | "REGISTER";

export function isCaptchaServiceError(code: number | undefined): boolean {
  return code === 42901 || code === 50301;
}

export function translateCaptchaError(code: number | undefined): string {
  if (code === 42901) return "操作过于频繁，请稍后再试";
  if (code === 50301) return "验证服务暂时不可用，请稍后重试";
  return "验证码暂不可用，请重试";
}

export function applyCaptchaBinding(
  requestData: Record<string, unknown> | undefined,
  purpose: CaptchaPurpose,
  email: string,
): Record<string, unknown> {
  return { ...(requestData ?? {}), purpose, email };
}

export function extractCaptchaProof(
  response: { code?: number; data?: { captchaProof?: unknown } | null } | null | undefined,
): string | null {
  const proof = response?.data?.captchaProof;
  return typeof proof === "string" && proof.length > 0 ? proof : null;
}

export function completeCaptchaVerification(
  response: { code?: number; data?: { captchaProof?: unknown } | null } | null | undefined,
  captcha: { reloadCaptcha: () => void },
  tac: { destroyWindow: () => void },
  onVerified: (captchaProof: string) => void,
): boolean {
  const proof = extractCaptchaProof(response);
  if (!proof) {
    captcha.reloadCaptcha();
    return false;
  }
  tac.destroyWindow();
  onVerified(proof);
  return true;
}

const BOOTSTRAP_HOLDER_ID = "h-captcha-sdk-bootstrap-holder";

let loaderScriptPromise: Promise<void> | null = null;

export function loadCaptchaLoaderScript(): Promise<void> {
  if (typeof window === "undefined" || typeof document === "undefined") {
    return Promise.reject(new Error("验证码组件只能在浏览器中加载"));
  }
  if (typeof window.initTAC === "function") return Promise.resolve();
  if (loaderScriptPromise) return loaderScriptPromise;

  loaderScriptPromise = new Promise<void>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = TAC_LOADER_SCRIPT_SRC;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      script.remove();
      loaderScriptPromise = null;
      reject(new Error("验证码脚本加载失败，请检查网络后重试"));
    };
    document.head.appendChild(script);
  });
  return loaderScriptPromise;
}

let sdkLoadPromise: Promise<void> | null = null;

export function ensureCaptchaSdkLoaded(): Promise<void> {
  if (typeof window === "undefined" || typeof document === "undefined") {
    return Promise.reject(new Error("验证码组件只能在浏览器中加载"));
  }
  if (window.TAC) return Promise.resolve();
  if (sdkLoadPromise) return sdkLoadPromise;

  // initTAC 的 showLoading 会替换 bindEl 的 innerHTML，引导阶段必须绑定到一个独立的隐藏元素。
  const holder = document.createElement("div");
  holder.id = BOOTSTRAP_HOLDER_ID;
  holder.style.display = "none";
  document.body.appendChild(holder);

  sdkLoadPromise = window
    .initTAC(TAC_SDK_BASE_PATH, {
      bindEl: `#${BOOTSTRAP_HOLDER_ID}`,
      requestCaptchaDataUrl: CAPTCHA_CHALLENGE_URL,
      validCaptchaUrl: CAPTCHA_VERIFICATION_URL,
    })
    .then((tac) => {
      tac.destroyWindow();
    })
    .catch((error: unknown) => {
      sdkLoadPromise = null;
      throw error;
    })
    .finally(() => {
      holder.remove();
    });
  return sdkLoadPromise;
}
