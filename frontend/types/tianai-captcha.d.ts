export type TacRequest = {
  url: string;
  method?: string;
  headers: Record<string, string>;
  data: unknown;
};

export type TacResponse = {
  code?: number;
  msg?: string;
  data?: Record<string, unknown> | null;
};

export type TacRequestChain = {
  preRequest?: (
    type: "requestCaptchaData" | "validCaptcha",
    request: TacRequest,
    captcha?: unknown,
    param?: unknown,
  ) => boolean | void;
  postRequest?: (
    type: "requestCaptchaData" | "validCaptcha",
    request: TacRequest,
    response: TacResponse | null,
    captcha?: unknown,
    param?: unknown,
  ) => boolean | void;
};

export type TacCaptcha = {
  reloadCaptcha: () => void;
};

export type TacUserConfig = {
  bindEl: string;
  requestCaptchaDataUrl: string;
  validCaptchaUrl: string;
  requestHeaders?: Record<string, string>;
  timeToTimestamp?: boolean;
  validSuccess?: (response: TacResponse, captcha: TacCaptcha, tac: TacInstance) => void;
  validFail?: (response: TacResponse, captcha: TacCaptcha, tac: TacInstance) => void;
  btnCloseFun?: (event: unknown, tac: { destroyWindow: () => void }) => void;
  btnRefreshFun?: (event: unknown, tac: { reloadCaptcha: () => void }) => void;
};

export type TacConfig = TacUserConfig & {
  doSendRequest: (request: TacRequest) => Promise<TacResponse | string>;
  addRequestChain: (chain: TacRequestChain) => void;
  insertRequestChain: (index: number, chain: TacRequestChain) => void;
};

export type TacStyle = {
  btnUrl?: string;
  logoUrl?: string | null;
  bgUrl?: string;
  moveTrackMaskBgColor?: string;
  moveTrackMaskBorderColor?: string;
  i18n?: Record<string, string>;
};

export type TacInstance = {
  config: TacConfig;
  style: TacStyle;
  init: () => TacInstance;
  reloadCaptcha: () => void;
  destroyWindow: () => void;
};

declare global {
  interface Window {
    initTAC: (
      tacPath: string | { url: string; scriptUrls?: string[]; cssUrls?: string[]; timeout?: number },
      config: TacUserConfig,
      style?: TacStyle,
    ) => Promise<TacInstance>;
    TAC?: unknown;
  }
}
