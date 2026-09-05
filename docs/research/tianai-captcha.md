# tianai-captcha 登录/注册滑块验证调研

> 日期：2026-09-05  
> 状态：官方 README、Release、Maven Central 与源码核验完成；仅作为设计依据，不含实现代码

## 1. 版本与 Spring Boot 4 兼容性

- 当前最新稳定制品是 `cloud.tianai.captcha:tianai-captcha-springboot-starter:1.5.5`。官方 Release 将 1.5.5 标为 Latest，并明确写明支持 Spring Boot 4；Maven Central 已发布该版本。[官方 Release](https://github.com/dromara/tianai-captcha/releases/tag/1.5.5)、[Maven Central](https://central.sonatype.com/artifact/cloud.tianai.captcha/tianai-captcha-springboot-starter/1.5.5)
- 1.5.5 仍以 Java 8 字节码构建，starter POM 内部还引用 Spring Boot 2.2.7 BOM，但源码增加了 Boot 4 Redis 自动配置类名适配，并提供 `AutoConfiguration.imports`。[starter POM](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-springboot-starter/pom.xml)、[Redis 自动配置](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-springboot-starter/src/main/java/cloud/tianai/captcha/spring/autoconfiguration/CacheStoreAutoConfiguration.java#L23-L52)、[AutoConfiguration.imports](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-springboot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
- 对本项目 Spring Boot 4.0.6 + Java 26，1.5.5 是应选版本，但上游没有公开 Java 26/Boot 4.0.6 测试矩阵。实现前必须做 ApplicationContext、Redis、生成/校验和应用关闭的集成冒烟测试。
- 上游有发布追溯异常：GitHub `1.5.5` tag 指向的 `387ed93` 提交内容仍标为 1.5.4；master 的 `e7b9fd9` 才更新为 1.5.5。Maven Central 1.5.5 sources 与后者内容一致。依赖应固定 1.5.5，但不要把 `git checkout 1.5.5` 当成可复现源码基线。[tag 指向提交](https://github.com/dromara/tianai-captcha/commit/387ed937f8ca89ff3801ab384bd6df4e9be7f551)、[master 版本提交](https://github.com/dromara/tianai-captcha/commit/e7b9fd923bdba6533014c46d03f349b5c1a985f6)

## 2. 后端生成、校验与一次性语义

生成流程：

1. Controller 调用 `ImageCaptchaApplication.generateCaptcha("SLIDER")`；
2. 库随机选择背景图/模板和缺口位置，生成背景图与滑块图；
3. 默认以 Base64 data URI 返回图片，同时把正确位置的百分比写入 `CacheStore`；
4. 返回 `ImageCaptchaVO`，包含 `id/type/backgroundImage/templateImage/尺寸`，不返回正确位置。

校验流程：

1. 前端提交 `id + ImageCaptchaTrack`；轨迹包含渲染尺寸、起止时间及 `x/y/t/type` 点列；
2. `matching(id, track)` 先通过 `getAndRemoveCache` 取出并删除正确答案，再做校验；
3. 因此同一 challenge 无论成功还是失败都只能尝试一次；Redis 实现使用 Lua 原子 GET + DEL，可防并发重放。[生成与校验源码](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha/src/main/java/cloud/tianai/captcha/application/DefaultImageCaptchaApplication.java#L100-L243)、[RedisCacheStore](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-springboot-starter/src/main/java/cloud/tianai/captcha/spring/store/impl/RedisCacheStore.java#L22-L51)

安全上不能直接采用默认 validator：Spring Boot starter 默认装配 `SimpleImageCaptchaValidator + EmptyCaptchaInterceptor`，只检查滑块终点百分比，默认容差为 0.02，不分析速度、Y 轴变化或时长。应显式注册 `BasicCaptchaTrackValidator`，但它也只是固定阈值的基础启发式规则，仍需配合接口限流。[默认装配](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-springboot-starter/src/main/java/cloud/tianai/captcha/spring/autoconfiguration/ImageCaptchaAutoConfiguration.java#L72-L82)、[Simple 校验](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha/src/main/java/cloud/tianai/captcha/validator/impl/SimpleImageCaptchaValidator.java#L337-L375)、[Basic 轨迹校验](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha/src/main/java/cloud/tianai/captcha/validator/impl/BasicCaptchaTrackValidator.java#L45-L109)

## 3. 官方 Web SDK 协议

官方前端不是 React npm 组件，而是把构建好的 `tac` 静态目录和 `load.min.js` 放进站点，通过 `window.initTAC(tacPath, config, style)` 挂载；README 声明兼容 React、Vue 和移动 WebView。[官方前端接入](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/readme.md#L158-L238)

生成请求：

```http
POST <requestCaptchaDataUrl>
Content-Type: application/json;charset=UTF-8

{}
```

生成响应必须是上游协议：

```json
{
  "code": 200,
  "msg": "OK",
  "data": {
    "id": "SLIDER_...",
    "type": "SLIDER",
    "backgroundImage": "data:image/jpeg;base64,...",
    "templateImage": "data:image/png;base64,..."
  }
}
```

校验请求：

```json
{
  "id": "SLIDER_...",
  "data": {
    "bgImageWidth": 300,
    "bgImageHeight": 180,
    "templateImageWidth": 34,
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

SDK 硬编码以 `code == 200` 判定成功；本项目现有接口以 `code=0` 表示成功，二者不兼容。验证码生成/校验 Controller 应保留 `code/msg/data` 上游协议，认证接口继续使用本项目 `code/message/data` 协议。[SDK 请求与判定](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-web-sdk/src/captcha/config/config.js#L61-L100)、[校验协议](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-web-sdk/src/captcha/config/config.js#L127-L176)、[轨迹组装](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-web-sdk/src/captcha/captcha.js#L126-L160)

前端实现建议封装 client-only `SliderCaptchaDialog`，把官方静态文件固定版本放进本项目 `public`，不要运行时从作者域名加载脚本；关闭/卸载时调用 `destroyWindow()`。

## 4. 缓存与二次验证

- 存在 `StringRedisTemplate` 时，starter 自动选择 `RedisCacheStore`；否则回退本机 `LocalCacheStore`。本项目是多实例可扩展架构，应明确断言使用 Redis，不能依赖进程内状态。[缓存自动配置](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-springboot-starter/src/main/java/cloud/tianai/captcha/spring/autoconfiguration/CacheStoreAutoConfiguration.java#L34-L75)
- README 对 challenge TTL 的文字和示例不一致，源码实际兜底是 20 秒；必须显式配置，例如 SLIDER 120 秒。[README 配置](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/readme.md#L65-L98)、[源码默认值](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha/src/main/java/cloud/tianai/captcha/application/DefaultImageCaptchaApplication.java#L55-L76)
- 上游 `secondary.enabled` 会在校验成功后以原 challenge id 写入一个空 Map，并由 `secondaryVerification(id)` 再次 GET+DEL；默认 TTL 120 秒。但它没有绑定登录/注册 purpose 或邮箱，而且只覆写 `matching(String, ImageCaptchaTrack)`，调用 `matching(String, MatchParam)` 不会产生二次记录。[二次验证源码](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-springboot-starter/src/main/java/cloud/tianai/captcha/spring/plugins/secondary/SecondaryVerificationApplication.java#L18-L57)

因此登录/注册应使用本项目自己的短期一次性 `captchaProof`：

```text
captcha verify success
  -> 生成随机 proof
  -> Redis SET proofHash {purpose, normalizedEmailFingerprint} EX 60~120 NX

login/register
  -> Redis GETDEL/Lua 原子消费 proof
  -> 核对 purpose + 邮箱指纹
  -> 再执行密码认证或注册事务
```

proof 不写日志、不放 URL、不落 localStorage；登录 proof 不能用于注册、不能更换邮箱、不能重放。

## 5. 最小安全边界

- 对 generate、verify、login、register 分别做应用/网关限流；Spring Boot starter 没有内置生成/校验限流器。
- verify 请求限制 body、轨迹点数量、尺寸、时间和数值范围，畸形输入返回受控 4xx。
- Redis/生成器异常必须 fail closed，不能跳过验证码。
- 资源 URL/文件路径只能来自受信配置；上游 provider 会直接 `URL.openStream()` 或 `FileInputStream`，绝不能让匿名请求指定资源路径。[URL provider](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha/src/main/java/cloud/tianai/captcha/resource/impl/provider/URLResourceProvider.java)、[File provider](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha/src/main/java/cloud/tianai/captcha/resource/impl/provider/FileResourceProvider.java)
- 第一版使用审核过的 classpath 背景图和模板；内置 helper 源码明确说只为演示、不推荐生产。[默认资源源码](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha/src/main/java/cloud/tianai/captcha/resource/DefaultBuiltInResources.java#L16-L24)

## 6. 官方来源

- [项目官方仓库](https://github.com/dromara/tianai-captcha)
- [1.5.5 官方 Release](https://github.com/dromara/tianai-captcha/releases/tag/1.5.5)
- [1.5.5 README 固定提交](https://github.com/dromara/tianai-captcha/blob/e7b9fd923bdba6533014c46d03f349b5c1a985f6/readme.md)
- [Maven Central 1.5.5](https://central.sonatype.com/artifact/cloud.tianai.captcha/tianai-captcha-springboot-starter/1.5.5)
- [核心源码固定提交](https://github.com/dromara/tianai-captcha/tree/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha/src/main/java/cloud/tianai/captcha)
- [Spring Boot starter 固定提交](https://github.com/dromara/tianai-captcha/tree/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-springboot-starter)
- [官方 Web SDK 固定提交](https://github.com/dromara/tianai-captcha/tree/e7b9fd923bdba6533014c46d03f349b5c1a985f6/tianai-captcha-web-sdk)
