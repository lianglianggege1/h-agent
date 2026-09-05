# 登录与注册滑块验证设计规格

- 日期：2026-09-05
- 状态：设计冻结，待实现
- 适用范围：`backend`、`frontend`
- 依赖调研：[tianai-captcha 登录/注册滑块验证调研](../../research/tianai-captcha.md)
- 关联设计：[用户登录注册前后端模块设计](2026-05-13-auth-design.md)

本文扩展原认证设计；两者冲突时，登录/注册请求字段、提交顺序、匿名放行路径和错误语义以本文为准，其余认证行为仍以原设计及当前实现为准。

## 1. 结论摘要

登录和注册采用“两阶段一次性凭证”流程：用户先完成 tianai-captcha 滑块，后端校验成功后签发短时、一次性的 `captchaProof`；登录或注册请求必须原子消费该 proof，随后才能进入现有密码认证或用户创建流程。

```text
表单本地校验
    |
    v
生成 SLIDER challenge -----> Redis challenge + challenge metadata
    |
    v
用户提交滑动轨迹
    |
    v
tianai-captcha matching + 元数据校验
    |
    v
签发一次性 captchaProof ---> Redis proof
    |
    v
POST /api/auth/login 或 /api/auth/register
    |
    v
原子消费并校验 proof ------> 现有 AuthService 逻辑
```

本设计冻结以下决定：

1. 第一版对每次登录、注册提交都执行滑块验证，不采用失败若干次后才触发的自适应策略。
2. 使用 `cloud.tianai.captcha:tianai-captcha-springboot-starter:1.5.5`，所有上游调用封装在 Adapter 内，并先通过 Spring Boot 4.0.6 + Java 26 兼容性门禁。
3. challenge 正确答案、challenge metadata、proof 和限流计数均使用 Redis；生产环境不得回退到进程内状态。
4. 使用项目自有 `captchaProof`，不启用或依赖上游 `secondaryVerification`。
5. proof 绑定 `purpose + 规范化邮箱指纹`，短时有效且只能消费一次。
6. 验证码 HTTP 端点保留官方 Web SDK 的 `code/msg/data` 协议；现有认证端点继续使用项目的 `code/message/data` 协议。
7. 官方 Web SDK 静态制品固定版本并随前端部署，不从作者域名运行时加载。
8. 验证码不可用时认证 fail closed，不提供跳过开关或降级通路。

## 2. 目标与非目标

### 2.1 目标

- 阻止匿名调用者直接绕过滑块批量尝试登录或注册。
- 阻止 challenge、验证结果和 proof 的重复使用、跨用途使用及更换邮箱使用。
- 保持现有 JWT、HttpOnly Cookie、用户/角色表和认证成功响应不变。
- 登录与注册复用同一套后端 Human Verification 模块和前端滑块模块。
- 支持当前 Next.js 同源 `/api` 代理、桌面浏览器和移动端触摸操作。
- 在多实例部署下保持一致的一次性消费语义。

### 2.2 非目标

- 不实现短信、邮件验证码或无障碍替代验证；这些属于后续独立需求。
- 不在第一版实现风险评分、自适应触发、设备指纹或第三方风控平台。
- 不修改 JWT claim、登录态存储方式或注册后的跳转策略。
- 不把验证码当作唯一的防机器人手段；限流和现有密码安全策略仍然必需。
- 不允许匿名请求选择图片 URL、文件路径、验证码模板或任意验证码类型。

## 3. 当前集成点

现有代码的变化集中在以下 seam：

- `AuthController` 接收登录/注册请求并保持成功响应形状不变。
- `AuthService` 在读取用户、比对密码、检查重复邮箱或开启注册事务前消费 proof。
- `SecurityConfig` 新增匿名可访问的 challenge 和 verification 路径。
- `frontend/lib/auth.ts` 的登录、注册 payload 增加 `captchaProof`。
- 登录和注册页面在原有本地表单校验之后打开共享 `SliderCaptchaDialog`。

认证 Cookie 仍由后端写入，前端继续使用 `credentials: "include"`，本设计不恢复 localStorage token。

## 4. 领域语言与安全不变量

### 4.1 术语

| 术语 | 定义 |
| --- | --- |
| `CaptchaPurpose` | proof 可用于的业务动作，第一版仅有 `LOGIN`、`REGISTER` |
| challenge | tianai-captcha 生成的一次滑块题目及其正确答案缓存 |
| challenge metadata | 项目保存的 challenge 用途、邮箱指纹和签发时间 |
| track | 官方 Web SDK 收集的图片尺寸、起止时间和坐标点列 |
| `captchaProof` | challenge 校验成功后签发的项目自有不透明随机凭证 |
| subject | 当前登录或注册邮箱经规范化后的逻辑主体 |
| subject fingerprint | 使用专用密钥对规范化邮箱计算的 HMAC-SHA-256 |

