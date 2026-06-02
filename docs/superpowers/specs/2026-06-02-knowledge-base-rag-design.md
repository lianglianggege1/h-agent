# 知识库上传 + RAG 检索 — 设计规格

- 日期：2026-06-02
- 范围：**仅后端**（前端接口对接本次不做，预留 REST 接口）
- 模块：新增 `com.h.backend.knowledge`，并对 `chat` 模块的模型装配做最小改动

## 1. 目标与边界

实现一个与现有 AI Agent 打通的知识库能力：

- 支持上传 `md / txt / doc / docx / xls / xlsx` 文件，以及手动输入文本
- 用本地嵌入模型 `BgeSmallZhV15QuantizedEmbeddingModel`（进程内 ONNX，512 维）做向量化
- 用 `PgVectorEmbeddingStore` 存储向量（复用现有 PostgreSQL 数据源）
- 通过 `RetrievalAugmentor`（查询扩展 + 查询路由）接入现有 `HAssistant` Agent，实现 RAG
- 文档可管理：列表 / 删除 / 重解析 / 查看切片

### 关键决策（已与用户确认）

| 维度 | 决策 |
|------|------|
| 知识库归属范围 | 按 systemPrompt / Agent 隔离（按 `promptId`） |
| 文档管理 | 完整管理：列表 + 删除 + 重解析 + 查看切片（需元数据表） |
| 处理时序 | 同步处理（上传即解析入库），但保留 `status` 字段以便将来切异步 |
| 检索参数 | `maxResults=3~5` + `minScore` 阈值，做成可配置 |
| 嵌入模型 | 本地 `BgeSmallZhV15QuantizedEmbeddingModel`（512 维） |
| 文档解析 | Apache Tika（`langchain4j-document-parser-apache-tika`），一个 parser 通吃所有格式 |
| RAG 接入方式 | `DefaultRetrievalAugmentor` + `ExpandingQueryTransformer` + `LanguageModelQueryRouter` |
| 查询扩展/路由模型 | 新增 Anthropic 同步 `ChatModel`（复用同一 `.env` 配置，指向 minimaxi 代理） |

### 非目标（本次不做）

- 前端页面与接口联调
- 异步处理 / 任务队列
- 跨用户共享、知识库分享、权限分级（当前仅「同一 promptId 下所有用户可见」）
- 远程嵌入 API

## 2. 现状（接入点）

- 后端：Spring Boot 3.4.0 + Java 23 + langchain4j 1.15.0，MyBatis Plus（非 JPA）+ Flyway + PostgreSQL + Redis + JWT
- 现有 Agent：`chat/ai/HAssistant.java` —— `TokenStream streamChat(@MemoryId String, @UserMessage String)`
- 装配点：`chat/config/ChatModelConfig.java` 的 `hAssistant()`（`AiServices.builder()`）与 `streamingChatModel()`
- 调用点：`chat/service/impl/ChatServiceImpl.java`，`memoryId = userId + ":" + resolvedPromptId + ":" + sessionId`，以 `Flux<ChatStreamEvent>` 流式返回（SSE）
- 依赖现状：
  - `langchain4j-pgvector`（compile）✓
  - `langchain4j-embeddings-bge-small-zh-v15-q` 已引入但 **scope=test**（需改 compile）
  - **缺** Tika 文档解析依赖
  - 无任何文件上传代码

## 3. 整体架构

```
com.h.backend.knowledge/
├── controller/   KnowledgeDocumentController   上传/列表/删除/重解析/查看切片
├── service/      KnowledgeIngestService         解析→切片→嵌入→入库
│                 KnowledgeDocumentService       文档元数据 CRUD
├── config/       EmbeddingConfig                EmbeddingModel + PgVectorEmbeddingStore
│                                                + ContentRetriever + RetrievalAugmentor
│                 KnowledgeProperties            @ConfigurationProperties("knowledge")
├── entity/       KnowledgeDocumentEntity        文档元数据（MyBatis Plus）
├── mapper/       KnowledgeDocumentMapper
│                 KnowledgeSegmentMapper         查 knowledge_embeddings 的 text/metadata（查看切片）
└── dto/          上传响应、文档列表项、切片项、手动输入请求等
```

对 `chat` 模块的改动仅两处（最小侵入）：

