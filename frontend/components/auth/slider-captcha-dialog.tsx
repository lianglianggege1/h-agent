"use client";

import { useEffect, useId, useRef, useState } from "react";
import {
  CAPTCHA_CHALLENGE_URL,
  CAPTCHA_VERIFICATION_URL,
  TAC_SDK_BASE_PATH,
  applyCaptchaBinding,
  completeCaptchaVerification,
  ensureCaptchaSdkLoaded,
  isCaptchaServiceError,
  loadCaptchaLoaderScript,
  translateCaptchaError,
} from "@/lib/captcha";
import type { CaptchaPurpose } from "@/lib/captcha";
import type { TacInstance, TacStyle } from "@/types/tianai-captcha";

export type SliderCaptchaDialogProps = {
  open: boolean;
  purpose: CaptchaPurpose;
  email: string;
  onVerified: (captchaProof: string) => void;
  onCancel: () => void;
};

const CAPTCHA_STYLE: TacStyle = {
  moveTrackMaskBgColor: "#fde68a",
  moveTrackMaskBorderColor: "#d97706",
};

export function SliderCaptchaDialog(props: SliderCaptchaDialogProps) {
  const [retryCount, setRetryCount] = useState(0);
  if (!props.open) return null;
  return (
    <SliderCaptchaDialogActive
      key={retryCount}
      purpose={props.purpose}
      email={props.email}
      onVerified={props.onVerified}
      onCancel={props.onCancel}
      retry={() => setRetryCount((current) => current + 1)}
    />
  );
}

type SliderCaptchaDialogActiveProps = Omit<SliderCaptchaDialogProps, "open"> & {
  retry: () => void;
};

function SliderCaptchaDialogActive({ purpose, email, onVerified, onCancel, retry }: SliderCaptchaDialogActiveProps) {
  const bindElId = `h-slider-captcha-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
  const bindElRef = useRef<HTMLDivElement | null>(null);
  const snapshotRef = useRef({ purpose, email });
  const onVerifiedRef = useRef(onVerified);
  const onCancelRef = useRef(onCancel);
  const [phase, setPhase] = useState<"loading" | "active">("loading");
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    onVerifiedRef.current = onVerified;
    onCancelRef.current = onCancel;
  }, [onVerified, onCancel]);

  useEffect(() => {
    let cancelled = false;
    let tac: TacInstance | null = null;

    loadCaptchaLoaderScript()
      .then(() => ensureCaptchaSdkLoaded())
      .then(() => {
        if (cancelled || !bindElRef.current) return null;
        return window.initTAC(
          TAC_SDK_BASE_PATH,
          {
            bindEl: `#${bindElId}`,
            requestCaptchaDataUrl: CAPTCHA_CHALLENGE_URL,
            validCaptchaUrl: CAPTCHA_VERIFICATION_URL,
            validSuccess: (response, captcha, captchaWindow) =>
              completeCaptchaVerification(response, captcha, captchaWindow, onVerifiedRef.current),
            validFail: (response, captcha) => {
              if (isCaptchaServiceError(response?.code)) {
                setLoadError(translateCaptchaError(response?.code));
                return;
              }
              captcha.reloadCaptcha();
            },
            btnCloseFun: () => {
              onCancelRef.current();
            },
          },
          CAPTCHA_STYLE,
        );
      })
      .then((instance) => {
        if (!instance) return;
        if (cancelled) {
          instance.destroyWindow();
          return;
        }
        tac = instance;
        const { config } = instance;
        config.addRequestChain({
          preRequest: (type, request) => {
            if (type === "requestCaptchaData" || type === "validCaptcha") {
              request.data = applyCaptchaBinding(
                request.data as Record<string, unknown> | undefined,
                snapshotRef.current.purpose,
                snapshotRef.current.email,
              );
            }
            return true;
          },
          postRequest: (type, _request, response) => {
            if (type === "requestCaptchaData" && response && response.code !== 200) {
              setLoadError(translateCaptchaError(response.code));
            }
            return true;
          },
        });
        const originalSend = config.doSendRequest.bind(config);
        config.doSendRequest = (request) =>
          originalSend(request).catch((error: unknown) => {
            if (!cancelled) setLoadError("网络异常，请检查连接后重试");
            throw error;
          });
        instance.init();
        setPhase("active");
      })
      .catch(() => {
        if (!cancelled) setLoadError("验证码组件加载失败，请重试");
      });

    return () => {
      cancelled = true;
      tac?.destroyWindow();
    };
  }, [bindElId]);

  useEffect(() => {
    function handleKeyDown(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") {
        onCancelRef.current();
      }
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, []);

  return (
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center bg-stone-900/45 px-2 py-6"
      role="dialog"
      aria-modal="true"
      aria-label="滑块安全验证"
    >
      <div className="w-full max-w-[352px] rounded-[1.5rem] border border-stone-200 bg-white p-3 shadow-[0_24px_60px_rgba(76,59,36,0.25)]">
        <div className="mb-2 flex items-baseline justify-between px-1">
          <h2 className="text-base font-semibold text-stone-900">安全验证</h2>
          <span className="text-xs text-stone-400">拖动滑块完成拼图</span>
        </div>

        <div className="relative flex min-h-[318px] justify-center">
          {phase === "loading" && !loadError ? (
            <div className="absolute inset-0 flex items-center justify-center text-sm text-stone-400">
              正在加载验证码...
            </div>
          ) : null}
          <div id={bindElId} ref={bindElRef} className="h-slider-captcha-widget flex justify-center" />
        </div>

        {loadError ? (
          <div className="mt-2 flex items-center justify-between gap-3 rounded-xl bg-red-50 px-3 py-2.5">
            <p className="text-xs leading-5 text-red-700">{loadError}</p>
            <button
              className="shrink-0 rounded-full bg-red-600 px-3 py-1 text-xs font-medium text-white transition hover:bg-red-700"
              type="button"
              onClick={retry}
            >
              重试
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
}