### 4.2 必须始终成立的不变量

1. challenge 最多进入一次 `matching`；校验成功或失败后都不能重试。
2. challenge metadata 在校验前原子消费；用途或邮箱不匹配时该 challenge 同样失效。
3. 只有 tianai-captcha 校验成功才能创建 proof。
4. proof 最多被一个登录或注册请求成功消费；并发请求中最多一个跨过 proof seam。
5. `LOGIN` proof 不能用于 `REGISTER`，反之亦然。
6. proof 签发和消费使用同一个邮箱规范化规则。
7. proof 失效、过期、重放、用途错误和邮箱错误对认证调用者呈现同一错误语义。
8. proof 在查询用户、比对密码、检查邮箱是否存在或写数据库之前消费。
9. 密码不进入 challenge 或 verification 请求，不进入验证码日志和 Redis 数据。
10. Redis、验证码引擎或 proof 存储异常时不执行认证业务逻辑。
11. raw proof 不写日志、不放 URL、不持久化在浏览器存储中；Redis key 只保存 proof 的 SHA-256。

## 5. 后端模块设计

### 5.1 外部 seam：`HumanVerification`

`HumanVerification` 是认证模块和验证码 Web 层共同使用的深模块 interface。调用者只需要理解三种动作，不感知 tianai-captcha、Redis key、随机数、HMAC 或上游错误码。

```java
public interface HumanVerification {
    CaptchaChallenge issueChallenge(CaptchaPurpose purpose, String email, ClientContext client);

    CaptchaProof solveChallenge(CaptchaSolution solution, ClientContext client);

    void consumeProof(String rawProof, CaptchaPurpose purpose, String email);
}
```

interface 的行为约束：

- `issueChallenge` 只生成 `SLIDER`，成功时 challenge 与 metadata 均已写入并具有相同 TTL。
- `solveChallenge` 消费 challenge metadata 和上游正确答案；失败不产生 proof。
- `consumeProof` 原子读取并删除 proof；任何不匹配均抛出统一的 proof invalid 错误。
- `ClientContext` 只承载经可信代理解析后的来源 IP 等限流信息，不作为第一版 proof 的强绑定字段。

### 5.2 内部 seam 与 Adapter

| seam | 生产 Adapter | 职责 |
| --- | --- | --- |
| `CaptchaEngine` | `TianaiCaptchaAdapter` | 生成 SLIDER、校验 track、翻译上游结果 |
| `CaptchaStateStore` | `RedisCaptchaStateStore` | challenge metadata、proof、原子消费和 TTL |
| `CaptchaRateLimiter` | `RedisCaptchaRateLimiter` | issue、solve 和 proof consume 的业务维度限流 |

测试使用内存 fake 替换内部 Adapter；生产配置只能装配 Redis Adapter。应用启动时必须断言实际 `CacheStore` 不是 `LocalCacheStore`。

### 5.3 包结构建议

```text
backend/src/main/java/com/h/backend/captcha/
  application/
    HumanVerification.java
    impl/HumanVerificationImpl.java
  domain/
    CaptchaPurpose.java
    CaptchaChallenge.java
    CaptchaSolution.java
    CaptchaProof.java
    CaptchaErrors.java
  interfaces/
    dto/CaptchaRequests.java
    dto/CaptchaResponses.java
    web/CaptchaController.java
  infrastructure/
    config/CaptchaConfiguration.java
    redis/RedisCaptchaStateStore.java
    redis/RedisCaptchaRateLimiter.java
    tianai/TianaiCaptchaAdapter.java
```

`auth` 只依赖 `HumanVerification` 和 `CaptchaPurpose`，不能直接依赖 tianai-captcha DTO 或 Redis。

### 5.4 tianai-captcha 装配

- 依赖固定为 `1.5.5`，不使用浮动版本。
- `captcha.secondary.enabled=false`；二次验证由项目 proof 实现。
- `CacheStore` 明确使用 Redis，实现 challenge 的跨实例原子消费。
- 资源 store 使用进程内、只读的受信 classpath 配置；它只保存资源描述，不保存 challenge 状态。
- 第一版只注册 `SLIDER` 所需资源，至少包含经审核的背景图和滑块模板。
- 图片通过 `Base64ImageTransform` 返回，前端 CSP 必须允许 `img-src data:`。
- 默认 `SimpleImageCaptchaValidator` 不能作为完整安全配置。实现阶段必须选择并验证 `BasicCaptchaTrackValidator`，或使用参数校验与基础轨迹拦截器组合。
- 基础轨迹规则属于启发式规则；真实鼠标和触屏回放测试通过后才能开启生产门禁。

