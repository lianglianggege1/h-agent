# 智能外呼首版编码计划

日期：2026-09-21
状态：待执行；本文是编码顺序与验证计划，不代表任何步骤已经完成。
依据：[智能外呼实施规格](../specs/2026-09-20-outbound-system-implementation-spec.md)。功能范围与结果语义以规格为准。

## 1. 执行方式

目标是交付软电话环境中的完整闭环：名单 → 任务 → FreeSWITCH 拨号 → 真实 Harness Agent 多轮对话与插话 → 结束 → 查看有效对话。

按下面的顺序逐项完成，每项均包含代码落点、实现动作和通过条件。先写关键行为测试，再做实现；不为普通字段映射、简单样式增加无价值测试。一次完成一个可验证的小闭环，不先把所有类、接口和页面铺成空壳。

- [ ] 0. 核对基线与开发环境
- [ ] 1. 验证并固定双向媒体接入
- [ ] 2. 建立最小业务数据与创建入口
- [ ] 3. 扩展 PHONE 通话与独立会话
- [ ] 4. 接入真实 Harness Agent 和播放后提交
- [ ] 5. 打通单分机实时对话
- [ ] 6. 完成批量调度、停止与异常恢复
- [ ] 7. 完成管理接口和两个页面视图
- [ ] 8. 执行端到端验收与交付

步骤 1 未通过，不开始供应商媒体协议的正式实现。步骤 5 未通过，不扩展批量执行和页面。缺少运行条件时记录未验证项，不能用固定回复、录音文件轮询或模拟播放结果宣布通过。

## 2. 文件组织与实现边界

以下目录已经存在；新增类名是建议落点，可以合并，但不要新增职责相同的第二套模块。

| 区域 | 修改现有文件 | 建议新增文件 |
| --- | --- | --- |
| Java 外呼 | application.yml | outbound/domain 下的 Contact、Task、Call；application/OutboundModule、OutboundScheduler；infrastructure/OutboundStore、OutboundProperties、FreeswitchEslClient、PhoneWorkerClient；interfaces/web/OutboundController |
| Java 语音 | voice/application/VoiceCallModule、VoiceTurnModule、VoiceReply；domain/VoiceCall、VoiceTurn；infrastructure/VoiceStore、VoiceSecurity；interfaces/web/VoiceWorkerController | voice/infrastructure/HarnessVoiceReply；必要的执行上下文值对象 |
| Java Agent | chat/domain/agent/HarnessAgentExecutor、ChatAgentExecutionCommand；chat/application/ChatSessionService、impl/ChatSessionServiceImpl；AgentRunService 及必要实现 | 仅在现有执行代码确实需要时提取回复提交策略，不另写 Agent 引擎 |
| Python | realtime-voice/src/realtime_voice/worker.py、control.py、settings.py | 同包内 session.py、phone_worker.py、freeswitch_io.py |
| 数据库 | 不修改已执行迁移 | db/migration 下新的 PHONE 适配迁移、外呼三表迁移 |
| 前端 | frontend/app/me/page.tsx | frontend/app/outbound/page.tsx、frontend/lib/outbound.ts |

表中 Java 路径相对 backend/src/main/java/com/h/backend；迁移目录相对 backend/src/main/resources。具体测试放入对应模块现有测试目录。

职责约束：

- OutboundModule 拥有业务事务和状态推进，Scheduler 只负责定时触发；ESL 回调只报告事实。
- FreeswitchEslClient 只封装已验证的呼叫控制、事件与查询；不直接运行 Agent 或递归拨下一个号码。
- PhoneWorkerClient 只负责准备/结束电话 Worker；Python 拥有媒体、VAD、ASR、TTS，Java 拥有业务状态。
- 复用浏览器与电话确实共有的语音代码；不预建多供应商插件、通用事件总线或通用调度框架。

## 3. 分步编码任务

### 步骤 0：核对基线与环境

