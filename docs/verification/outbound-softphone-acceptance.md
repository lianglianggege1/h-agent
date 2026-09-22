# 外呼软电话验收记录

- 日期：2026-09-21
- 范围：本地代码审查与自动化验证
- 真实软电话环境：尚未提供
- 结论：代码级验证通过；真实媒体闭环尚未验收，不能启用生产外呼

## 已完成

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| PHONE 空 prompt、开场空 user message | 通过 | `PhoneCallLifecycleTest`、`VoiceTurnModuleStep4Test` |
| 任务 requestId 幂等 | 通过 | `OutboundModuleTest` |
| 启动、停止、恢复与 UNKNOWN 结案规则 | 通过 | `OutboundStartStopTest`、`OutboundRecoveryTest`、`OutboundSchedulerTest` |
| Harness 电话能力隔离与取消结算 | 通过 | `HarnessVoiceReplyTest` |
| Python Worker 路由、鉴权和有界队列 | 通过 | `realtime-voice/tests/test_phone_worker.py` |
| 管理页面及 requestId 保持 | 通过 | `frontend/lib/outbound.test.mjs`、ESLint、Next.js build |

Java 测试使用 GraalVM JDK 21 和 `-Djava.version=21 -Dmaven.compiler.release=21`。本机 Java 26
下 Lombok 1.18.46 因 javac `EndPosTable` 兼容问题在项目代码编译前失败；这不是外呼测试通过的证据。

## 尚未验证

- [ ] 固定 FreeSWITCH 与双向媒体模块的版本、来源和许可。
- [ ] 两个白名单分机顺序外呼，确认不会并发或重复提交。
- [ ] 客户音频到 Python 的真实编码、采样率、帧长和乱序行为。
- [ ] Python 音频回放到软电话，并获得可关联到 utteranceId 的完成确认。
- [ ] 插话后远端播放队列立即清空，旧音频不恢复；记录停止延迟。
- [ ] 三轮对话、开场首句延迟，以及一次允许的只读业务工具调用。
- [ ] ESL 断开、媒体断开、Java/Python 重启、originate 响应丢失和乱序事件。
- [ ] UNKNOWN 重查能由目标 FreeSWITCH 的 `uuid_exists` 返回稳定的存在/不存在事实。
- [ ] 浏览器语音和普通文字聊天回归。

在以上项目完成并记录环境、协议样例和实际时间数据之前，保持 `OUTBOUND_ENABLED=false`。