建议配置：

```yaml
captcha:
  prefix: h-agent:captcha:answer
  expire:
    default: 120000
    SLIDER: 120000
  init-default-resource: false
  local-cache-enabled: true
  local-cache-size: 20
  local-cache-wait-time: 1000
  local-cache-period: 5000
  secondary:
    enabled: false

human-verification:
  challenge-metadata-ttl: 120s
  proof-ttl: 90s
  key-prefix: h-agent:human-verification
  subject-hmac-secret: ${CAPTCHA_SUBJECT_HMAC_SECRET:}
```

`CAPTCHA_SUBJECT_HMAC_SECRET` 是独立密钥，不复用 `JWT_SECRET`；为空时生产配置启动失败。

## 6. Redis 数据设计

### 6.1 Key

```text
# 上游正确答案，由 tianai-captcha 管理
h-agent:captcha:answer:{challengeId}

# 项目 challenge metadata
h-agent:human-verification:challenge:{challengeId}

# 项目 proof；proofHash = SHA-256(rawProof)
h-agent:human-verification:proof:{proofHash}

# 限流
h-agent:human-verification:rate:{operation}:{dimensionHash}:{window}
```

Redis key 不包含明文邮箱、raw proof、密码或完整 User-Agent。

### 6.2 Challenge metadata

```json
{
  "purpose": "LOGIN",
  "subjectFingerprint": "hmac-sha256:...",
  "issuedAt": "2026-09-05T12:00:00Z"
}
```

metadata TTL 与上游 challenge TTL 均为 120 秒。验证时先对 metadata 执行 `GETDEL`，再调用上游 `matching`；两个动作不需要分布式事务：任一步中断都使 challenge 不可继续使用，保持 fail closed。

### 6.3 Proof

raw proof 使用 CSPRNG 生成至少 256 bit 随机值并编码为 Base64url，不携带可解析业务数据。Redis 只保存它的 SHA-256：

```json
{
  "purpose": "LOGIN",
  "subjectFingerprint": "hmac-sha256:...",
  "issuedAt": "2026-09-05T12:00:10Z"
}
```

proof TTL 默认为 90 秒。签发使用 `SET ... NX EX`；消费使用 Redis `GETDEL` 或等价 Lua。若出现极低概率的 key 冲突，重新生成随机值，不覆盖现有 proof。

### 6.4 邮箱规范化

challenge 签发、challenge 校验和 proof 消费统一执行：

```text
normalize(email) = email.trim().toLowerCase(Locale.ROOT)
subjectFingerprint = HMAC-SHA-256(dedicatedSecret, normalize(email))
```

该规则仅定义验证码主体绑定；是否将存量账号邮箱统一为小写不在本设计范围内。

## 7. HTTP 契约

### 7.1 协议分离

验证码端点由官方 Web SDK 直接调用，使用：

```json
{"code": 200, "msg": "OK", "data": {}}
```

认证端点继续使用项目现有协议：

```json
{"code": 0, "message": "OK", "data": {}}
```

两种协议的翻译只存在于 `CaptchaController`，不得扩散到 `AuthController`、`AuthService` 或通用 `ApiResponse`。

### 7.2 生成 challenge

`POST /api/captcha/challenges`

请求：

```json
{
  "purpose": "LOGIN",
  "email": "user@example.com"
}
```

官方 SDK 默认发送空对象；前端 Adapter 必须通过 request chain 给生成请求注入 `purpose` 和 `email`。

成功：HTTP 200。

```json
{
  "code": 200,
  "msg": "OK",
  "data": {
    "id": "SLIDER_...",
    "type": "SLIDER",
    "backgroundImage": "data:image/jpeg;base64,...",
    "templateImage": "data:image/png;base64,...",
    "backgroundImageWidth": 600,
    "backgroundImageHeight": 360,
    "templateImageWidth": 110,
    "templateImageHeight": 360,
    "data": null
  }
}
```

约束：

- `purpose` 必须是 `LOGIN` 或 `REGISTER`。
- `email` 必须满足与认证 DTO 相同的格式和长度上限。
- 客户端不能传验证码类型、资源地址、模板或容差。
- 返回前必须确认 challenge metadata 已写入 Redis。

### 7.3 校验 challenge

`POST /api/captcha/verifications`

请求：