**阅读位置**：根目录 CLAUDE.md、CONTEXT.md、上述规格，现有 voice 模块、realtime-voice/README.md、frontend/AGENTS.md。

**动作**：

1. 检查工作区差异、已有 outbound 文件与 Flyway 版本。旧方向的残留代码如仍存在，先核对来源和调用关系，再决定替换；不能假定全部可删。
2. 确认 Java 26、项目支持的 Python 版本及 Node 版本。根 Maven reactor 包含 agent-observability，后端验证通过 reactor 带上依赖模块。
3. 准备独立开发/测试数据库与两个允许的测试分机；数据库集成测试不连接日常业务数据库。
4. 跑一轮现有语音、Harness、Python 的相关测试，记录基线失败；前端编码前阅读本地 Next.js 对应文档。

**通过条件**：可区分既有问题与新增回归；工具链版本正确；尚未启用自动拨号。

### 步骤 1：验证并固定双向媒体接入

**产出位置**：

- 新增 docs/deployment/outbound-softphone.md：固定版本、部署方式、测试分机及媒体协议的实测记录。
- 新增最小联调探针，放在 realtime-voice/scripts/；探针仅验证媒体，不作为生产任务入口。

**动作**：

1. 选定一个真正支持双向播放的 FreeSWITCH 媒体制品，核实版本与许可；不要同时实现多个候选。
2. 验证软电话注册、ESL 连接与拨号。确认 Channel UUID、Job-UUID、接听、结束及拨号失败事件的实际对应关系。
3. 打通客户音频到 Python、Python 音频到软电话；记录真实编码、采样率、帧格式、连接关联和鉴权方式。
4. 在回复播放中持续收音，验证停止/清空远端队列和每段回复的完成/中断通知；主动断开媒体与控制连接，检查电话会结束。
5. 用项目固定的 livekit-agents==1.6.9 验证自定义 I/O 接入。明确哪些播放结果可确认，哪些只能记 UNKNOWN。

**必须留下的证据**：一次双向交谈、一次插话、一次失联挂断；协议样例和版本；首句响应与打断停止时间的实际测量。

**通过条件**：四项媒体要求全部可实现，才锁定适配代码。若缺少清空或播放结果能力，在本步骤解决选型，不在业务层伪造确认。

### 步骤 2：建立最小业务数据与创建入口

**文件**：外呼三表迁移、outbound/domain、OutboundStore、OutboundModule、OutboundProperties、OutboundController；对应 OutboundModuleTest 与独立 PostgreSQL 集成测试。

**先验证的行为**：重复导入不能解除禁呼；相同 requestId 不重复建任务；任一越权联系人让整批创建失败；并发占用被数据库拒绝。

**实现动作**：

1. 按规格创建联系人、任务、通话条目三表。补齐归属外键、任务内号码唯一、单 RUNNING 任务和单活跃通话约束；活跃状态必须包含 UNKNOWN。
2. 实现分机白名单校验、导入、禁呼，以及任务创建。联系人格式和整批输入全部通过后才写入；插入冲突按重复项跳过。
3. 服务端解析 Agent 绑定和只读能力快照，禁止前端提交工具配置。新建任务时同事务插入全部 QUEUED 条目。
4. requestId 重放先返回已有结果；仅请求内容相同才算重放，内容不同返回冲突。唯一冲突后的重新读取在可用事务中完成，不继续使用已回滚事务。
5. 先开放登录保护的导入、创建、详情入口；启动入口尚不可用。实现任务详情的进度聚合，不持久化累加计数。

**通过条件**：可通过接口创建一个含单分机的 READY 任务；没有任何网络拨号副作用。数据库测试实际验证事务回滚和唯一约束，而不只模拟 Store 返回值。

### 步骤 3：扩展 PHONE 通话与独立会话

**文件**：PHONE 迁移、VoiceCall/VoiceTurn、VoiceStore、VoiceCallModule、VoiceWorkerController、VoiceSecurity、ChatSessionService 及实现；新增 PhoneCallLifecycleTest。

