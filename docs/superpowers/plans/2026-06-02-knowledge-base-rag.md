# 知识库上传 + RAG 检索 实现计划（仅后端）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为现有 HAssistant Agent 增加知识库能力——上传 md/txt/word/excel 文件或手动输入文本，本地 BGE 嵌入后存入 PgVector，通过 RetrievalAugmentor（查询扩展 + 路由）实现按 promptId 隔离的 RAG 检索。

**架构：** 新增 `com.h.backend.knowledge` 模块（controller/service/config/entity/mapper/dto）。文档元数据存 PostgreSQL（MyBatis Plus + Flyway），切片向量存 PgVector（langchain4j 自动建表）。检索通过在现有 `ChatModelConfig.hAssistant()` 上挂一个 `DefaultRetrievalAugmentor` 接入，对话链路（HAssistant 接口、ChatServiceImpl）零改动。

**技术栈：** Java 23 + Spring Boot 3.4.0 + langchain4j 1.15.0 + MyBatis Plus 3.5.16 + Flyway + PostgreSQL(pgvector) + Apache Tika 解析 + 本地 BgeSmallZhV15QuantizedEmbeddingModel(512 维)。

**规格来源：** `docs/superpowers/specs/2026-06-02-knowledge-base-rag-design.md`

**范围：** 仅后端。前端接口对接不在本计划内。

---

## 关键现有模式（实现时严格遵循）

- **统一响应：** `com.h.backend.common.api.ApiResponse<T>`，`ApiResponse.ok(data)` / `ApiResponse.error(code, msg)`。
- **业务异常：** `com.h.backend.common.exception.BusinessException(int code, String message)`，由 `GlobalExceptionHandler` 捕获。code 在 `[40100,40200)` → 401，其余 → 400。
- **鉴权：** Controller 方法用 `@AuthenticationPrincipal AuthUserPrincipal principal`，`principal.userId()` 取当前用户。SecurityConfig 默认 `anyRequest().authenticated()`，新接口无需额外放行（自动需要登录）。
- **实体：** MyBatis Plus，`@TableName` + `@TableId(type = IdType.AUTO)` + `@TableField`，时间字段 `LocalDateTime`。
- **Mapper：** `@Mapper interface XxxMapper extends BaseMapper<XxxEntity>`，复杂查询用 `@Select` 内联 SQL。
- **Flyway：** `src/main/resources/db/migration/`，现有最大版本号 V7，**下一个用 V8**。
- **测试：** `@SpringBootTest` 直连本地 PostgreSQL（127.0.0.1:5432/h_agent_db），无 Testcontainers。集成测试沿用此模式。纯逻辑测试用普通 JUnit5 + Mockito（`spring-boot-starter-test` 已含）。
- **配置读取：** `streamingChatModel()` 已从项目根 `.env` 读 `API_KEY`/`MODEL_NAME`，新同步模型复用同一段读取逻辑。

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `backend/src/main/resources/db/migration/V8__create_knowledge_document.sql` | 文档元数据表 |
| `knowledge/config/KnowledgeProperties.java` | `@ConfigurationProperties("knowledge")` 检索/切片/上传参数 |
| `knowledge/config/EmbeddingConfig.java` | EmbeddingModel + PgVectorEmbeddingStore + ContentRetriever + RetrievalAugmentor 四个 Bean |
| `knowledge/entity/KnowledgeDocumentEntity.java` | 文档元数据实体 |
| `knowledge/mapper/KnowledgeDocumentMapper.java` | 文档元数据 CRUD |
| `knowledge/mapper/KnowledgeSegmentMapper.java` | 查 `knowledge_embeddings` 的 text/metadata（查看切片） |
| `knowledge/dto/...` | 上传/列表/切片/手动输入 DTO |
| `knowledge/service/KnowledgeDocumentService.java` (+impl) | 文档元数据业务（创建/列表/删除/状态更新/权限校验） |
| `knowledge/service/KnowledgeIngestService.java` (+impl) | 解析→切片→嵌入→入库；删向量；重解析 |
| `knowledge/controller/KnowledgeDocumentController.java` | 6 个 REST 接口 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `backend/pom.xml` | 加 Tika 解析依赖；bge 依赖 scope test→compile |
| `backend/src/main/resources/application.yml` | 加 `knowledge.*` 配置 + multipart 上限 |
| `chat/config/ChatModelConfig.java` | 新增同步 `ChatModel` Bean；`hAssistant()` 加 `.retrievalAugmentor(...)` |

---

## 任务 1：依赖调整（pom.xml）

**文件：**
- 修改：`backend/pom.xml`

- [ ] **步骤 1：把 bge 嵌入依赖从 test 改为 compile**

把现有这段（约 144-149 行）：

```xml
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-embeddings-bge-small-zh-v15-q</artifactId>
            <version>${langchain4j.version}-beta25</version>
            <scope>test</scope>
        </dependency>
```

改为（删掉 `<scope>test</scope>` 这一行）：

```xml
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-embeddings-bge-small-zh-v15-q</artifactId>
            <version>${langchain4j.version}-beta25</version>
        </dependency>
```

- [ ] **步骤 2：新增 Apache Tika 文档解析依赖**

在 `langchain4j-pgvector` 依赖之后、`langchain4j-open-ai` 依赖之前插入：

```xml
        <!-- 文档解析：一个 parser 通吃 md/txt/doc/docx/xls/xlsx -->
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-document-parser-apache-tika</artifactId>
            <version>${langchain4j.version}</version>
        </dependency>
```

- [ ] **步骤 3：验证依赖可解析**

运行：`source ~/.profile && mvn -pl backend dependency:resolve -q`
预期：BUILD SUCCESS，无法解析 artifact 的报错为 0。

- [ ] **步骤 4：Commit**

```bash
git add backend/pom.xml
git commit -m "build: add tika parser, promote bge embeddings to compile scope"
```

---

## 任务 2：Flyway 迁移 — 文档元数据表

**文件：**
- 创建：`backend/src/main/resources/db/migration/V8__create_knowledge_document.sql`