```json
{
  "id": "SLIDER_...",
  "purpose": "LOGIN",
  "email": "user@example.com",
  "data": {
    "bgImageWidth": 300,
    "bgImageHeight": 180,
    "templateImageWidth": 55,
    "templateImageHeight": 180,
    "startTime": 1788580000000,
    "stopTime": 1788580000834,
    "trackList": [
      {"x": 120, "y": 600, "t": 0, "type": "down"},
      {"x": 238, "y": 601, "t": 834, "type": "up"}
    ]
  }
}
```

成功：HTTP 200。

```json
{
  "code": 200,
  "msg": "OK",
  "data": {
    "captchaProof": "opaque-base64url-value",
    "expiresIn": 90
  }
}
```

预期校验失败仍返回 HTTP 200，以便官方 SDK 展示失败并重新加载：

```json
{"code": 4001, "msg": "验证失败，请重新尝试", "data": null}
```

challenge 过期或已消费：

```json
{"code": 4000, "msg": "验证已失效，请重新尝试", "data": null}
```

校验请求限制：

- 请求体最大 64 KiB。
- `trackList` 为 2～512 个点。
- 所有尺寸为 1～2000 的整数。
- `startTime <= stopTime`，总时长不超过 30 秒。
- `x/y/t` 必须是有限数值，`t` 非负且按顺序不递减。
- `type` 仅接受 allowlist 中的 `down`、`move`、`up`。

格式错误返回 HTTP 400；限流返回 HTTP 429；依赖不可用返回 HTTP 503。响应仍使用 `code/msg/data`，前端 Adapter 负责转换为稳定用户提示。

### 7.4 登录

`POST /api/auth/login`

```json
{
  "email": "user@example.com",
  "password": "P@ssw0rd123",
  "captchaProof": "opaque-base64url-value"
}
```

成功响应、JWT 和 Cookie 行为保持现状。proof 缺失、无效、过期、重放、用途错误或邮箱错误统一返回：

```json
{
  "code": 40003,
  "message": "请重新完成滑块验证",
  "data": null
}
```

HTTP 状态为 400。账号或密码错误仍使用现有 `40101`，但 proof 已经被消费，下一次登录必须重新完成滑块。

### 7.5 注册

`POST /api/auth/register`

```json
{
  "email": "user@example.com",
  "password": "P@ssw0rd123",
  "captchaProof": "opaque-base64url-value"
}
```

proof 错误与登录相同。邮箱已注册仍使用现有 `40002`，但 proof 已经被消费，下一次注册提交必须重新完成滑块。

### 7.6 错误映射

| 场景 | HTTP | 项目错误码 | 用户消息 |
| --- | ---: | ---: | --- |
| auth proof 无效的所有情况 | 400 | `40003` | 请重新完成滑块验证 |
| captcha 请求参数错误 | 400 | `40001` | 参数错误 |
| captcha/auth 限流 | 429 | `42901` | 操作过于频繁，请稍后再试 |
| Redis 或验证码引擎不可用 | 503 | `50301` | 验证服务暂时不可用，请稍后重试 |

`GlobalExceptionHandler` 必须增加 429xx→429、503xx→503 映射。Captcha Web Adapter 将项目错误映射到相同 HTTP 状态下的 `code/msg/data`。

## 8. 端到端流程

### 8.1 Challenge 签发

1. 校验 `purpose` 和 email。
2. 执行 issue 限流。
3. 规范化 email 并计算 subject fingerprint。
4. 调用 `CaptchaEngine.generateSlider()`。
5. 保存 challenge metadata，TTL 120 秒。
6. 返回上游 SDK 协议；metadata 保存失败时不向客户端暴露 challenge，遗留答案依靠 TTL 回收。

### 8.2 Challenge 校验与 proof 签发

1. 校验请求体和 track 上限。
2. 执行 solve 限流。
3. `GETDEL` challenge metadata。
4. 比较 purpose 和 subject fingerprint；不匹配即返回失败。
5. 调用 tianai-captcha `matching`；该调用原子消费正确答案。
6. 匹配失败时返回 `4001`，不创建 proof。
7. 匹配成功后生成 raw proof，以 `SET NX EX` 保存 proof hash 和绑定信息。
8. 仅将 raw proof 返回一次。

### 8.3 登录/注册

1. Controller 完成基础 JSON 反序列化和邮箱、密码格式校验。
2. AuthService 调用 `consumeProof(rawProof, expectedPurpose, email)`。
3. proof 原子消费并完成用途、subject fingerprint 比较。
4. proof 有效后进入现有登录或注册流程。
5. 后续账号密码错误、邮箱重复或数据库失败不恢复 proof。

## 9. 前端模块设计

### 9.1 外部 seam：`SliderCaptchaDialog`