1. `ChatModelConfig` 新增一个同步 `ChatModel` Bean（与现有 `streamingChatModel()` 并存）
2. `ChatModelConfig.hAssistant()` 增加一行 `.retrievalAugmentor(knowledgeRetrievalAugmentor)`

`HAssistant` 接口、`ChatServiceImpl` 完全不动。

### 写入流程（同步）

```
上传文件 → Tika 解析成 Document → DocumentSplitters.recursive 切片 (TextSegment[])
  → 每个 segment 打 metadata{promptId, docId, userId, fileName}
  → BgeEmbeddingModel.embedAll() → PgVectorEmbeddingStore.addAll()
  → 更新 knowledge_document 元数据(status/segmentCount) → 返回
```

### 检索流程（RAG，零侵入对话链路）

```
用户提问 → HAssistant.streamChat(memoryId, msg)
  → RetrievalAugmentor 介入
     ① ExpandingQueryTransformer：原问题 → 多个等价子查询（同步 ChatModel）
     ② LanguageModelQueryRouter：判断是否走知识库（FallbackStrategy.ROUTE_TO_ALL 兜底）
     ③ 每个子查询经 dynamicFilter 解析出 promptId → 向量检索 topK
     ④ ContentAggregator 聚合去重 → ContentInjector 注入 prompt
  → LLM 流式输出
```

隔离原理：`dynamicFilter` 从 `query.metadata().chatMemoryId()`（即 `userId:promptId:sessionId`）解析出 `promptId`，按 `metadataKey("promptId").isEqualTo(promptId)` 过滤。子查询继承原始 Query 的 chatMemoryId，隔离照常生效。

## 4. 数据模型

### (a) pgvector 向量表 `knowledge_embeddings`

由 langchain4j `createTable(true)` 自动建表：

- 维度 `512`（BGE-small-zh）
- metadata 用 `COMBINED_JSONB` 存储（`columnDefinitions(List.of("metadata JSONB NULL"))`），便于按 promptId 过滤且性能好
- 每行 = 一个切片：embedding 向量 + text 文本 + metadata JSONB（含 `promptId`/`docId`/`userId`/`fileName`）

### (b) 文档元数据表 `knowledge_document`（Flyway 迁移 + MyBatis Plus 实体）

```sql
CREATE TABLE knowledge_document (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    prompt_id     BIGINT       NOT NULL,        -- 知识库归属（Agent 隔离）
    file_name     VARCHAR(512) NOT NULL,
    source_type   VARCHAR(16)  NOT NULL,        -- FILE / MANUAL
    file_type     VARCHAR(32),                  -- md/txt/xlsx/docx...
    file_size     BIGINT,
    char_count    INT,                          -- 解析出的字符数
    segment_count INT,                          -- 切片数
    status        VARCHAR(16)  NOT NULL,        -- PROCESSING/COMPLETED/FAILED
    error_msg     TEXT,                         -- 失败原因
    content_hash  VARCHAR(64),                  -- 原文 SHA-256，用于重解析/去重
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_knowledge_doc_user_prompt ON knowledge_document(user_id, prompt_id);
```

### 两表关联

向量行 metadata 存 `docId`。删除文档：先按 `metadataKey("docId").isEqualTo(docId)` 调 `embeddingStore.removeAll(filter)` 删向量，再删元数据行。重解析 = 删旧向量 + 重新切片入库 + 更新 `segment_count`。

## 5. Bean 配置

### 5.1 新增同步 ChatModel（加到 `ChatModelConfig`，与 `streamingChatModel()` 并存）

```java
@Bean
public ChatModel chatModel() {
    // .env 不存在时返回 DisabledChatModel，与 streamingChatModel 降级逻辑一致
    return AnthropicChatModel.builder()
            .apiKey(properties.getProperty("API_KEY"))
            .baseUrl("https://api.minimaxi.com/anthropic/v1")
            .modelName(properties.getProperty("MODEL_NAME"))
            .timeout(Duration.ofSeconds(60))
            .build();   // 查询扩展/路由不需要 thinking
}
```

### 5.2 `EmbeddingConfig`