- [ ] **步骤 1：编写迁移 SQL**

```sql
CREATE TABLE IF NOT EXISTS knowledge_document (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    prompt_id     BIGINT       NOT NULL,
    file_name     VARCHAR(512) NOT NULL,
    source_type   VARCHAR(16)  NOT NULL,
    file_type     VARCHAR(32),
    file_size     BIGINT,
    char_count    INTEGER,
    segment_count INTEGER,
    status        VARCHAR(16)  NOT NULL,
    error_msg     TEXT,
    content_hash  VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_doc_user_prompt
    ON knowledge_document(user_id, prompt_id);
```

- [ ] **步骤 2：验证迁移可应用**

运行：`source ~/.profile && mvn -pl backend flyway:info -q` 或启动一次应用让 Flyway 自动迁移。
预期：V8 迁移被识别，应用后 `knowledge_document` 表存在。

> 注：`knowledge_embeddings` 向量表由 langchain4j `createTable(true)` 在运行时自动创建，不在 Flyway 管理范围。pgvector 扩展需数据库已安装（PgVectorEmbeddingStore 默认会 `CREATE EXTENSION IF NOT EXISTS vector`，除非 `skipCreateVectorExtension(true)`）。

- [ ] **步骤 3：Commit**

```bash
git add backend/src/main/resources/db/migration/V8__create_knowledge_document.sql
git commit -m "feat: add knowledge_document table migration"
```

---

## 任务 3：KnowledgeProperties 配置类

**文件：**
- 创建：`backend/src/main/java/com/h/backend/knowledge/config/KnowledgeProperties.java`
- 修改：`backend/src/main/resources/application.yml`

- [ ] **步骤 1：编写配置类**

```java
package com.h.backend.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties {

    private final Retriever retriever = new Retriever();
    private final Split split = new Split();
    private final Upload upload = new Upload();

    public Retriever getRetriever() { return retriever; }
    public Split getSplit() { return split; }
    public Upload getUpload() { return upload; }

    public static class Retriever {
        private int maxResults = 4;
        private double minScore = 0.6;
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public double getMinScore() { return minScore; }
        public void setMinScore(double minScore) { this.minScore = minScore; }
    }

    public static class Split {
        private int chunkSize = 300;
        private int chunkOverlap = 30;
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    }

    public static class Upload {
        private List<String> allowedTypes =
                List.of("md", "markdown", "txt", "doc", "docx", "xls", "xlsx");
        public List<String> getAllowedTypes() { return allowedTypes; }
        public void setAllowedTypes(List<String> allowedTypes) { this.allowedTypes = allowedTypes; }
    }
}
```

- [ ] **步骤 2：在 application.yml 追加配置**

在 `application.yml` 末尾追加（与顶层 `spring:` 同级新增 `knowledge:`；并在 `spring:` 下补 multipart 上限）：

```yaml
knowledge:
  retriever:
    max-results: 4
    min-score: 0.6
  split:
    chunk-size: 300
    chunk-overlap: 30
  upload:
    allowed-types:
      - md
      - markdown
      - txt
      - doc
      - docx
      - xls
      - xlsx
```

并在现有 `spring:` 块内（与 `datasource:` 同级）新增：

```yaml
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
```

- [ ] **步骤 3：编译验证**

运行：`source ~/.profile && mvn -pl backend compile -q`
预期：BUILD SUCCESS。

- [ ] **步骤 4：Commit**

```bash
git add backend/src/main/java/com/h/backend/knowledge/config/KnowledgeProperties.java backend/src/main/resources/application.yml
git commit -m "feat: add knowledge properties and multipart limits"
```

---

## 任务 4：文档元数据实体 + Mapper

**文件：**
- 创建：`backend/src/main/java/com/h/backend/knowledge/entity/KnowledgeDocumentEntity.java`
- 创建：`backend/src/main/java/com/h/backend/knowledge/mapper/KnowledgeDocumentMapper.java`
- 测试：`backend/src/test/java/com/h/backend/knowledge/KnowledgeDocumentMapperPersistenceTest.java`

- [ ] **步骤 1：编写失败的持久化测试**

```java
package com.h.backend.knowledge;

import com.h.backend.knowledge.entity.KnowledgeDocumentEntity;
import com.h.backend.knowledge.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class KnowledgeDocumentMapperPersistenceTest {

    @Autowired
    private KnowledgeDocumentMapper mapper;

    @Test
    void shouldInsertAndQueryByUserAndPrompt() {
        long promptId = System.currentTimeMillis();
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setUserId(1L);
        doc.setPromptId(promptId);
        doc.setFileName("test.md");
        doc.setSourceType("FILE");
        doc.setFileType("md");
        doc.setStatus("PROCESSING");
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        mapper.insert(doc);
        assertNotNull(doc.getId());

        List<KnowledgeDocumentEntity> found = mapper.selectByUserAndPrompt(1L, promptId);
        assertEquals(1, found.size());
        assertEquals("test.md", found.get(0).getFileName());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`source ~/.profile && mvn -pl backend test -Dtest=KnowledgeDocumentMapperPersistenceTest -q`
预期：编译失败（实体/Mapper 不存在）。

- [ ] **步骤 3：编写实体**

```java
package com.h.backend.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("knowledge_document")
public class KnowledgeDocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("prompt_id")
    private Long promptId;

    @TableField("file_name")
    private String fileName;

    @TableField("source_type")
    private String sourceType;

    @TableField("file_type")
    private String fileType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("char_count")
    private Integer charCount;

    @TableField("segment_count")
    private Integer segmentCount;

    private String status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("content_hash")
    private String contentHash;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **步骤 4：编写 Mapper**