**先验证的行为**：PHONE 创建不调用 LiveKit；不归档操作者其他会话；重复准备复用同一资源；旧 Worker 的提交被拒绝。

**实现动作**：

1. voice_calls 增加 BROWSER/PHONE，历史行回填 BROWSER；按渠道调整 room、participant、普通聊天 prompt 等约束，保留原有用户和会话占用限制。
2. 从会话服务提取不切换、不归档其他会话的电话创建路径；Session、PHONE voice_call 和 outbound 关联同事务建立，缓存登记放在事务提交成功后。
3. 为 PHONE 增加准备、认领、心跳与结束处理；复用 Bearer、claimSecret、workerEpoch 和租约。启动与重启恢复按渠道分支，不能给 PHONE 删除 LiveKit 房间。
4. PHONE 的接听事实与媒体就绪分别记录，两项齐备才能 ACTIVE。超时或租约失效进入收尾，不直接释放业务占用。
5. 为开场增加明确的轮次类型，允许没有客户消息的开场 Run；同步调整 Run 创建及数据库约束，不伪造“你好”作为用户发言。
6. 避免会话切换/过期归档破坏活跃电话 Session；保留原会话并发保护。

**通过条件**：同一通话身份贯穿任务、voice 与 Session；浏览器创建、认领、心跳、结束仍按原行为运行。

### 步骤 4：接入真实 Harness Agent 和播放后提交

**文件**：VoiceReply、AnthropicVoiceReply、HarnessVoiceReply、VoiceTurnModule、HarnessAgentExecutor、ChatAgentExecutionCommand，以及实际拥有 Harness 状态保存/记忆写入的相关代码。

**先验证的行为**：

- 同一 turnId 重试只执行一次 Agent、创建一个 Run。
- 生成结束而播放未结束时，未播全文不进入产品消息或下一轮上下文。
- 打断后保留可确认的已播短句及工具事实，思考和工具日志不进入语音。
- 普通文字聊天仍按原方式完成回复提交。

**实现动作**：

1. 将语音执行输入从单纯 prompt/history 扩展为包含用户、Session、Agent 绑定、turnId、已有 Run 与消息身份的执行上下文；同步适配浏览器的直接模型实现。
2. HarnessVoiceReply 复用现有 Harness 执行路径。按已保存绑定选择执行器，PHONE 不回退到 AnthropicVoiceReply，也不调用会重复落库的 streamChat 入口。
3. 将 Harness 的生成完成与对客回复提交拆开：文字聊天保留默认提交，电话由 VoiceTurnModule 接收最终播放结算后提交。执行成功和播放成功分别保存。
4. 检查 Harness 自动保存、压缩和记忆捕获的时机，使未播正文不会绕过 voice 提前写入工作上下文或长期记忆。保留工具调用/结果，不用纯文本消息列表重建整个 Harness 状态。
5. 在执行层限制电话工具、知识和记忆范围；只传递明确面向客户的顶级 Agent 文本。
6. 取消必须能确认旧执行退出；播放和执行结算完成后才能进入下一轮。开场使用固定幂等身份，不在媒体重连或重复事件中重播。

**通过条件**：通过受控输入完成三轮真实 Harness 对话和一次只读业务工具调用；生成、打断、播放结算的持久化结果一致。此步骤可使用模拟播放通知做自动化测试，但真实播放必须在下一步验证。

### 步骤 5：打通单分机实时对话

**文件**：FreeswitchEslClient、PhoneWorkerClient、OutboundScheduler 的单通执行路径；Python session.py、freeswitch_io.py、phone_worker.py；扩展 control.py、settings.py；最小启动/停止接口。

**先验证的行为**：发送失败不自动重拨；接听和媒体就绪前不生成开场；插话后旧音频不恢复；停止不会提前释放占用。

**实现动作**：