```java
// 1. 本地嵌入模型（512 维 ONNX，单例复用）
@Bean
EmbeddingModel embeddingModel() {
    return new BgeSmallZhV15QuantizedEmbeddingModel();
}

// 2. pgvector（复用 Spring DataSource，COMBINED_JSONB metadata）
@Bean
EmbeddingStore<TextSegment> knowledgeEmbeddingStore(DataSource ds) {
    return PgVectorEmbeddingStore.datasourceBuilder()
            .datasource(ds).table("knowledge_embeddings").dimension(512)
            .createTable(true)
            .metadataStorageConfig(DefaultMetadataStorageConfig.builder()
                    .storageMode(MetadataStorageMode.COMBINED_JSONB)
                    .columnDefinitions(List.of("metadata JSONB NULL")).build())
            .build();
}

// 3. ContentRetriever（按 promptId 动态隔离 + topK + 阈值，可配置）
@Bean
ContentRetriever knowledgeContentRetriever(
        EmbeddingStore<TextSegment> store, EmbeddingModel model, KnowledgeProperties props) {
    return EmbeddingStoreContentRetriever.builder()
            .embeddingStore(store).embeddingModel(model)
            .maxResults(props.getMaxResults())     // 默认 4
            .minScore(props.getMinScore())         // 默认 0.6
            .dynamicFilter(query -> {
                Object mid = query.metadata() == null ? null : query.metadata().chatMemoryId();
                if (mid == null) return null;
                String[] p = mid.toString().split(":", 3);
                return p.length < 2 ? null : metadataKey("promptId").isEqualTo(Long.valueOf(p[1]));
            })
            .build();
}

// 4. RetrievalAugmentor —— 查询扩展 + 查询路由
@Bean
RetrievalAugmentor knowledgeRetrievalAugmentor(
        ChatModel chatModel, ContentRetriever knowledgeContentRetriever) {

    QueryTransformer queryTransformer = new ExpandingQueryTransformer(chatModel);

    Map<ContentRetriever, String> retrieverToDescription = Map.of(
            knowledgeContentRetriever, "用户上传的知识库文档，包含手册、资料等参考内容");
    QueryRouter queryRouter = LanguageModelQueryRouter.builder()
            .chatModel(chatModel)
            .retrieverToDescription(retrieverToDescription)
            .fallbackStrategy(FallbackStrategy.ROUTE_TO_ALL)  // 路由失败兜底走知识库，避免漏检
            .build();

    return DefaultRetrievalAugmentor.builder()
            .queryTransformer(queryTransformer)
            .queryRouter(queryRouter)
            .build();
}
```

### 5.3 接入现有 Agent（改 `hAssistant()` 一行）

```java
return AiServices.builder(HAssistant.class)
        .streamingChatModel(streamingChatModel)
        .retrievalAugmentor(knowledgeRetrievalAugmentor)   // ← 新增
        .systemMessageProvider(...)
        ...  // 其余不变
```

### 5.4 KnowledgeProperties（`@ConfigurationProperties("knowledge")`）

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `knowledge.retriever.max-results` | 4 | 检索返回切片数 |
| `knowledge.retriever.min-score` | 0.6 | 相似度阈值 |
| `knowledge.split.chunk-size` | 300 | 切片大小（token） |
| `knowledge.split.chunk-overlap` | 30 | 切片重叠（token） |
| `knowledge.upload.max-file-size` | 10MB | 单文件上限（也配 `spring.servlet.multipart.max-file-size`） |
| `knowledge.upload.allowed-types` | md,markdown,txt,doc,docx,xls,xlsx | 后缀白名单 |

### API 约定（已核实，langchain4j 1.15.0）

- `ExpandingQueryTransformer` / `LanguageModelQueryRouter` 构造均要求 `ChatModel`（同步），不能用 `StreamingChatModel`
- `EmbeddingStoreContentRetriever` 支持 `maxResults` / `minScore` / `dynamicFilter(Function<Query, Filter>)`
- `Query.metadata().chatMemoryId()` 返回 memoryId（本项目为 `userId:promptId:sessionId`）
- `PgVectorEmbeddingStore.datasourceBuilder().datasource(DataSource)` 可复用 Spring 数据源

## 6. 解析与切片（`KnowledgeIngestService`）

依赖新增：`langchain4j-document-parser-apache-tika`；把 `langchain4j-embeddings-bge-small-zh-v15-q` 的 `<scope>test</scope>` 改为 compile。

