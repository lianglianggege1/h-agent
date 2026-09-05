import assert from "node:assert/strict";
import { test } from "node:test";
import {
  CAPTCHA_CHALLENGE_URL,
  CAPTCHA_VERIFICATION_URL,
  TAC_SDK_BASE_PATH,
  applyCaptchaBinding,
  completeCaptchaVerification,
  extractCaptchaProof,
  isCaptchaServiceError,
  translateCaptchaError,
} from "./captcha.ts";

test("applyCaptchaBinding 为 challenge 请求注入 purpose 和 email", () => {
  const request = applyCaptchaBinding(undefined, "LOGIN", "user@example.com");
  assert.deepEqual(request, { purpose: "LOGIN", email: "user@example.com" });
});

test("applyCaptchaBinding 为 verification 请求注入 purpose 和 email 且保留原有字段", () => {
  const request = applyCaptchaBinding(
    { id: "SLIDER_1", data: { bgImageWidth: 300, trackList: [{ x: 1, y: 2, t: 0, type: "down" }] } },
    "REGISTER",
    "user@example.com",
  );
  assert.equal(request.id, "SLIDER_1");
  assert.equal(request.purpose, "REGISTER");
  assert.equal(request.email, "user@example.com");
  assert.equal(request.data.bgImageWidth, 300);
  assert.equal(request.data.trackList.length, 1);
});

test("applyCaptchaBinding 注入后的两类请求都不携带密码或 proof 等敏感字段", () => {
  const challenge = applyCaptchaBinding({}, "LOGIN", "user@example.com");
  assert.deepEqual(Object.keys(challenge).sort(), ["email", "purpose"]);

  const verification = applyCaptchaBinding({ id: "SLIDER_1", data: { trackList: [] } }, "LOGIN", "user@example.com");
  assert.deepEqual(Object.keys(verification).sort(), ["data", "email", "id", "purpose"]);
});

test("extractCaptchaProof 读取验证响应中的 proof", () => {
  assert.equal(extractCaptchaProof({ code: 200, data: { captchaProof: "opaque-value", expiresIn: 90 } }), "opaque-value");
});

test("extractCaptchaProof 对缺失、空值和非字符串 proof 返回 null", () => {
  assert.equal(extractCaptchaProof(null), null);
  assert.equal(extractCaptchaProof({ code: 200, data: null }), null);
  assert.equal(extractCaptchaProof({ code: 200, data: {} }), null);
  assert.equal(extractCaptchaProof({ code: 200, data: { captchaProof: "" } }), null);
  assert.equal(extractCaptchaProof({ code: 200, data: { captchaProof: 12345 } }), null);
  assert.equal(extractCaptchaProof({ code: 4001, data: null }), null);
});

test("completeCaptchaVerification 使用 TAC 实例关闭窗口并继续登录", () => {
  let reloadCount = 0;
  let destroyCount = 0;
  let verifiedProof = null;

  const completed = completeCaptchaVerification(
    { code: 200, data: { captchaProof: "opaque-value" } },
    { reloadCaptcha: () => reloadCount++ },
    { destroyWindow: () => destroyCount++ },
    (proof) => {
      verifiedProof = proof;
    },
  );

  assert.equal(completed, true);
  assert.equal(reloadCount, 0, "成功回调不应刷新验证码");
  assert.equal(destroyCount, 1, "应销毁第三个参数中的 TAC 实例");
  assert.equal(verifiedProof, "opaque-value", "销毁窗口后应继续登录");
});

test("isCaptchaServiceError 识别限流与依赖不可用错误码", () => {
  assert.equal(isCaptchaServiceError(42901), true);
  assert.equal(isCaptchaServiceError(50301), true);
  assert.equal(isCaptchaServiceError(4001), false);
  assert.equal(isCaptchaServiceError(4000), false);
  assert.equal(isCaptchaServiceError(undefined), false);
});

test("translateCaptchaError 将上游错误码翻译为稳定中文提示", () => {
  assert.equal(translateCaptchaError(42901), "操作过于频繁，请稍后再试");
  assert.equal(translateCaptchaError(50301), "验证服务暂时不可用，请稍后重试");
  assert.equal(translateCaptchaError(4001), "验证码暂不可用，请重试");
  assert.equal(translateCaptchaError(undefined), "验证码暂不可用，请重试");
});

test("loadCaptchaLoaderScript 并发调用只插入一个 script 标签", async () => {
  const { loadCaptchaLoaderScript } = await import("./captcha.ts?loader=singleton");
  const originalWindow = globalThis.window;
  const originalDocument = globalThis.document;
  const scripts = [];

  globalThis.window = {};
  globalThis.document = {
    createElement: () => {
      const script = { src: "", async: false, onload: null, onerror: null, remove: () => {} };
      scripts.push(script);
      return script;
    },
    head: { appendChild: (script) => assert.equal(script, scripts[scripts.length - 1]) },
  };

  try {
    const first = loadCaptchaLoaderScript();
    const second = loadCaptchaLoaderScript();
    assert.equal(scripts.length, 1, "并发调用不得重复插入脚本");
    assert.equal(scripts[0].src, "/vendor/tianai-captcha/1.5.5/load.min.js");
    scripts[0].onload();
    await first;
    await second;
  } finally {
    globalThis.window = originalWindow;
    globalThis.document = originalDocument;
  }
});