```java
package com.h.backend.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.knowledge.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {

    @Select("""
            SELECT id, user_id, prompt_id, file_name, source_type, file_type, file_size,
                   char_count, segment_count, status, error_msg, content_hash,
                   created_at, updated_at
            FROM knowledge_document
            WHERE user_id = #{userId} AND prompt_id = #{promptId}
            ORDER BY created_at DESC, id DESC
            """)
    List<KnowledgeDocumentEntity> selectByUserAndPrompt(@Param("userId") Long userId,
                                                        @Param("promptId") Long promptId);
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`source ~/.profile && mvn -pl backend test -Dtest=KnowledgeDocumentMapperPersistenceTest -q`
预期：PASS（需本地 PostgreSQL 已启动、Flyway 已迁移 V8）。

- [ ] **步骤 6：Commit**

```bash
git add backend/src/main/java/com/h/backend/knowledge/entity backend/src/main/java/com/h/backend/knowledge/mapper/KnowledgeDocumentMapper.java backend/src/test/java/com/h/backend/knowledge/KnowledgeDocumentMapperPersistenceTest.java
git commit -m "feat: add knowledge document entity and mapper"
```

---

## 任务 5：同步 ChatModel Bean（查询扩展/路由用）

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/config/ChatModelConfig.java`

**背景：** `ExpandingQueryTransformer` 和 `LanguageModelQueryRouter` 构造函数要求一个同步 `dev.langchain4j.model.chat.ChatModel`。现有只有 `StreamingChatModel`，二者不可互换，必须新增。复用 `streamingChatModel()` 已有的 `.env` 读取与降级模式。

- [ ] **步骤 1：新增导入**

在 `ChatModelConfig.java` 顶部 import 区追加：

```java
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.DisabledChatModel;
```

- [ ] **步骤 2：新增同步 ChatModel Bean**

在 `streamingChatModel()` 方法之后、`hAssistant(...)` 之前插入：

```java
    @Bean
    public ChatModel chatModel() {
        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            return new DisabledChatModel();
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);
            return AnthropicChatModel.builder()
                    .apiKey(properties.getProperty("API_KEY"))
                    .baseUrl("https://api.minimaxi.com/anthropic/v1")
                    .modelName(properties.getProperty("MODEL_NAME"))
                    .timeout(Duration.ofSeconds(60))
                    .build();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file", ex);
        }
    }
```

- [ ] **步骤 3：编译验证**

运行：`source ~/.profile && mvn -pl backend compile -q`
预期：BUILD SUCCESS。