```ts
type SliderCaptchaDialogProps = {
  open: boolean;
  purpose: "LOGIN" | "REGISTER";
  email: string;
  onVerified: (captchaProof: string) => void;
  onCancel: () => void;
};
```

登录、注册页面不接触 `window.initTAC`、官方响应码或轨迹结构。所有 SDK 生命周期、request chain、错误翻译和静态路径集中在该模块。

建议文件：

```text
frontend/components/auth/slider-captcha-dialog.tsx
frontend/lib/captcha.ts
frontend/types/tianai-captcha.d.ts
frontend/public/vendor/tianai-captcha/1.5.5/load.min.js
frontend/public/vendor/tianai-captcha/1.5.5/tac/**
frontend/public/vendor/tianai-captcha/1.5.5/LICENSE
```

### 9.2 表单状态

```text
editing
  -> captcha_open
  -> auth_submitting
  -> success
  -> error -> editing
```

规则：

- 页面先执行现有邮箱、密码和确认密码校验，通过后才打开滑块。
- 打开时固定一份 `purpose + email` 快照；SDK 生成和校验请求都使用该快照。
- 密码只保留在页面内存，不传给验证码模块。
- `onVerified` 后立即关闭滑块并用 proof 提交认证请求。
- 用户关闭滑块、页面卸载、认证失败或输入改变时清除当前 proof。
- 收到 `40003` 时提示重新验证；不自动重放上一次认证请求。
- `submitting` 期间禁止重复打开滑块和重复提交认证。
- SDK 初始化 promise、全局脚本加载和 `destroyWindow()` 必须正确处理 React Strict Mode 的重复挂载。

### 9.3 SDK 协议适配

- `requestCaptchaDataUrl=/api/captcha/challenges`。
- `validCaptchaUrl=/api/captcha/verifications`。
- request chain 在两类请求中注入 `purpose`、`email`。
- `validSuccess` 读取 `res.data.captchaProof`，然后调用 `onVerified`。
- `validFail` 清除本地 proof 并调用 `reloadCaptcha()`。
- 网络错误、429、503 转换为页面可理解的中文提示。

### 9.4 静态制品与浏览器策略

- 从官方 `1.5.5` Release 获取 Web SDK，固定目录版本，记录来源和校验值。
- 随制品保留 Apache-2.0 LICENSE/NOTICE 信息。
- 页面只加载同源脚本和样式，不引用作者 MinIO/CDN。
- CSP 至少允许 `script-src 'self'`、`style-src 'self'` 和 `img-src 'self' data:`。
- 320 px 宽度和移动端安全区必须实测；弹层不得被现有卡片 padding 裁剪。
- 滑块本身缺少完整键盘替代能力；上线说明中记录该已知限制，后续以邮件验证码等独立模块补齐。

## 10. 限流与防滥用

默认值作为配置提供，不写死在 Controller：

| 操作 | 维度 | 默认限制 | 执行位置 |
| --- | --- | --- | --- |
| issue challenge | 来源 IP | 20 次/分钟 | `CaptchaRateLimiter` |
| solve challenge | 来源 IP | 20 次/分钟 | `CaptchaRateLimiter` |
| solve challenge | subject fingerprint | 10 次/分钟 | `CaptchaRateLimiter` |
| login | 来源 IP | 10 次/10 分钟 | 受信网关或登录 HTTP interceptor |
| login | subject fingerprint | 5 次/10 分钟 | `consumeProof` |
| register | 来源 IP | 10 次/10 分钟 | 受信网关或注册 HTTP interceptor |
| register | subject fingerprint | 5 次/10 分钟 | `consumeProof` |

限流 key 使用固定窗口或 Lua 原子滑动窗口；第一版只需一种实现。`HumanVerification` 负责 challenge 和 subject 维度，登录/注册的 IP 维度在解析请求体之前执行。客户端 IP 只能从直连地址或受信反向代理传入的转发头解析，不能无条件信任任意 `X-Forwarded-For`。

限流与滑块是两层独立防护：通过滑块不会清空认证限流，认证成功可按后续策略清理 subject 失败计数。

## 11. 可观测性与运维

### 11.1 指标

建议增加：

```text
captcha_challenge_issued_total
captcha_challenge_solve_total{outcome=success|mismatch|expired|invalid_binding|error}
captcha_proof_consume_total{outcome=success|invalid|error,purpose=login|register}
captcha_rate_limited_total{operation=issue|solve|login|register}
captcha_operation_duration_seconds{operation=issue|solve|consume}
```

标签不得包含 challenge ID、proof、邮箱、IP 或 User-Agent。

### 11.2 日志