1. 从 worker.py 提取 ASR/VAD/TTS 构建、Java 回复消费与播放结算共用代码，浏览器入口继续使用 RoomIO；电话入口使用步骤 1 锁定的 I/O 适配。
2. 电话 Worker 提供受鉴权的准备/清理能力。按 callId、短期认领信息和 Worker 代次绑定媒体连接；不接受浏览器提供的任意媒体 URL。
3. 音频适配实现格式转换、有界缓存、回复身份关联、停止与清空、完成/中断通知。连接断开报告 Java，首版不续播旧音频。
4. ESL 客户端实现连接、事件订阅、originate、查询与挂断；解析消息按协议分帧，不把 TCP 一次读取当成完整事件。禁止对 originate 自动重试。
5. 串联一条数据库驱动路径：认领 → PREPARING → Worker 准备 → 持久化 DIALING → originate → 接听且媒体就绪 → ACTIVE → 开场/对话 → 收尾。
6. 发命令前固定 origination_uuid=callId；Job-UUID 单独关联。ESL 回调幂等更新事实，迟到响应不覆盖终态。
7. 使用步骤 2 的单分机任务，经正式 start/stop 入口联调，不新增绕过数据库、鉴权和状态机的拨号接口。

**通过条件**：软电话连续三轮交谈，一次真实工具调用、一次插话、一次正常挂断；有效对话与下一轮上下文一致。只收到声音、只跑过模拟测试都不算通过。

### 步骤 6：完成批量调度、停止与异常恢复

**文件**：OutboundModule、OutboundScheduler、OutboundStore、FreeSWITCH/Worker 收尾逻辑；新增 OutboundLifecycleTest、OutboundRecoveryTest 及数据库并发测试。

**先验证的场景**：重复 start/stop；拨号提交与停止/禁呼竞争；响应丢失；乱序事件；后台拨号未完成时断线；通话中进程重启。

**实现动作**：

1. 单实例定时调度先做恢复和收尾，再处理准备和下一项；每次只认领一项。每次扫描有界，不等待整通对话结束，以便持续处理停止和超时。短事务按固定顺序锁任务、联系人、通话，网络调用放在事务外。
2. 提交拨号意图时重新检查任务、禁呼与占用；停止与提交使用同一事务约束确定先后。提交之后只能挂断，不能承诺未拨出。
3. 正常未接听、忙线、拒接结案后继续；系统依赖、Agent 或媒体故障结案并暂停任务，不扫完整批制造失败。
4. 停止取消剩余 QUEUED 项；PREPARING 不再提交拨号并清理，已提交项保持 ENDING/UNKNOWN 直到远端和旧执行均结束。
5. 启动先暂停遗留 RUNNING 任务。PREPARING 未提交项清理结案；已提交项查证并请求结束，不重发、不重放、不自动继续。
6. UNKNOWN 持续占用。确认后台命令、通道、Worker 与旧 Agent 均终止才释放；否则允许用户人工核实结案并保存备注，不补造接听/挂断时间。
7. 同一套调度处理重查、超时与结束；不新增 Redis 锁、事件递归拨号或第二个恢复调度器。外呼开关关闭后仍执行收尾。
8. 依据条目聚合任务进度和完成状态；STOPPED 不改回 COMPLETED，终态不可再次启动。

**通过条件**：两个分机顺序执行；断开 ESL/媒体、重启 Java/Python 后不出现双呼、不重复提交；失败状态能通过自动检查或人工核实正确结束。

### 步骤 7：完成管理接口和两个页面视图

**文件**：OutboundController、frontend/lib/outbound.ts、frontend/app/outbound/page.tsx、frontend/app/me/page.tsx。

**接口落点**（均在 /api/outbound，输入输出在实现时以 record/类型定义为准）：