```java
public IngestResult ingest(Long userId, Long promptId, String fileName,
                           InputStream content, String fileType, SourceType sourceType) {
    // 1. 解析：手动输入直接包装，文件用 Tika
    Document document = (sourceType == SourceType.MANUAL)
            ? Document.from(text)
            : new ApacheTikaDocumentParser().parse(content);

    // 2. 递归切片，带重叠
    DocumentSplitter splitter = DocumentSplitters.recursive(
            props.getChunkSize(), props.getChunkOverlap());
    List<TextSegment> segments = splitter.split(document);

    // 3. 先建元数据(status=PROCESSING) 拿到 docId
    Long docId = documentService.create(userId, promptId, fileName, ...);

    // 4. 给每个 segment 打 metadata
    segments.forEach(s -> s.metadata()
            .put("promptId", promptId).put("docId", docId)
            .put("userId", userId).put("fileName", fileName));

    // 5. 嵌入 + 批量入库
    List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
    embeddingStore.addAll(embeddings, segments);

    // 6. 更新元数据 status=COMPLETED, segmentCount
    documentService.markCompleted(docId, segments.size());
    return ...;
}
```

校验：后缀白名单、文件大小上限、解析后空内容 → FAILED。整个 ingest 包在事务里，嵌入/入库异常 → status=FAILED + error_msg，不留半截向量。

## 7. REST 接口（`KnowledgeDocumentController`）

统一 `ApiResponse<T>` 包装，前缀 `/api/knowledge`，JWT 鉴权（从 `AuthUserPrincipal` 取 userId）。

| 方法 | 路径 | 入参 | 说明 |
|------|------|------|------|
| POST | `/api/knowledge/documents/upload` | `MultipartFile file`, `promptId` | 上传文件，同步解析入库 |
| POST | `/api/knowledge/documents/manual` | `{promptId, title, content}` | 手动输入文本入库 |
| GET | `/api/knowledge/documents` | `promptId`（可分页） | 当前用户该 prompt 下文档列表 |
| DELETE | `/api/knowledge/documents/{docId}` | — | 删向量 + 删元数据 |
| POST | `/api/knowledge/documents/{docId}/reparse` | — | 删旧向量 + 重新切片入库 |
| GET | `/api/knowledge/documents/{docId}/segments` | 分页 | 查看该文档切片内容 |

权限：按 docId 操作前校验 `document.userId == 当前 userId`，越权返回 403（`BusinessException` + `GlobalExceptionHandler`）。

切片查看：`KnowledgeSegmentMapper` 按 `metadata->>'docId'` 查 `knowledge_embeddings` 的 text + metadata 列分页返回。

## 8. 测试策略

- **单元测试**（mock embeddingStore/embeddingModel）：
  - 切片逻辑：给定文本 → 断言切片数/重叠/metadata
  - 文件类型白名单：非法后缀被拒
  - `dynamicFilter` 解析 memoryId：`"1:2:abc"` → `metadataKey("promptId")=2`；非法格式 → null
- **集成测试**（Testcontainers pgvector，复用现有 Postgres + Flyway）：
  - 真实本地 BGE 嵌入 → 入库 → 按 promptId 检索命中；换 promptId 检索为空（验证隔离）
  - 删除文档后向量与元数据均清空
- **验证命令**：`source ~/.profile && mvn -pl backend test`

## 9. 错误处理与安全

- 解析失败/空内容 → status=FAILED + error_msg + 明确中文提示
- 嵌入/入库异常 → 事务回滚，不留半截向量
- 超大文件 → multipart 上限拦截
- 同步 `ChatModel` 的 `.env` 缺失 → 降级 `DisabledChatModel`
- **检索阶段异常不得中断对话**：路由/扩展/检索异常需 catch 并降级为「无 RAG 的普通回答」，保证聊天可用
- 上传接口需校验文件类型/大小，限制 Tika 解析资源（防超大/恶意文档耗内存），临时文件用后清理；接口在 JWT 鉴权之后，非匿名

## 10. 实现顺序（建议）

1. pom 依赖调整（Tika + bge 改 compile）
2. Flyway 迁移建 `knowledge_document` 表
3. `KnowledgeProperties` + `EmbeddingConfig`（4 个 Bean）+ 同步 `ChatModel` Bean
4. `KnowledgeDocumentEntity` / Mapper / `KnowledgeDocumentService`
5. `KnowledgeIngestService`（解析切片入库）
6. `KnowledgeDocumentController`（REST）
7. 接入 `hAssistant()` 的 `.retrievalAugmentor(...)`
8. 单元测试 + 集成测试 + 验证