- INFO：依赖版本、生产是否使用 Redis、资源数量、配置 TTL，不记录资源内容。
- WARN：限流、无效 proof、challenge 过期，以聚合维度或请求 trace 记录，不记录原始身份值。
- ERROR：Redis/验证码引擎故障，保留异常类型和 trace，响应使用脱敏固定文案。
- 图片 Base64、完整 track、raw proof、密码和完整邮箱禁止进入日志与 tracing content。

### 11.3 健康与告警

- Redis 已是共享依赖；验证码健康检查关注 challenge 写入/原子消费能力，而非仅 TCP 连通。
- `captcha_challenge_solve_total{outcome=error}` 或 `captcha_proof_consume_total{outcome=error}` 持续增长时告警。
- mismatch 比例只用于趋势观察，不能单独判定攻击或封禁用户。

## 12. 预计文件影响面

### 12.1 后端

- 修改 `backend/pom.xml`：增加固定版本依赖。
- 修改 `backend/src/main/resources/application.yml`：增加 captcha 与 human-verification 配置。
- 新增 `backend/.../captcha/**` 模块。
- 修改 `LoginRequest`、`RegisterRequest`：增加 `captchaProof`。
- 修改 `AuthServiceImpl`：在现有业务逻辑前消费 proof。
- 修改 `SecurityConfig`：放行两个验证码端点。
- 修改 `GlobalExceptionHandler`：补齐 429/503 映射。
- 增加经审核的 classpath 背景图资源。

### 12.2 前端

- 新增官方固定版本静态制品和许可证。
- 新增 `SliderCaptchaDialog`、SDK Adapter 和全局类型声明。
- 修改 `frontend/lib/auth.ts` payload。
- 修改登录、注册页面提交状态机。
- 按需增加 CSP 或资源加载配置。

### 12.3 不需要的变化

- 不新增数据库表或 Flyway 迁移。
- 不修改用户、角色、JWT、Cookie 名称和认证成功响应。
- 不修改 `/api/auth/me`、`/api/auth/logout`。

## 13. 测试策略与清单

以下清单是实现完成的验收门禁。测试应通过模块 interface 和 HTTP seam 验证行为；除 Redis 原子语义与 tianai 兼容性外，不直接测试第三方实现内部细节。

### 13.1 依赖与运行时兼容性

- [ ] Spring Boot 4.0.6 + Java 26 能加载 tianai-captcha 1.5.5，ApplicationContext 启动成功。
- [ ] `ImageCaptchaApplication`、`CacheStore`、validator/interceptor 和资源 store 的实际 Bean 类型符合设计。
- [ ] 存在 Redis 时使用 Redis `CacheStore`；生产配置若装配 `LocalCacheStore` 则启动失败。
- [ ] 在项目 Docker/headless 环境生成 100 次 SLIDER，无 AWT、字体、图片解码或线程异常。
- [ ] 生成和校验后关闭应用，无验证码预生成线程泄漏或关闭异常。
- [ ] Maven 依赖树无 Spring 2.x、Jackson 旧版或日志实现冲突。
- [ ] 官方 1.5.5 Web SDK 制品来源、版本、许可证和 SHA-256 被记录。

### 13.2 Human Verification 模块测试

- [ ] issue 成功返回 SLIDER，并写入相同用途和 subject fingerprint 的 metadata。
- [ ] issue 的 metadata TTL 与 challenge TTL 对齐。
- [ ] metadata 写入失败时不返回可用 challenge。
- [ ] solve 成功签发至少 256 bit 的随机 proof，并只在 Redis 保存 proof hash。
- [ ] 两次签发产生不同 proof。
- [ ] 上游 matching 失败时不签发 proof。
- [ ] challenge 过期时不签发 proof。
- [ ] challenge metadata 被消费后不能再次 solve。
- [ ] purpose 不匹配时 challenge 失效且不进入 matching。
- [ ] email fingerprint 不匹配时 challenge 失效且不进入 matching。
- [ ] proof 在 TTL 内能被正确 purpose 和 email 消费一次。
- [ ] proof 缺失、空白、过期、篡改或随机值统一失败。
- [ ] LOGIN proof 用于 REGISTER 失败，REGISTER proof 用于 LOGIN 失败。
- [ ] proof 更换邮箱后失败。
- [ ] proof 成功消费后再次消费失败。
- [ ] 100 个并发消费者竞争同一 proof，恰好一个成功。
- [ ] Redis 在 issue、solve、proof issue、proof consume 任一阶段异常均 fail closed。
- [ ] email 的 trim 和大小写规范化在 issue、solve、consume 三处一致。

### 13.3 tianai Adapter 与轨迹测试