test("loadCaptchaLoaderScript 失败后允许重新加载", async () => {
  const { loadCaptchaLoaderScript } = await import("./captcha.ts?loader=retry");
  const originalWindow = globalThis.window;
  const originalDocument = globalThis.document;
  const scripts = [];

  globalThis.window = {};
  globalThis.document = {
    createElement: () => {
      const script = { src: "", async: false, onload: null, onerror: null, remove: () => {} };
      scripts.push(script);
      return script;
    },
    head: { appendChild: () => {} },
  };

  try {
    const first = loadCaptchaLoaderScript();
    scripts[0].onerror();
    await assert.rejects(first, /验证码脚本加载失败/);

    const second = loadCaptchaLoaderScript();
    assert.equal(scripts.length, 2, "失败后应重新插入脚本");
    scripts[1].onload();
    await second;
  } finally {
    globalThis.window = originalWindow;
    globalThis.document = originalDocument;
  }
});

test("loadCaptchaLoaderScript 在脚本已就绪时直接返回", async () => {
  const { loadCaptchaLoaderScript } = await import("./captcha.ts?loader=ready");
  const originalWindow = globalThis.window;
  const originalDocument = globalThis.document;

  globalThis.window = { initTAC: () => Promise.resolve({ destroyWindow: () => {} }) };
  globalThis.document = {
    createElement: () => assert.fail("不应再创建脚本元素"),
    head: { appendChild: () => assert.fail("不应再插入脚本") },
  };

  try {
    await loadCaptchaLoaderScript();
  } finally {
    globalThis.window = originalWindow;
    globalThis.document = originalDocument;
  }
});

test("ensureCaptchaSdkLoaded 用隐藏占位元素引导 SDK 且只初始化一次", async () => {
  const { ensureCaptchaSdkLoaded } = await import("./captcha.ts?sdk=bootstrap");
  const originalWindow = globalThis.window;
  const originalDocument = globalThis.document;

  const appended = [];
  let initCalls = 0;
  let destroyed = 0;

  globalThis.window = {
    initTAC: (path, config) => {
      initCalls++;
      assert.equal(path, TAC_SDK_BASE_PATH);
      assert.equal(config.requestCaptchaDataUrl, CAPTCHA_CHALLENGE_URL);
      assert.equal(config.validCaptchaUrl, CAPTCHA_VERIFICATION_URL);
      assert.equal(config.bindEl, "#h-captcha-sdk-bootstrap-holder");
      return Promise.resolve({
        destroyWindow: () => {
          destroyed++;
        },
      });
    },
  };
  globalThis.document = {
    createElement: () => ({
      style: {},
      id: "",
      removeCount: 0,
      remove() {
        this.removeCount += 1;
      },
    }),
    body: { appendChild: (element) => appended.push(element) },
  };

  try {
    await ensureCaptchaSdkLoaded();
    await ensureCaptchaSdkLoaded();
    assert.equal(initCalls, 1, "SDK 引导只应执行一次");
    assert.equal(destroyed, 1, "引导实例应被销毁");
    assert.equal(appended.length, 1, "隐藏占位元素只应插入一次");
    assert.equal(appended[0].style.display, "none");
    assert.equal(appended[0].removeCount, 1, "成功路径应在 finally 中移除占位元素");
  } finally {
    globalThis.window = originalWindow;
    globalThis.document = originalDocument;
  }
});

test("ensureCaptchaSdkLoaded 失败后重置缓存允许重试", async () => {
  const { ensureCaptchaSdkLoaded } = await import("./captcha.ts?sdk=retry");
  const originalWindow = globalThis.window;
  const originalDocument = globalThis.document;

  let initCalls = 0;
  globalThis.window = {
    initTAC: () => {
      initCalls++;
      return initCalls === 1 ? Promise.reject(new Error("load failed")) : Promise.resolve({ destroyWindow: () => {} });
    },
  };
  globalThis.document = {
    createElement: () => ({ style: {}, id: "", remove: () => {} }),
    body: { appendChild: () => {} },
  };

  try {
    await assert.rejects(() => ensureCaptchaSdkLoaded(), /load failed/);
    await ensureCaptchaSdkLoaded();
    assert.equal(initCalls, 2, "失败后应重新初始化");
  } finally {
    globalThis.window = originalWindow;
    globalThis.document = originalDocument;
  }
});

test("ensureCaptchaSdkLoaded 在 SDK 已就绪时不再发起引导", async () => {
  const { ensureCaptchaSdkLoaded } = await import("./captcha.ts?sdk=ready");
  const originalWindow = globalThis.window;
  const originalDocument = globalThis.document;

  globalThis.window = {
    TAC: class {},
    initTAC: () => {
      throw new Error("不应再次调用 initTAC");
    },
  };
  globalThis.document = {
    createElement: () => assert.fail("不应创建占位元素"),
    body: { appendChild: () => assert.fail("不应插入占位元素") },
  };

  try {
    await ensureCaptchaSdkLoaded();
  } finally {
    globalThis.window = originalWindow;
    globalThis.document = originalDocument;
  }
});
