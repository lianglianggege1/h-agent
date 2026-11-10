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
import styles from "./auth-form.module.css";

export type SliderCaptchaDialogProps = {
  open: boolean;
  purpose: CaptchaPurpose;
  email: string;
  onVerified: (captchaProof: string) => void;
  onCancel: () => void;
};

const CAPTCHA_STYLE: TacStyle = {
  moveTrackMaskBgColor: "#dbeafe",
  moveTrackMaskBorderColor: "#3b82f6",
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
      className={styles.overlay}
      role="dialog"
      aria-modal="true"
      aria-label="滑块安全验证"
    >
      <div className={styles.dialog}>
        <div className={styles.dialogHeader}>
          <div><h2>安全验证</h2><p>拖动滑块完成拼图</p></div>
          <button className={styles.close} type="button" aria-label="关闭安全验证" onClick={onCancel}>×</button>
        </div>

        <div className={`${styles.challenge} ${loadError ? styles.challengeFailed : ""}`}>
          {phase === "loading" && !loadError ? (
            <div className={styles.loading}>
              正在加载验证码...
            </div>
          ) : null}
          <div id={bindElId} ref={bindElRef} className={`h-slider-captcha-widget flex justify-center ${styles.widget}`} />
        </div>

        {loadError ? (
          <div className={styles.captchaError} role="alert">
            <p>{loadError}</p>
            <button
              className={styles.retry}
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