- [ ] `generateCaptcha(SLIDER)` 正确映射为内部 `CaptchaChallenge`，不泄漏正确位置。
- [ ] Adapter 只允许 SLIDER，调用者无法注入资源、模板、类型或容差。
- [ ] 正确轨迹返回 success，错误终点返回 mismatch。
- [ ] 同一 challenge 的第一次错误匹配后，第二次正确轨迹仍返回 expired。
- [ ] 轨迹参数校验拒绝空点列、缺失尺寸、逆序时间和非有限数值。
- [ ] 基础轨迹规则拒绝过短、点数异常和明显机器轨迹。
- [ ] 真实鼠标慢速、正常、快速滑动样本均能通过合理比例。
- [ ] Android/iOS 触摸轨迹能通过，坐标归一化不会改变最终位移百分比。
- [ ] 多个应用实例通过共享 Redis 生成和校验 challenge。

### 13.4 Captcha HTTP contract 测试

- [ ] 未登录可调用 `/api/captcha/challenges` 和 `/api/captcha/verifications`。
- [ ] 其他受保护接口仍需认证。
- [ ] challenge 成功响应严格使用 `code=200`、`msg`、`data`。
- [ ] challenge 响应包含 SDK 所需 id、type、图片和尺寸字段。
- [ ] verification 成功响应包含 `data.captchaProof` 和 `expiresIn`。
- [ ] mismatch 使用 HTTP 200 + `code=4001`。
- [ ] expired/consumed 使用 HTTP 200 + `code=4000`。
- [ ] 非法 purpose、email、id 和 track 返回 HTTP 400，不抛出未处理异常。
- [ ] 超过 64 KiB 或超过 512 个轨迹点的请求被拒绝。
- [ ] issue/solve 限流返回 HTTP 429 和稳定协议。
- [ ] Redis/引擎故障返回 HTTP 503 和脱敏文案。
- [ ] 响应中不存在正确位置、subject fingerprint、Redis key 或内部异常。

### 13.5 Auth 模块与 Controller 集成测试

- [ ] 登录缺少 proof 返回 `40003`，不查询用户、不比对密码、不签发 JWT。
- [ ] 注册缺少 proof 返回 `40003`，不查询重复邮箱、不写数据库。
- [ ] 有效 LOGIN proof + 正确密码登录成功，响应和 Cookie 保持现状。
- [ ] 有效 REGISTER proof 注册成功并创建默认 USER 角色。
- [ ] LOGIN proof 不能注册，REGISTER proof 不能登录。
- [ ] proof 对应邮箱与请求邮箱不一致时返回 `40003`。
- [ ] 过期、篡改和已使用 proof 均返回相同 `40003` 文案。
- [ ] 错误密码会消费有效 proof；用相同 proof 改成正确密码仍失败。
- [ ] 重复邮箱会消费有效 proof；相同 proof 不能再次注册。
- [ ] 数据库事务失败不恢复 proof。
- [ ] 两个并发登录请求使用同一 proof，最多一个进入密码认证。
- [ ] 现有错误码 `40001`、`40002`、`40101`、`40102` 行为除 proof 前置门禁外保持不变。
- [ ] `/api/auth/me`、logout、Bearer Token 和 HttpOnly Cookie 回归测试通过。
- [ ] `GlobalExceptionHandler` 正确映射 42901→429、50301→503。

### 13.6 前端模块测试

- [ ] SDK 脚本只加载一次，多次打开弹层不会重复插入资源。
- [ ] React Strict Mode 重复 mount/unmount 不留下 DOM、计时器或全局事件。
- [ ] 表单本地校验失败时不打开滑块、不调用 challenge。
- [ ] 登录使用 `purpose=LOGIN`，注册使用 `purpose=REGISTER`。
- [ ] challenge 和 verification 都携带同一份 email 快照。
- [ ] 验证码请求不包含 password 或 confirmPassword。
- [ ] validSuccess 读取 proof 并恰好提交一次认证请求。
- [ ] 登录、注册 payload 都包含 `captchaProof`。
- [ ] validFail 清除 proof 并加载新 challenge。
- [ ] 用户关闭弹层不会提交认证。
- [ ] 输入发生变化时现有 proof 被清除。
- [ ] auth 返回 `40003` 后提示重新验证，不自动重放密码请求。
- [ ] 429、503、脚本加载失败和网络错误显示稳定中文提示并允许重试。
- [ ] auth submitting 状态禁用按钮，快速双击不会产生重复 challenge/auth 请求。
- [ ] 320 px 宽度、桌面宽度和移动端触摸布局无裁剪、横向滚动或遮挡。

