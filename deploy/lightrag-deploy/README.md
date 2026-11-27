# 在另一台设备上用 Docker 部署 LightRAG

在目标设备上只启动 **LightRAG Server v1.5.8**。复用 h-agent 现有的 PostgreSQL 实例，给 LightRAG 单独建库 `h_agent_lightrag` 和账号 `lightrag_app`。本文命令按执行位置标注，数据库准备工作由现有 PostgreSQL 的管理员执行。

## 存储怎么复用

| 数据 | 存放位置 |
| --- | --- |
| 文档正文、文本分块、缓存、处理状态、配置 | 现有 PostgreSQL 实例内的 `h_agent_lightrag` |
| 向量 | 同一个库里的 `pgvector`，扩展名为 `vector` |
| 图谱节点及关系 | 同一个库里的普通 PostgreSQL 表，由 `PGTableGraphStorage` 管理 |
| 通过 WebUI 上传的文件、工作目录、可选提示词 | 目标设备的 `./data/`，挂载到容器 |
| h-agent 已有业务文件 | 继续由现有 MinIO 管理。接入应用时，可从 MinIO 读取选定文件，再提交给 LightRAG 的 `/documents/upload` |

`PGTableGraphStorage` 需要 PostgreSQL 14+，图谱表无需 Apache AGE。这套 Compose 只包含一个服务，也无需为 LightRAG 准备 Redis、Neo4j 或单独的向量数据库。仓库中 Langfuse 与 Mem0 已采用容器连接现有 PostgreSQL 的做法。确认目标设备能访问实际实例后，再使用下面的配置。 [Langfuse Compose](../langfuse-deploy/docker-compose.yml) · [Mem0 Compose](../mem0-deploy/docker-compose.yml) · [LightRAG PostgreSQL 存储说明](https://github.com/HKUDS/LightRAG/blob/v1.5.8/docs/LightRAG-API-Server.md#storage-selection)

## 1. 在 PostgreSQL 所在设备上准备数据库

用现有实例的管理员账号进入 `psql`，执行以下一次性初始化。`\password` 会交互提示输入密码，将该密码保存给 LightRAG 的 `.env`。

```sql
SHOW server_version;
SELECT name, default_version FROM pg_available_extensions WHERE name = 'vector';

SET password_encryption = 'scram-sha-256';
CREATE ROLE lightrag_app LOGIN;
\password lightrag_app
CREATE DATABASE h_agent_lightrag OWNER lightrag_app;
REVOKE CONNECT ON DATABASE h_agent_lightrag FROM PUBLIC;
\connect h_agent_lightrag
CREATE EXTENSION IF NOT EXISTS vector;
GRANT USAGE, CREATE ON SCHEMA public TO lightrag_app;
SELECT extversion FROM pg_extension WHERE extname = 'vector';
```

`pg_available_extensions` 应有 `vector` 一行。示例使用 HNSW 索引，请确认 `vector` 版本至少为 `0.5.0`。扩展的程序文件可以复用，但每个数据库都需要单独启用扩展。由 LightRAG 首次启动时以专用账号建应用表。 [pgvector 版本记录](https://github.com/pgvector/pgvector/blob/master/CHANGELOG.md)

### 让目标设备访问现有实例

如果数据库已经通过私网或 VPN 对目标设备可达，只需确认新库和新账号的访问规则。仓库里的 PostgreSQL 配置是同机部署示例，另一台设备上的容器需要使用数据库主机可达的地址。

以下以数据库设备 `192.168.10.20`、LightRAG 设备 `192.168.10.30` 为例。用 `SHOW config_file;`、`SHOW hba_file;` 和 `SHOW listen_addresses;` 查看实际配置。若服务目前只监听回环地址，在 `postgresql.conf` 的 `listen_addresses` 中追加数据库设备的私网地址，保留原有监听地址。

```conf
listen_addresses = 'localhost,192.168.10.20'
```

在 `pg_hba.conf` 加入专用规则，放在可能先匹配该连接的通用规则之前。`/32` 填数据库实际看到的客户端地址。防火墙仅对目标设备开放数据库端口。

```conf
host  h_agent_lightrag  lightrag_app  192.168.10.30/32  scram-sha-256
```

修改 `listen_addresses` 需要按现有实例的运维方式重启 PostgreSQL。只改 `pg_hba.conf` 时，用管理员执行 `SELECT pg_reload_conf();` 即可。这份示例适用于受控私网或加密 VPN。若以 PostgreSQL TLS 保护链路，使用 `hostssl`，在 LightRAG 设置 `POSTGRES_SSL_MODE=verify-full`，并用与证书匹配的主机名。私有 CA 还需将证书文件只读挂入容器并设置 `POSTGRES_SSL_ROOT_CERT`。 [PostgreSQL 监听配置](https://www.postgresql.org/docs/18/runtime-config-connection.html) · [连接规则](https://www.postgresql.org/docs/18/auth-pg-hba-conf.html)

如果目标设备已有 `psql`，先验证连接和扩展。结果应显示 `h_agent_lightrag`、`lightrag_app` 和扩展版本。

```bash
psql -h 192.168.10.20 -p 5432 -U lightrag_app -d h_agent_lightrag \
  -c "SELECT current_database(), current_user, extversion FROM pg_extension WHERE extname = 'vector';"
```

## 2. 在目标设备上准备 Compose 和 `.env`

目标设备需要 Docker Engine 或 Docker Desktop，以及 Docker Compose v2。官方镜像 `ghcr.io/hkuds/lightrag:v1.5.8` 提供 Linux `amd64` 和 `arm64`。 [LightRAG v1.5.8](https://github.com/HKUDS/LightRAG/releases/tag/v1.5.8) · [镜像包](https://github.com/HKUDS/LightRAG/pkgs/container/lightrag)

```bash
mkdir -p ~/lightrag-deploy
cd ~/lightrag-deploy
mkdir -p data/inputs data/rag_storage data/prompts
touch .env
chmod 600 .env
```

在此目录创建 `docker-compose.yml`。

```yaml
name: lightrag

services:
  lightrag:
    image: ghcr.io/hkuds/lightrag:v1.5.8
    restart: unless-stopped
    env_file: .env
    ports:
      - "127.0.0.1:9621:9621"
    environment:
      HOST: "0.0.0.0"
      PORT: "9621"
      INPUT_DIR: /app/data/inputs
      WORKING_DIR: /app/data/rag_storage
      PROMPT_DIR: /app/data/prompts
    volumes:
      - ./data/inputs:/app/data/inputs
      - ./data/rag_storage:/app/data/rag_storage
      - ./data/prompts:/app/data/prompts
```

填写 `.env`。将示例 IP、聊天模型地址、模型 ID 和凭据换成实际值。聊天模型可复用已有的 **OpenAI 兼容** `/chat/completions` 服务，下方 Embedding 则按 Mem0 部署所用的硅基流动 `BAAI/bge-m3` 示例填写。`*_BINDING_HOST` 填 API 基础地址，例如以 `/v1` 结尾。 [LightRAG v1.5.8 配置模板](https://github.com/HKUDS/LightRAG/blob/v1.5.8/env.example) · [硅基流动 Embedding 文档](https://api-docs.siliconflow.cn/docs/api/embeddings-post)

```dotenv
WORKSPACE=h_agent_lightrag
SUMMARY_LANGUAGE=Chinese

LIGHTRAG_KV_STORAGE=PGKVStorage
LIGHTRAG_DOC_STATUS_STORAGE=PGDocStatusStorage
LIGHTRAG_VECTOR_STORAGE=PGVectorStorage
LIGHTRAG_GRAPH_STORAGE=PGTableGraphStorage
LIGHTRAG_CONFIG_STORAGE=PGKVStorage

POSTGRES_HOST=192.168.10.20
POSTGRES_PORT=5432
POSTGRES_DATABASE=h_agent_lightrag
POSTGRES_USER=lightrag_app
POSTGRES_PASSWORD='REPLACE_WITH_DATABASE_PASSWORD'
POSTGRES_MAX_CONNECTIONS=10
POSTGRES_VECTOR_INDEX_TYPE=HNSW

LLM_BINDING=openai
LLM_BINDING_HOST=https://llm.example.com/v1
LLM_BINDING_API_KEY='REPLACE_WITH_CHAT_MODEL_KEY'
LLM_MODEL=REPLACE_WITH_CHAT_MODEL_ID

EMBEDDING_BINDING=openai
EMBEDDING_BINDING_HOST=https://api.siliconflow.cn/v1
EMBEDDING_BINDING_API_KEY='REPLACE_WITH_EMBEDDING_KEY'
EMBEDDING_MODEL=BAAI/bge-m3
EMBEDDING_DIM=1024
EMBEDDING_TOKEN_LIMIT=8192
EMBEDDING_SEND_DIM=false
EMBEDDING_USE_BASE64=false

LIGHTRAG_API_KEY='REPLACE_WITH_LIGHTRAG_API_KEY'
AUTH_ACCOUNTS='admin:REPLACE_WITH_ADMIN_PASSWORD'
TOKEN_SECRET='REPLACE_WITH_JWT_SIGNING_SECRET'
```

- `LIGHTRAG_API_KEY` 用于 REST API，`AUTH_ACCOUNTS` 用于 WebUI 登录。管理员密码、API Key、JWT 签名密钥各自生成，例如分别运行 `openssl rand -hex 32`。`.env` 仅保存在部署设备上。 [LightRAG 鉴权说明](https://github.com/HKUDS/LightRAG/blob/v1.5.8/docs/LightRAG-API-Server.md#api-key-and-authentication)
- `EMBEDDING_SEND_DIM=false` 让固定维度的 BGE-M3 使用模型默认输出。更换 Embedding 时，在首次入库前确认实际输出维度，并按维度选择索引。v1.5.8 会检查已有工作区的 Embedding 配置基线，变更模型需要按官方流程重建向量。 [Embedding 配置说明](https://github.com/HKUDS/LightRAG/blob/v1.5.8/env.example) · [v1.5.8 发布说明](https://github.com/HKUDS/LightRAG/releases/tag/v1.5.8)
- `WORKING_DIR` 会保存配置存储的绑定信息，即使应用数据都在 PostgreSQL，也要保留 `data/rag_storage`。 [配置存储说明](https://github.com/HKUDS/LightRAG/blob/v1.5.8/env.example)

## 3. 在目标设备上启动和验证

```bash
docker compose config --quiet
docker compose pull
docker compose up -d
docker compose ps
docker compose logs --tail=100 lightrag
curl -fsS http://127.0.0.1:9621/health
```

`/health` 用于确认服务响应。再在目标设备访问 `http://127.0.0.1:9621/webui/`，使用 `.env` 里的 `admin` 账号登录。在另一台电脑上操作浏览器时，可以通过 SSH 转发访问 WebUI，Compose 端口仍绑定目标设备的回环地址。

```bash
ssh -N -L 19621:127.0.0.1:9621 deploy@192.168.10.30
```

SSH 命令中的 `deploy@192.168.10.30` 换成目标设备的实际 SSH 账号和地址，再打开 `http://127.0.0.1:19621/webui/`。登录后，将下面两段分别保存为文本文件并上传，待两份状态都显示 `Processed`。

| 文件 | 内容 |
| --- | --- |
| `smoke-rental.txt` | 测试租车订单 LR-100 使用车辆 V-023。 |
| `smoke-repair.txt` | 车辆 V-023 的维修工单为 R-091，本次维修更换了前轮轮胎。 |

在 WebUI 用 `mix` 模式提问「租车订单 LR-100 对应的车辆，在哪张工单里做了什么维修？」预期能找到车辆 V-023、工单 R-091 和更换前轮轮胎的信息，并能核对两份来源。也可以在目标设备用 API 验证同一个问题。 [LightRAG 查询与引用说明](https://github.com/HKUDS/LightRAG/blob/v1.5.8/docs/LightRAG-API-Server.md)

```bash
printf 'LightRAG API Key: '
read -rs LIGHTRAG_TEST_KEY
printf '\n'
curl -fsS http://127.0.0.1:9621/query \
  -H "X-API-Key: ${LIGHTRAG_TEST_KEY}" \
  -H 'Content-Type: application/json' \
  --data '{"query":"租车订单 LR-100 对应的车辆，在哪张工单里做了什么维修？","mode":"mix","include_references":true}'
unset LIGHTRAG_TEST_KEY
```

需要确认图谱确实写进了现有 PostgreSQL 时，在专用库执行以下两条查询。样例完成索引后，两张表应该都有数据。

```sql
SELECT count(*) FROM lightrag_graph_nodes;
SELECT count(*) FROM lightrag_graph_edges;
```

## 日常维护和排障

| 现象 | 检查 |
| --- | --- |
| PostgreSQL 连接超时或被拒绝 | 检查 `POSTGRES_HOST`、监听地址、线路和防火墙；`host.docker.internal` 指容器所在设备，跨设备用数据库的私网地址。 |
| `no pg_hba.conf entry` 或密码错误 | 确认数据库实际看到的客户端 IP、规则顺序、库名、账号和 TLS 模式。 |
| `vector` 缺失或建表权限不足 | 确认扩展启用在 `h_agent_lightrag`，并核对账号对 `public` 的权限。 |
| `/health` 正常，文档处理失败 | 看 `docker compose logs --tail=100 lightrag`，检查模型服务的基础地址、凭据、模型 ID 和 Embedding 返回维度。 |
| WebUI 无法登录或 `/query` 返回 `401` | WebUI 检查 `AUTH_ACCOUNTS`、`TOKEN_SECRET`。REST API 检查 `X-API-Key` 使用的是否为 `LIGHTRAG_API_KEY`。 |

停止用 `docker compose stop`，恢复用 `docker compose up -d`。改 `.env` 后用 `docker compose up -d --force-recreate lightrag` 重新注入配置。备份时先暂停 LightRAG 写入，在现有 PostgreSQL 的备份任务里纳入 `h_agent_lightrag`，同时备份部署目录中的 `data/`，并单独妥善保管 `.env`。升级前备份数据库和目录，再按目标版本的发布说明调整固定镜像标签。