| 方法与路径 | 用途 |
| --- | --- |
| POST /contacts/import；GET /contacts；PUT /contacts/{id}/dnc | 导入、分页与禁呼 |
| GET /agents | 返回已验证电话能力且当前用户可用的 Agent |
| POST /tasks；GET /tasks；GET /tasks/{id} | 创建、列表、条目及关联 Session 信息 |
| POST /tasks/{id}/start；POST /tasks/{id}/stop | 启动/继续、停止整项任务 |
| POST /calls/{id}/reconcile；POST /calls/{id}/resolve-unknown | 重查、带确认与备注的人工结案 |

**实现动作**：

1. 补齐分页、批量限制、字段长度、所有归属校验和统一错误响应。状态查询只读本地数据库，不能因页面刷新调用 originate 或供应商查询。
2. 复用 apiFetch 和当前登录态；创建时生成 requestId，网络失败重试沿用，修改表单后才换新值。
3. 名单视图提供粘贴导入、授权依据、勾选与禁呼。任务视图使用一个创建表单，加任务列表和详情即可。
4. 详情区分接通结果、对话结果、未拨打和 UNKNOWN；从关联 Session 读取有效消息，显示必要的中断状态。
5. 明确“停止”会取消剩余项并结束当前电话；请求返回后仍可能处于结束确认中。无已验证 Agent、依赖未就绪或有占用时展示具体原因。
6. 仅详情打开时轮询本地状态，离开清理；人工结案需要确认与备注，后端检查旧执行是否仍存活。
7. 不加入独立通话记录页、录音播放器、意向编辑或统计概览。

**通过条件**：浏览器完成导入 → 创建 → 启动 → 对话 → 查看结果；重复点击与刷新不产生新拨号；普通账户不能读取或操作他人数据。

### 步骤 8：端到端验收与交付

**产出**：更新 docs/deployment/outbound-softphone.md；新增 docs/verification/outbound-softphone-acceptance.md，记录环境、版本、日期、场景与真实结果。

执行规格中的验收表，至少覆盖：

- 两个允许分机顺序外呼、独立 Session、三轮对话、真实只读工具调用。
- 插话后旧音频不恢复，已播短句和工具事实保留，未播全文不进入下轮上下文。
- 禁呼后重复导入、越权、重复创建/启动/停止、拒接与未接听。
- Worker 未就绪、模型异常、ESL 断开、媒体断开、进程重启和 UNKNOWN 核实。
- 浏览器语音与文字聊天回归；首句响应及打断时间实测。

交付时列清已通过与未验证场景。验收范围仅是软电话闭环；不得用模拟测试结果代替真实媒体验证，也不得宣称已经接入客户手机线路。

## 4. 验证命令与运行约定

以下为执行计划时使用的命令，本文编写阶段未运行这些测试。所有 shell 命令先 source ~/.profile，且确认使用 Java 26。

**后端基线**（仓库根目录）：

~~~bash
source ~/.profile
mvn -pl backend -am -Dtest=VoiceTurnTest,AnthropicVoiceReplyTest,LiveKitGatewayTest,HarnessAgentExecutorTest,ChatSessionServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~

新增测试后，每步将相应类加入 -Dtest 列表。数据库约束、并发和重启恢复测试使用显式配置的临时 PostgreSQL，真实电话测试使用单独的手动联调配置，默认测试不拨号。

**Python**（realtime-voice 目录）：

~~~bash
source ~/.profile
uv sync --frozen
uv run pytest
uv run ruff check src tests
~~~

新增 test_freeswitch_io.py、test_phone_worker.py 等测试时，优先验证停止清空、旧事件隔离、缓冲上限、准备幂等和租约失效；不要只断言内部函数调用顺序。

**前端**（frontend 目录）：

~~~bash
source ~/.profile
npm test
npx eslint app/outbound/page.tsx lib/outbound.ts app/me/page.tsx
npm run build
~~~

**每步完成前**（仓库根目录）：

~~~bash
source ~/.profile
git diff --check
~~~

通过相关测试后再进入下一项；只有新修改、新失败或尚未覆盖的风险才扩大验证。媒体制品版本、音频协议、最终 SQL 和 DTO 在实现处记录一次，本计划不复制第二套定义。