### 13.7 端到端测试

- [ ] 注册：填写表单→完成滑块→注册成功→跳转登录页。
- [ ] 登录：填写表单→完成滑块→设置 Cookie→跳转目标页→`/api/auth/me` 成功。
- [ ] 滑块失败后自动换题，旧 challenge 不能再次使用。
- [ ] 滑块成功后等待 proof 过期，认证提示重新验证。
- [ ] 滑块成功后修改邮箱，认证被拒绝并要求重新验证。
- [ ] 同一 proof 在两个浏览器请求中并发使用，只有一个认证请求成功跨过 proof seam。
- [ ] 后端双实例场景下，实例 A 生成 challenge、实例 B 校验、实例 A/B 任一消费 proof 均正常。
- [ ] Redis 中断时 challenge、verification、login、register 均无绕过路径；恢复后新流程可用。

自动化 E2E 可在测试 profile 注入确定位置的 `CaptchaEngine` Adapter；发布前必须额外执行一次真实 tianai-captcha + 真实浏览器人工滑动冒烟，避免 fake 掩盖 SDK/轨迹兼容问题。

### 13.8 安全与隐私检查

- [ ] raw proof 不出现在 Redis key、服务日志、访问日志、trace、指标标签或浏览器 URL。
- [ ] 密码不出现在 challenge/verification 请求、验证码状态或诊断日志。
- [ ] Redis 中不存明文邮箱；subject fingerprint 使用独立 HMAC 密钥。
- [ ] 匿名请求无法选择 URL/file resource provider 或读取服务器文件。
- [ ] challenge、proof 和限流 key 都具有 TTL，不产生永久垃圾数据。
- [ ] 请求体、轨迹点数、数值范围和生成频率均有限制。
- [ ] 客户端伪造 `X-Forwarded-For` 不能绕过 IP 限流。
- [ ] Redis key 和错误响应不泄漏账号是否存在。
- [ ] 依赖故障、超时和异常路径全部 fail closed。
- [ ] 前端仅从同源加载固定 SDK，不存在作者 CDN 的供应链运行时依赖。

### 13.9 可观测性与回归

- [ ] issue、solve、consume 的成功、失败、过期、限流、依赖错误指标正确增加。
- [ ] 指标标签基数受控，不包含用户输入。
- [ ] 故障日志包含 trace 和错误类别，但不包含敏感字段。
- [ ] 错误率告警可由测试指标触发并恢复。
- [ ] 后端全量测试通过。
- [ ] 前端 test、lint、build 全部通过。
- [ ] 认证以外的聊天、用户信息、logout 和未认证跳转行为无回归。

## 14. 实现顺序与完成标准

### 14.1 实现顺序

1. 先完成依赖兼容性和真实 SDK 协议 tracer test；完成标准是 Spring 上下文、Redis、一次真实滑动闭环均通过。
2. 实现 Human Verification interface、Redis Adapter 和 fake；完成标准是一次性与绑定不变量的模块测试全部通过。
3. 实现 CaptchaController 和协议测试；完成标准是官方 SDK 可以不修改核心代码完成 generate/verify。
4. 将 proof seam 接入 AuthService；完成标准是所有绕过、重放、跨用途和错误凭证测试通过。
5. 实现前端 SliderCaptchaDialog 并接入两个页面；完成标准是前端模块与 E2E 清单通过。
6. 加入限流、指标、生产配置和 runbook；完成标准是故障注入、双实例和全量回归通过。

### 14.2 发布门禁

以下条件全部满足才视为实现完成：

- 本文 13.1～13.9 中所有适用复选项完成；跳过项必须在 PR 中说明原因和替代证据。
- 真实 Spring Boot 4.0.6 + Java 26 + Redis 环境完成 generate→solve→proof→login/register 闭环。
- 并发重放测试证明 challenge 和 proof 各自最多一次成功。
- Redis 故障注入证明登录和注册没有绕过路径。
- 官方 SDK 固定制品、许可证、来源和校验值进入仓库。
- 全量后端测试、前端 test/lint/build 和移动端人工冒烟通过。

## 15. 后续演进

第一版稳定后可独立评估：

- 根据失败次数、IP/subject 风险和设备信号改为自适应触发。
- 增加邮件验证码作为无障碍和验证码故障时的受控替代模块。
- 引入更强的行为模型或外部风控 Adapter，同时保持 `HumanVerification` interface 不变。
- 将登录限流从固定窗口升级为滑动窗口或令牌桶。
- 为背景图和模板建立独立的版权、审核与轮换流程。

这些演进不得削弱一次性 proof、用途/邮箱绑定和 fail-closed 不变量。
