#!/usr/bin/env bash
# mem0 自托管栈启动脚本
# 依赖: 本地 PostgreSQL(含 pgvector 扩展) 已在跑、LLM Key 已配
# 用法: ./start.sh   （首次运行自动克隆源码并构建镜像，需几分钟）
set -euo pipefail
cd "$(dirname "$0")"

MEM0_VERSION="v2.0.19"
API_PORT=8888
DASH_PORT=3001
PG_HOST=localhost
PG_PORT=5432
PG_USER=h_agent
PG_PASSWORD=h_agent
PG_DB=h_agent_mem0
PSQL_BIN=/Library/PostgreSQL/18/bin/psql

# 1. 端口预检（mem0 自身占用的两个口）
for p in $API_PORT $DASH_PORT; do
  if lsof -iTCP:$p -sTCP:LISTEN >/dev/null 2>&1; then
    echo "错误: 端口 $p 已被占用（$(lsof -nP -iTCP:$p -sTCP:LISTEN | tail -1 | awk '{print $1}')）"
    exit 1
  fi
done

# 2. 源码（钉版本，mem0 官方服务端需从源码构建，无预构建镜像）
if [ ! -d repo/server ]; then
  echo "克隆 mem0 源码 ($MEM0_VERSION)..."
  git clone --depth 1 --branch "$MEM0_VERSION" https://github.com/mem0ai/mem0.git repo
fi

# 2.1 官方坑位修复1: requirements.txt 的 psycopg 缺 [binary]，slim 镜像无 libpq 会启动崩溃
if ! grep -q '^psycopg\[binary\]' repo/server/requirements.txt; then
  sed -i '' 's/^psycopg>=/psycopg[binary]>=/' repo/server/requirements.txt
  echo "已修补 requirements.txt（psycopg → psycopg[binary]）"
fi

# 2.2 官方坑位修复2: mem0ai 不钉版本会拉到未来新版，钉到与 git tag 一致的 2.0.19
#     （compose 启动命令里的 sed 补丁依赖该版本的第 18 行代码形态）
if ! grep -q '^mem0ai==2.0.19$' repo/server/requirements.txt; then
  sed -i '' 's/^mem0ai>=.*/mem0ai==2.0.19/' repo/server/requirements.txt
  echo "已修补 requirements.txt（mem0ai 钉到 2.0.19）"
fi

# 2.3 官方坑位修复3: Dockerfile 基础镜像写死 Docker Hub，国内直连超时——改为可注入国内镜像源
if ! grep -q '^ARG BASE_IMAGE' repo/server/Dockerfile; then
  sed -i '' '1s/^FROM python:3.12-slim$/ARG BASE_IMAGE=python:3.12-slim\nFROM ${BASE_IMAGE}/' repo/server/Dockerfile
  echo "已修补 server/Dockerfile（基础镜像可注入）"
fi
if ! grep -q '^ARG BASE_IMAGE' repo/server/dashboard/Dockerfile; then
  sed -i '' '1s/^FROM node:20-alpine AS base$/ARG BASE_IMAGE=node:20-alpine\nFROM ${BASE_IMAGE} AS base/' repo/server/dashboard/Dockerfile
  echo "已修补 dashboard/Dockerfile（基础镜像可注入）"
fi

# 2.4 官方坑位修复4: 容器内 pypi.org 直连被墙，pip 换清华镜像
if ! grep -q 'pypi.tuna.tsinghua.edu.cn' repo/server/Dockerfile; then
  sed -i '' 's|RUN pip install --no-cache-dir -r requirements.txt|RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt|' repo/server/Dockerfile
  echo "已修补 server/Dockerfile（pip 清华镜像）"
fi

# 3. 配置
if [ ! -f .env ]; then
  echo "生成 .env 模板，请填写 MINIMAX_API_KEY 后重跑"
  cat > .env <<EOF
OPENAI_API_KEY=
MINIMAX_API_KEY=
MINIMAX_BASE_URL=https://api.minimaxi.com/v1
POSTGRES_HOST=host.docker.internal
POSTGRES_PORT=5432
POSTGRES_DB=$PG_DB
POSTGRES_USER=$PG_USER
POSTGRES_PASSWORD=$PG_PASSWORD
POSTGRES_COLLECTION_NAME=memories
JWT_SECRET=$(openssl rand -base64 36 | tr -d '/+=')
AUTH_DISABLED=false
DASHBOARD_URL=http://localhost:$DASH_PORT
APP_DB_NAME=h_agent_mem0_app
MEM0_TELEMETRY=false
REQUEST_LOG_RETENTION_DAYS=30
EOF
  exit 1
fi

# 3.1 前端 API 地址动态化补丁: 官方把 NEXT_PUBLIC_API_URL 构建期烘焙进 JS，写死具体 IP 后
#     宿主机换 IP 局域网就访问不通。改为浏览器运行时取 window.location.hostname 动态拼接，
#     从哪个地址打开页面就调哪个地址，IP 无关。补丁存 patches/，重跑幂等。
if ! cmp -s patches/dashboard/api.ts repo/server/dashboard/src/utils/api.ts; then
  cp patches/dashboard/api.ts repo/server/dashboard/src/utils/api.ts
  cp patches/dashboard/memories-page.tsx "repo/server/dashboard/src/app/(root)/dashboard/memories/page.tsx"
  cp patches/dashboard/setup-page.tsx repo/server/dashboard/src/app/setup/page.tsx
  echo "已应用前端动态 API 地址补丁（IP 变化不再需要任何操作）"
fi

# 4. 本地 PG 预检（mem0 数据都落在本地 PG）
if [ -x "$PSQL_BIN" ]; then
  if ! PGPASSWORD="$PG_PASSWORD" "$PSQL_BIN" -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -tc "SELECT 1" >/dev/null 2>&1; then
    echo "错误: 本地 PG 预检失败（$PG_USER@$PG_HOST:$PG_PORT/$PG_DB）—— PG 是否在跑？库是否已建？"
    exit 1
  fi
  echo "本地 PG 预检通过 ($PG_DB)"
else
  echo "警告: 未找到 psql($PSQL_BIN)，跳过 PG 预检"
fi

# 5. 构建并启动（首次构建较慢）
echo "启动 mem0 栈（首次需构建镜像）..."
docker compose up -d --build

# 6. 就绪等待（一次性，不常驻轮询）
echo -n "等待 API 就绪"
for i in $(seq 1 60); do
  curl -fsS --max-time 2 "http://localhost:$API_PORT/auth/setup-status" >/dev/null 2>&1 && break
  echo -n "."; sleep 2
done
echo ""
echo -n "等待仪表盘就绪"
for i in $(seq 1 30); do
  curl -fsS --max-time 2 "http://localhost:$DASH_PORT/api/health" >/dev/null 2>&1 && break
  echo -n "."; sleep 2
done
echo ""

echo ""
echo "================ mem0 已就绪 ================"
echo "  仪表盘:   http://localhost:$DASH_PORT   <- 首次访问引导创建管理员"
echo "  局域网:   http://<本机任意IP>:$DASH_PORT （IP 随便换，前端动态取址）"
echo "  REST API: http://localhost:$API_PORT"
echo "  数据库:   本地PG h_agent_mem0 / h_agent_mem0_app"
echo "============================================="
echo "首次使用别忘了:"
echo "  1) 仪表盘建管理员后进 Configuration 页配 LLM(MiniMax) 和 Embedder"
echo "  2) 生成的 m0sk-... API Key 只显示一次，立即保存"