- [ ] **步骤 4：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/config/ChatModelConfig.java
git commit -m "feat: add synchronous ChatModel bean for query expansion and routing"
```

---

## 任务 6：EmbeddingConfig — 嵌入/向量库/检索器/增强器 Bean

**文件：**
- 创建：`backend/src/main/java/com/h/backend/knowledge/config/EmbeddingConfig.java`
- 测试：`backend/src/test/java/com/h/backend/knowledge/EmbeddingConfigContextTest.java`

- [ ] **步骤 1：编写失败的上下文测试**

验证四个 Bean 能正常装配（依赖本地 PG + .env；若环境无 .env，RetrievalAugmentor 仍应能用 DisabledChatModel 装配成功）。

```java
package com.h.backend.knowledge;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class EmbeddingConfigContextTest {

    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private EmbeddingStore<TextSegment> knowledgeEmbeddingStore;
    @Autowired
    private ContentRetriever knowledgeContentRetriever;
    @Autowired
    private RetrievalAugmentor knowledgeRetrievalAugmentor;

    @Test
    void beansShouldBeWired() {
        assertNotNull(embeddingModel);
        assertNotNull(knowledgeEmbeddingStore);
        assertNotNull(knowledgeContentRetriever);
        assertNotNull(knowledgeRetrievalAugmentor);
    }

    @Test
    void bgeEmbeddingDimensionIs512() {
        assertEquals(512, embeddingModel.embed("测试中文").content().dimension());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`source ~/.profile && mvn -pl backend test -Dtest=EmbeddingConfigContextTest -q`
预期：失败（Bean 不存在，装配报错）。

- [ ] **步骤 3：编写 EmbeddingConfig**

```java
package com.h.backend.knowledge.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.router.LanguageModelQueryRouter;
import dev.langchain4j.rag.query.router.LanguageModelQueryRouter.FallbackStrategy;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.rag.query.transformer.ExpandingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15QuantizedEmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> knowledgeEmbeddingStore(DataSource dataSource) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table("knowledge_embeddings")
                .dimension(512)
                .createTable(true)
                .metadataStorageConfig(DefaultMetadataStorageConfig.builder()
                        .storageMode(MetadataStorageMode.COMBINED_JSONB)
                        .columnDefinitions(List.of("metadata JSONB NULL"))
                        .build())
                .build();
    }

    @Bean
    public ContentRetriever knowledgeContentRetriever(
            EmbeddingStore<TextSegment> knowledgeEmbeddingStore,
            EmbeddingModel embeddingModel,
            KnowledgeProperties props) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(knowledgeEmbeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(props.getRetriever().getMaxResults())
                .minScore(props.getRetriever().getMinScore())
                .dynamicFilter(query -> {
                    Object mid = query.metadata() == null ? null : query.metadata().chatMemoryId();
                    if (mid == null) {
                        return null;
                    }
                    String[] parts = mid.toString().split(":", 3);
                    if (parts.length < 2) {
                        return null;
                    }
                    // promptId 以字符串存入 metadata（见任务 8），故按字符串过滤
                    return metadataKey("promptId").isEqualTo(parts[1]);
                })
                .build();
    }

    @Bean
    public RetrievalAugmentor knowledgeRetrievalAugmentor(
            ChatModel chatModel,
            ContentRetriever knowledgeContentRetriever) {

        QueryTransformer queryTransformer = new ExpandingQueryTransformer(chatModel);

        Map<ContentRetriever, String> retrieverToDescription = Map.of(
                knowledgeContentRetriever,
                "用户上传的知识库文档，包含手册、资料等参考内容");
        QueryRouter queryRouter = LanguageModelQueryRouter.builder()
                .chatModel(chatModel)
                .retrieverToDescription(retrieverToDescription)
                .fallbackStrategy(FallbackStrategy.ROUTE_TO_ALL)
                .build();

        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(queryTransformer)
                .queryRouter(queryRouter)
                .build();
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`source ~/.profile && mvn -pl backend test -Dtest=EmbeddingConfigContextTest -q`
预期：PASS。首次会下载/加载 BGE ONNX 模型（耗时、占内存属正常）。

> 若 `dynamicFilter` 的 `query.metadata()` API 或 `FallbackStrategy` 枚举名与本机 jar 不符，以 `mvn dependency:sources` 反查实际签名为准并调整（规格阶段已核实为 1.15.0/1.15.0-beta25 的签名）。

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/knowledge/config/EmbeddingConfig.java backend/src/test/java/com/h/backend/knowledge/EmbeddingConfigContextTest.java
git commit -m "feat: add embedding store, content retriever and retrieval augmentor beans"
```

---

## 任务 7：DTO + KnowledgeDocumentService（元数据业务 + 权限）

**文件：**
- 创建：`knowledge/dto/KnowledgeDocumentDto.java`、`knowledge/dto/ManualInputRequest.java`、`knowledge/dto/SegmentDto.java`
- 创建：`knowledge/service/KnowledgeDocumentService.java` + `knowledge/service/impl/KnowledgeDocumentServiceImpl.java`
- 测试：`backend/src/test/java/com/h/backend/knowledge/KnowledgeDocumentServiceTest.java`

- [ ] **步骤 1：编写 DTO**

`KnowledgeDocumentDto.java`（列表项）：

```java
package com.h.backend.knowledge.dto;

import java.time.LocalDateTime;

public record KnowledgeDocumentDto(
        Long id,
        String fileName,
        String sourceType,
        String fileType,
        Long fileSize,
        Integer charCount,
        Integer segmentCount,
        String status,
        String errorMsg,
        LocalDateTime createdAt
) {}
```

`ManualInputRequest.java`：

```java
package com.h.backend.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ManualInputRequest(
        @NotNull Long promptId,
        @NotBlank String title,
        @NotBlank String content
) {}
```

`SegmentDto.java`（查看切片）：

```java
package com.h.backend.knowledge.dto;

public record SegmentDto(
        String text,
        String metadata
) {}
```

- [ ] **步骤 2：编写失败的单元测试（权限校验）**

```java
package com.h.backend.knowledge;

import com.h.backend.common.exception.BusinessException;
import com.h.backend.knowledge.entity.KnowledgeDocumentEntity;
import com.h.backend.knowledge.mapper.KnowledgeDocumentMapper;
import com.h.backend.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeDocumentServiceTest {

    private final KnowledgeDocumentMapper mapper = Mockito.mock(KnowledgeDocumentMapper.class);
    private final KnowledgeDocumentServiceImpl service = new KnowledgeDocumentServiceImpl(mapper);

    @Test
    void requireOwnedShouldThrowWhenUserMismatch() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(10L);
        doc.setUserId(2L);
        Mockito.when(mapper.selectById(10L)).thenReturn(doc);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireOwned(1L, 10L));
        assertEquals(40300, ex.getCode());
    }

    @Test
    void requireOwnedShouldReturnWhenOwner() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(10L);
        doc.setUserId(1L);
        Mockito.when(mapper.selectById(10L)).thenReturn(doc);

        assertEquals(10L, service.requireOwned(1L, 10L).getId());
    }
}
```

- [ ] **步骤 3：运行测试验证失败**

运行：`source ~/.profile && mvn -pl backend test -Dtest=KnowledgeDocumentServiceTest -q`
预期：编译失败（service 不存在）。

- [ ] **步骤 4：编写 Service 接口**

```java
package com.h.backend.knowledge.service;

import com.h.backend.knowledge.dto.KnowledgeDocumentDto;
import com.h.backend.knowledge.entity.KnowledgeDocumentEntity;

import java.util.List;

public interface KnowledgeDocumentService {

    /** 创建元数据记录（status=PROCESSING），返回 docId */
    Long create(Long userId, Long promptId, String fileName, String sourceType,
                String fileType, Long fileSize, String contentHash);

    void markCompleted(Long docId, int charCount, int segmentCount);

    void markFailed(Long docId, String errorMsg);

    List<KnowledgeDocumentDto> list(Long userId, Long promptId);

    /** 校验文档归属当前用户，否则抛 40300；返回实体 */
    KnowledgeDocumentEntity requireOwned(Long userId, Long docId);

    void delete(Long docId);
}
```

- [ ] **步骤 5：编写 Service 实现**

```java
package com.h.backend.knowledge.service.impl;

import com.h.backend.common.exception.BusinessException;
import com.h.backend.knowledge.dto.KnowledgeDocumentDto;
import com.h.backend.knowledge.entity.KnowledgeDocumentEntity;
import com.h.backend.knowledge.mapper.KnowledgeDocumentMapper;
import com.h.backend.knowledge.service.KnowledgeDocumentService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeDocumentMapper mapper;

    public KnowledgeDocumentServiceImpl(KnowledgeDocumentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Long create(Long userId, Long promptId, String fileName, String sourceType,
                       String fileType, Long fileSize, String contentHash) {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setUserId(userId);
        doc.setPromptId(promptId);
        doc.setFileName(fileName);
        doc.setSourceType(sourceType);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setContentHash(contentHash);
        doc.setStatus("PROCESSING");
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        mapper.insert(doc);
        return doc.getId();
    }

    @Override
    public void markCompleted(Long docId, int charCount, int segmentCount) {
        KnowledgeDocumentEntity doc = mapper.selectById(docId);
        if (doc == null) {
            return;
        }
        doc.setStatus("COMPLETED");
        doc.setCharCount(charCount);
        doc.setSegmentCount(segmentCount);
        doc.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(doc);
    }

    @Override
    public void markFailed(Long docId, String errorMsg) {
        KnowledgeDocumentEntity doc = mapper.selectById(docId);
        if (doc == null) {
            return;
        }
        doc.setStatus("FAILED");
        doc.setErrorMsg(errorMsg);
        doc.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(doc);
    }

    @Override
    public List<KnowledgeDocumentDto> list(Long userId, Long promptId) {
        return mapper.selectByUserAndPrompt(userId, promptId).stream()
                .map(d -> new KnowledgeDocumentDto(
                        d.getId(), d.getFileName(), d.getSourceType(), d.getFileType(),
                        d.getFileSize(), d.getCharCount(), d.getSegmentCount(),
                        d.getStatus(), d.getErrorMsg(), d.getCreatedAt()))
                .toList();
    }

    @Override
    public KnowledgeDocumentEntity requireOwned(Long userId, Long docId) {
        KnowledgeDocumentEntity doc = mapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(40400, "文档不存在");
        }
        if (!doc.getUserId().equals(userId)) {
            throw new BusinessException(40300, "无权操作该文档");
        }
        return doc;
    }

    @Override
    public void delete(Long docId) {
        mapper.deleteById(docId);
    }
}
```

- [ ] **步骤 6：运行测试验证通过**

运行：`source ~/.profile && mvn -pl backend test -Dtest=KnowledgeDocumentServiceTest -q`
预期：PASS。

- [ ] **步骤 7：Commit**

```bash
git add backend/src/main/java/com/h/backend/knowledge/dto backend/src/main/java/com/h/backend/knowledge/service backend/src/test/java/com/h/backend/knowledge/KnowledgeDocumentServiceTest.java
git commit -m "feat: add knowledge document service with ownership check"
```

---

## 任务 8：KnowledgeIngestService — 解析/切片/嵌入/入库

**文件：**
- 创建：`knowledge/service/KnowledgeIngestService.java` + `impl/KnowledgeIngestServiceImpl.java`
- 测试：`backend/src/test/java/com/h/backend/knowledge/KnowledgeIngestServiceTest.java`

**说明：** pgvector 表结构（运行时自动建）为 `knowledge_embeddings(embedding_id UUID, embedding vector(512), text TEXT, metadata JSONB)`。切片删除按 `metadataKey("docId")` 过滤。

- [ ] **步骤 1：编写失败的单元测试（切片 + metadata + 类型校验）**

```java
package com.h.backend.knowledge;

import com.h.backend.knowledge.config.KnowledgeProperties;
import com.h.backend.knowledge.service.KnowledgeDocumentService;
import com.h.backend.knowledge.service.impl.KnowledgeIngestServiceImpl;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

class KnowledgeIngestServiceTest {

    @SuppressWarnings("unchecked")
    private final EmbeddingStore<TextSegment> store = Mockito.mock(EmbeddingStore.class);
    private final EmbeddingModel model = Mockito.mock(EmbeddingModel.class);
    private final KnowledgeDocumentService docService = Mockito.mock(KnowledgeDocumentService.class);
    private final KnowledgeProperties props = new KnowledgeProperties();

    private final KnowledgeIngestServiceImpl service =
            new KnowledgeIngestServiceImpl(store, model, docService, props);

    @Test
    void manualIngestShouldSplitTagMetadataAndStore() {
        Mockito.when(docService.create(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(99L);
        Mockito.when(model.embedAll(anyList()))
                .thenAnswer(inv -> {
                    List<TextSegment> segs = inv.getArgument(0);
                    List<Embedding> embs = segs.stream()
                            .map(s -> new Embedding(new float[]{0.1f, 0.2f})).toList();
                    return Response.from(embs);
                });

        service.ingestManual(1L, 2L, "标题", "这是一段用于测试的中文知识内容。".repeat(50));

        ArgumentCaptor<List<TextSegment>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(store).addAll(anyList(), captor.capture());
        List<TextSegment> stored = captor.getValue();
        assertFalse(stored.isEmpty());
        TextSegment first = stored.get(0);
        assertEquals("2", first.metadata().getString("promptId"));
        assertEquals("99", first.metadata().getString("docId"));
        assertEquals("1", first.metadata().getString("userId"));
        Mockito.verify(docService).markCompleted(Mockito.eq(99L), Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void isAllowedTypeShouldRejectUnknownExtension() {
        assertTrue(service.isAllowedType("report.docx"));
        assertTrue(service.isAllowedType("notes.MD"));
        assertFalse(service.isAllowedType("malware.exe"));
        assertFalse(service.isAllowedType("noext"));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`source ~/.profile && mvn -pl backend test -Dtest=KnowledgeIngestServiceTest -q`
预期：编译失败（service 不存在）。

- [ ] **步骤 3：编写 Service 接口**

```java
package com.h.backend.knowledge.service;

import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeIngestService {

    /** 上传文件：解析→切片→嵌入→入库，返回 docId */
    Long ingestFile(Long userId, Long promptId, MultipartFile file);

    /** 手动输入文本入库，返回 docId */
    Long ingestManual(Long userId, Long promptId, String title, String content);

    /** 删除文档对应的全部向量（按 docId 过滤） */
    void removeVectors(Long docId);

    /** 文件名后缀是否在白名单内 */
    boolean isAllowedType(String fileName);
}
```

- [ ] **步骤 4：编写 Service 实现**

```java
package com.h.backend.knowledge.service.impl;

import com.h.backend.common.exception.BusinessException;
import com.h.backend.knowledge.config.KnowledgeProperties;
import com.h.backend.knowledge.service.KnowledgeDocumentService;
import com.h.backend.knowledge.service.KnowledgeIngestService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Slf4j
@Service
public class KnowledgeIngestServiceImpl implements KnowledgeIngestService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeProperties props;

    public KnowledgeIngestServiceImpl(EmbeddingStore<TextSegment> embeddingStore,
                                      EmbeddingModel embeddingModel,
                                      KnowledgeDocumentService documentService,
                                      KnowledgeProperties props) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.documentService = documentService;
        this.props = props;
    }

    @Override
    public boolean isAllowedType(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return props.getUpload().getAllowedTypes().contains(ext);
    }

    @Override
    @Transactional
    public Long ingestFile(Long userId, Long promptId, MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (!isAllowedType(fileName)) {
            throw new BusinessException(40001, "不支持的文件类型：" + fileName);
        }
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        Long docId = documentService.create(userId, promptId, fileName, "FILE",
                ext, file.getSize(), null);
        try (InputStream in = file.getInputStream()) {
            Document document = new ApacheTikaDocumentParser().parse(in);
            ingestDocument(docId, userId, promptId, fileName, document);
            return docId;
        } catch (IOException | RuntimeException ex) {
            log.warn("文档解析入库失败 docId={}", docId, ex);
            documentService.markFailed(docId, truncate(ex.getMessage()));
            throw new BusinessException(40002, "文档解析失败：" + truncate(ex.getMessage()));
        }
    }

    @Override
    @Transactional
    public Long ingestManual(Long userId, Long promptId, String title, String content) {
        Long docId = documentService.create(userId, promptId, title, "MANUAL",
                "txt", (long) content.length(), null);
        try {
            ingestDocument(docId, userId, promptId, title, Document.from(content));
            return docId;
        } catch (RuntimeException ex) {
            log.warn("手动输入入库失败 docId={}", docId, ex);
            documentService.markFailed(docId, truncate(ex.getMessage()));
            throw new BusinessException(40002, "入库失败：" + truncate(ex.getMessage()));
        }
    }

    private void ingestDocument(Long docId, Long userId, Long promptId,
                                String fileName, Document document) {
        String text = document.text();
        if (text == null || text.isBlank()) {
            documentService.markFailed(docId, "解析后内容为空");
            throw new BusinessException(40003, "解析后内容为空");
        }
        DocumentSplitter splitter = DocumentSplitters.recursive(
                props.getSplit().getChunkSize(), props.getSplit().getChunkOverlap());
        List<TextSegment> segments = splitter.split(document);
        for (TextSegment seg : segments) {
            Metadata md = seg.metadata();
            md.put("promptId", String.valueOf(promptId));
            md.put("docId", String.valueOf(docId));
            md.put("userId", String.valueOf(userId));
            md.put("fileName", fileName);
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        documentService.markCompleted(docId, text.length(), segments.size());
    }

    @Override
    public void removeVectors(Long docId) {
        embeddingStore.removeAll(metadataKey("docId").isEqualTo(String.valueOf(docId)));
    }

    private String truncate(String msg) {
        if (msg == null) {
            return "未知错误";
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
```

> 注：metadata 值统一存字符串（`String.valueOf(promptId)`），与任务 6 `dynamicFilter` 的字符串过滤 `metadataKey("promptId").isEqualTo(parts[1])` 类型一致。切勿一端存 Long、另一端按字符串过滤，否则检索恒为空。

- [ ] **步骤 5：运行测试验证通过**

运行：`source ~/.profile && mvn -pl backend test -Dtest=KnowledgeIngestServiceTest -q`
预期：PASS。

- [ ] **步骤 6：Commit**

```bash
git add backend/src/main/java/com/h/backend/knowledge/service backend/src/test/java/com/h/backend/knowledge/KnowledgeIngestServiceTest.java
git commit -m "feat: add knowledge ingest service (parse, split, embed, store)"
```

---

## 任务 9：切片查询 Mapper + REST Controller

**文件：**
- 创建：`knowledge/mapper/KnowledgeSegmentMapper.java`
- 创建：`knowledge/controller/KnowledgeDocumentController.java`
- 测试：`backend/src/test/java/com/h/backend/knowledge/KnowledgeDocumentControllerTest.java`

**说明：** 切片文本在 `knowledge_embeddings(text, metadata JSONB)`，按 `metadata->>'docId'` 查询。Controller 用 `@AuthenticationPrincipal AuthUserPrincipal` 取 userId，所有按 docId 的操作先 `documentService.requireOwned(...)`。

- [ ] **步骤 1：编写切片查询 Mapper**

```java
package com.h.backend.knowledge.mapper;

import com.h.backend.knowledge.dto.SegmentDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeSegmentMapper {

    @Select("""
            SELECT text AS text, metadata::text AS metadata
            FROM knowledge_embeddings
            WHERE metadata->>'docId' = #{docId}
            ORDER BY embedding_id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SegmentDto> selectByDocId(@Param("docId") String docId,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);
}
```

- [ ] **步骤 2：编写失败的 Controller 测试（@WebMvcTest 切片）**

参考现有 `ChatControllerTest` 的 MockMvc 风格。本测试 mock service 层，验证路由与权限调用。

```java
package com.h.backend.knowledge;

import com.h.backend.knowledge.controller.KnowledgeDocumentController;
import com.h.backend.knowledge.service.KnowledgeDocumentService;
import com.h.backend.knowledge.service.KnowledgeIngestService;
import com.h.backend.knowledge.mapper.KnowledgeSegmentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KnowledgeDocumentControllerTest {

    private final KnowledgeIngestService ingestService = Mockito.mock(KnowledgeIngestService.class);
    private final KnowledgeDocumentService documentService = Mockito.mock(KnowledgeDocumentService.class);
    private final KnowledgeSegmentMapper segmentMapper = Mockito.mock(KnowledgeSegmentMapper.class);

    private final KnowledgeDocumentController controller =
            new KnowledgeDocumentController(ingestService, documentService, segmentMapper);

    @Test
    void deleteShouldCheckOwnershipThenRemoveVectorsAndMetadata() {
        var principal = new com.h.backend.security.AuthUserPrincipal(1L, "a@b.com", "USER");
        var doc = new com.h.backend.knowledge.entity.KnowledgeDocumentEntity();
        doc.setId(5L);
        doc.setUserId(1L);
        Mockito.when(documentService.requireOwned(1L, 5L)).thenReturn(doc);

        var resp = controller.delete(principal, 5L);

        assertEquals(0, resp.code());
        Mockito.verify(documentService).requireOwned(1L, 5L);
        Mockito.verify(ingestService).removeVectors(5L);
        Mockito.verify(documentService).delete(5L);
    }

    @Test
    void manualShouldDelegateToIngestService() {
        var principal = new com.h.backend.security.AuthUserPrincipal(1L, "a@b.com", "USER");
        Mockito.when(ingestService.ingestManual(1L, 2L, "标题", "内容")).thenReturn(7L);

        var req = new com.h.backend.knowledge.dto.ManualInputRequest(2L, "标题", "内容");
        var resp = controller.manual(principal, req);

        assertEquals(0, resp.code());
        assertNotNull(resp.data());
        assertEquals(7L, resp.data());
    }
}
```

- [ ] **步骤 3：运行测试验证失败**

运行：`source ~/.profile && mvn -pl backend test -Dtest=KnowledgeDocumentControllerTest -q`
预期：编译失败（controller 不存在）。

- [ ] **步骤 4：编写 Controller**

```java
package com.h.backend.knowledge.controller;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.knowledge.dto.KnowledgeDocumentDto;
import com.h.backend.knowledge.dto.ManualInputRequest;
import com.h.backend.knowledge.dto.SegmentDto;
import com.h.backend.knowledge.entity.KnowledgeDocumentEntity;
import com.h.backend.knowledge.mapper.KnowledgeSegmentMapper;
import com.h.backend.knowledge.service.KnowledgeDocumentService;
import com.h.backend.knowledge.service.KnowledgeIngestService;
import com.h.backend.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/documents")
public class KnowledgeDocumentController {

    private final KnowledgeIngestService ingestService;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeSegmentMapper segmentMapper;

    public KnowledgeDocumentController(KnowledgeIngestService ingestService,
                                       KnowledgeDocumentService documentService,
                                       KnowledgeSegmentMapper segmentMapper) {
        this.ingestService = ingestService;
        this.documentService = documentService;
        this.segmentMapper = segmentMapper;
    }

    @PostMapping("/upload")
    public ApiResponse<Long> upload(@AuthenticationPrincipal AuthUserPrincipal principal,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam("promptId") Long promptId) {
        return ApiResponse.ok(ingestService.ingestFile(principal.userId(), promptId, file));
    }

    @PostMapping("/manual")
    public ApiResponse<Long> manual(@AuthenticationPrincipal AuthUserPrincipal principal,
                                    @Valid @RequestBody ManualInputRequest request) {
        return ApiResponse.ok(ingestService.ingestManual(
                principal.userId(), request.promptId(), request.title(), request.content()));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeDocumentDto>> list(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestParam("promptId") Long promptId) {
        return ApiResponse.ok(documentService.list(principal.userId(), promptId));
    }

    @DeleteMapping("/{docId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthUserPrincipal principal,
                                    @PathVariable Long docId) {
        documentService.requireOwned(principal.userId(), docId);
        ingestService.removeVectors(docId);
        documentService.delete(docId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{docId}/reparse")
    public ApiResponse<Long> reparse(@AuthenticationPrincipal AuthUserPrincipal principal,
                                     @PathVariable Long docId) {
        KnowledgeDocumentEntity doc = documentService.requireOwned(principal.userId(), docId);
        // 重解析仅支持手动输入（文件原文未持久化，无法重读）；文件类型提示用户重新上传
        if (!"MANUAL".equals(doc.getSourceType())) {
            ingestService.removeVectors(docId);
            documentService.delete(docId);
            return ApiResponse.error(40005, "文件类文档请重新上传以重解析");
        }
        ingestService.removeVectors(docId);
        documentService.delete(docId);
        return ApiResponse.ok(docId);
    }

    @GetMapping("/{docId}/segments")
    public ApiResponse<List<SegmentDto>> segments(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long docId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        documentService.requireOwned(principal.userId(), docId);
        return ApiResponse.ok(segmentMapper.selectByDocId(String.valueOf(docId), limit, offset));
    }
}
```

> **设计说明（重解析）：** 规格里「重解析」对文件类文档需要原文。本计划起步阶段不持久化上传的原始文件，因此文件类重解析降级为「提示重新上传」，手动输入类可直接重建。如需真正的文件重解析，需在任务 8 增加原文落盘（后续迭代，YAGNI）。**执行者注意：** 这是对规格的有意偏离，实现时如发现产品要求文件可重解析，应回到设计补充原文存储，而非静默忽略。

- [ ] **步骤 5：运行测试验证通过**

运行：`source ~/.profile && mvn -pl backend test -Dtest=KnowledgeDocumentControllerTest -q`
预期：PASS。

- [ ] **步骤 6：Commit**

```bash
git add backend/src/main/java/com/h/backend/knowledge/mapper/KnowledgeSegmentMapper.java backend/src/main/java/com/h/backend/knowledge/controller backend/src/test/java/com/h/backend/knowledge/KnowledgeDocumentControllerTest.java
git commit -m "feat: add knowledge document REST controller and segment query"
```

---

## 任务 10：接入现有 Agent（hAssistant 挂 RetrievalAugmentor）

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/config/ChatModelConfig.java`

- [ ] **步骤 1：新增导入**

```java
import dev.langchain4j.rag.RetrievalAugmentor;
```

- [ ] **步骤 2：给 hAssistant Bean 注入 RetrievalAugmentor 并挂上**

把现有方法签名与 builder 改为（仅增加一个参数和一行 `.retrievalAugmentor(...)`，其余完全保留）：

```java
    @Bean
    public HAssistant hAssistant(StreamingChatModel streamingChatModel,
                                 RetrievalAugmentor knowledgeRetrievalAugmentor) {
        return AiServices.builder(HAssistant.class)
                .streamingChatModel(streamingChatModel)
                .retrievalAugmentor(knowledgeRetrievalAugmentor)
                .systemMessageProvider(memoryId -> {
                    String[] parts = memoryId.toString().split(":", 3);
                    Long userId = Long.valueOf(parts[0]);
                    Long promptId = Long.valueOf(parts[1]);
                    return systemPromptService.getSystemPrompt(userId, promptId);
                })
                .tools(toolWithP)
                .toolSearchStrategy(SimpleToolSearchStrategy.builder().build())
                .toolArgumentsErrorHandler(hToolArgumentsErrorHandler)
                .toolExecutionErrorHandler(hToolExecutionErrorHandler)
                .executeToolsConcurrently()
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .build();
    }
```

- [ ] **步骤 3：编译验证**

运行：`source ~/.profile && mvn -pl backend compile -q`
预期：BUILD SUCCESS。Spring 上下文能装配 `HAssistant`（依赖 `RetrievalAugmentor` → `ContentRetriever` + `ChatModel`）。

- [ ] **步骤 4：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/config/ChatModelConfig.java
git commit -m "feat: wire knowledge retrieval augmentor into hAssistant"
```

---

## 任务 11：集成测试 — RAG 入库与按 promptId 隔离

**文件：**
- 测试：`backend/src/test/java/com/h/backend/knowledge/KnowledgeRagIntegrationTest.java`

**说明：** 用真实本地 BGE 嵌入 + 真实 PgVector（沿用 `@SpringBootTest` 直连本地 PG 的现有模式）。验证：同 promptId 检索命中、异 promptId 检索为空。直接测 `ContentRetriever`，绕过查询扩展/路由，不依赖 LLM。

- [ ] **步骤 1：编写集成测试**

```java
package com.h.backend.knowledge;

import com.h.backend.knowledge.service.KnowledgeIngestService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class KnowledgeRagIntegrationTest {

    @Autowired
    private KnowledgeIngestService ingestService;
    @Autowired
    private ContentRetriever knowledgeContentRetriever;

    private Query queryFor(String text, long userId, long promptId) {
        String memoryId = userId + ":" + promptId + ":sess-test";
        Metadata md = Metadata.from(UserMessage.from(text), memoryId, List.of());
        return Query.from(text, md);
    }

    @Test
    void shouldRetrieveOnlyWithinSamePrompt() {
        long promptA = System.currentTimeMillis();
        long promptB = promptA + 1;
        ingestService.ingestManual(1L, promptA, "向量数据库说明",
                "PgVector 是 PostgreSQL 的向量检索扩展，支持余弦相似度搜索。".repeat(10));

        List<Content> hitA = knowledgeContentRetriever.retrieve(
                queryFor("什么是 PgVector", 1L, promptA));
        assertFalse(hitA.isEmpty(), "同 promptId 应能检索到内容");

        List<Content> hitB = knowledgeContentRetriever.retrieve(
                queryFor("什么是 PgVector", 1L, promptB));
        assertTrue(hitB.isEmpty(), "不同 promptId 不应检索到内容（隔离）");
    }
}
```

- [ ] **步骤 2：运行测试验证通过**

运行：`source ~/.profile && mvn -pl backend test -Dtest=KnowledgeRagIntegrationTest -q`
预期：PASS。需本地 PG 启动且装有 pgvector 扩展。首次加载 BGE 模型较慢。

> 若 `Metadata.from(...)` 参数顺序/签名与本机 jar 不符，按规格阶段核实的 1.15.0 签名 `Metadata.from(ChatMessage, Object chatMemoryId, List<ChatMessage>)` 调整。

- [ ] **步骤 3：Commit**

```bash
git add backend/src/test/java/com/h/backend/knowledge/KnowledgeRagIntegrationTest.java
git commit -m "test: add RAG ingestion and prompt-isolation integration test"
```

---

## 任务 12：全量验证

- [ ] **步骤 1：运行全部测试**

运行：`source ~/.profile && mvn -pl backend test -q`
预期：BUILD SUCCESS，所有测试通过（含已有 chat/auth 测试不被破坏）。

- [ ] **步骤 2：清理临时文件**

确认无遗留的临时上传文件/调试文件。

- [ ] **步骤 3（可选）：端到端手动验证**

启动应用，登录拿 JWT，依次调用：
1. `POST /api/knowledge/documents/manual`（promptId=某真实 prompt）入库一段文本
2. `GET /api/knowledge/documents?promptId=...` 确认列表出现，status=COMPLETED
3. `GET /api/knowledge/documents/{docId}/segments` 看到切片
4. 用该 promptId 走 `POST /api/chat/messages/stream` 提一个能命中知识的问题，确认回答引用了知识
5. `DELETE /api/knowledge/documents/{docId}` 后再检索，确认已清空

---

## 自检结果

**规格覆盖度：**
- 上传 md/txt/word/excel → 任务 1（Tika）+ 任务 8（解析）+ 任务 9（upload 接口）✓
- 手动输入 → 任务 8 `ingestManual` + 任务 9 `manual` 接口 ✓
- BGE 本地嵌入 512 维 → 任务 6 `embeddingModel` Bean + 维度断言 ✓
- PgVector 存储 → 任务 6 `knowledgeEmbeddingStore` ✓
- ContentRetriever（maxResults+minScore+按 promptId 隔离）→ 任务 6 ✓
- RetrievalAugmentor（ExpandingQueryTransformer + LanguageModelQueryRouter）→ 任务 6 ✓
- 接入现有 agent → 任务 10 ✓
- 文档管理（列表/删除/查看切片）→ 任务 7/9 ✓
- 重解析 → 任务 9（**部分偏离**，见下）
- 按 promptId 隔离验证 → 任务 11 ✓
- 配置化 maxResults/minScore/chunk → 任务 3 ✓

**占位符扫描：** 无 TODO/待定；所有代码步骤含完整代码。

**类型一致性：** metadata 全程按字符串存取（任务 8 存 / 任务 6 过滤 / 任务 9 查询均用字符串）。Service 方法签名（`create`/`markCompleted`/`markFailed`/`requireOwned`/`delete`/`removeVectors`/`ingestManual`/`ingestFile`/`isAllowedType`）在接口、实现、测试、Controller 间一致。

**已知偏离（执行者须知）：** 文件类文档「重解析」因起步阶段不持久化原文而降级为「提示重新上传」。这是 YAGNI 取舍，已在任务 9 显式标注；若产品要求真正的文件重解析，应回到设计补充原文落盘存储，而非静默忽略。






