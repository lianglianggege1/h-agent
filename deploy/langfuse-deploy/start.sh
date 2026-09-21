#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

command -v docker >/dev/null || { echo "未找到 docker"; exit 1; }

# 端口预检：web 需要宿主机 3000
if command -v lsof >/dev/null 2>&1; then
  if lsof -nP -iTCP:3000 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "端口 3000 已被占用，请修改 docker-compose.yml 端口映射" >&2
    exit 1
  fi
fi

# 首次运行先生成 .env（Langfuse 自身密钥随机；外部 PG/Redis 凭据留占位需手填）
if [ ! -f .env ]; then
  {
    echo "NEXTAUTH_URL=http://localhost:3000"
    echo "NEXTAUTH_SECRET=$(openssl rand -base64 32)"
    echo "SALT=$(openssl rand -base64 32)"
    echo "ENCRYPTION_KEY=$(openssl rand -hex 32)"
    echo ""
    echo "# 外部 Postgres（宿主机本地）"
    echo "DATABASE_URL=postgresql://h_agent:h_agent@host.docker.internal:5432/h_agent_obs"
    echo ""
    echo "# 外部 Redis（宿主机本地）"
    echo "REDIS_AUTH=123456"
    echo ""
    echo "CLICKHOUSE_USER=clickhouse"
    echo "CLICKHOUSE_PASSWORD=$(openssl rand -hex 12)"
    echo ""
    echo "# S3 存储复用 minio-deploy 的 MinIO"
    MINIO_ENV=../minio-deploy/.env
    if [ -f "${MINIO_ENV}" ]; then
      grep -E '^(MINIO_ROOT_USER|MINIO_ROOT_PASSWORD)=' "${MINIO_ENV}"
    else
      echo "# 未找到 ../minio-deploy/.env，请手动填入 MinIO 凭据"
      echo "MINIO_ROOT_USER="
      echo "MINIO_ROOT_PASSWORD="
    fi
    echo "LANGFUSE_S3_BUCKET=langfuse"
  } > .env
  chmod 600 .env
  echo "首次运行：已生成 .env（外部 PG/Redis 凭据为默认占位，请按实际情况修改）"
fi

# 依赖预检：MinIO（minio-deploy）、宿主机本地 Redis、宿主机本地 Postgres
if ! curl -sf --max-time 3 http://localhost:9000/minio/health/live >/dev/null 2>&1; then
  echo "MinIO 未运行（http://localhost:9000 不可达）。请先启动: ../minio-deploy/start.sh" >&2
  exit 1
fi

REDIS_AUTH=$(sed -n 's/^REDIS_AUTH=//p' .env)
if command -v redis-cli >/dev/null 2>&1; then
  redis-cli -h 127.0.0.1 -a "${REDIS_AUTH}" --no-auth-warning ping 2>/dev/null | grep -q PONG || { echo "Redis 不可达或密码错误（127.0.0.1:6379）" >&2; exit 1; }
else
  nc -z -w2 127.0.0.1 6379 2>/dev/null || { echo "Redis 不可达（127.0.0.1:6379）" >&2; exit 1; }
fi

PSQL=/Library/PostgreSQL/18/bin/psql
if [ -x "${PSQL}" ]; then
  DB_URL=$(sed -n 's/^DATABASE_URL=//p' .env)
  PGUSER_VAL=$(echo "${DB_URL}" | sed -E 's#postgresql://([^:]+):.*#\1#')
  PGPASSWORD_VAL=$(echo "${DB_URL}" | sed -E 's#postgresql://[^:]+:([^@]+)@.*#\1#')
  PGDB_VAL=$(echo "${DB_URL}" | sed -E 's#.*/([^/?]+)(\?.*)?$#\1#')
  PGPASSWORD="${PGPASSWORD_VAL}" "${PSQL}" -h 127.0.0.1 -U "${PGUSER_VAL}" -d "${PGDB_VAL}" -tAc "select 1;" >/dev/null 2>&1 \
    || { echo "PostgreSQL 不可达或账号/库无效（${PGUSER_VAL}@127.0.0.1:5432/${PGDB_VAL}）" >&2; exit 1; }
else
  nc -z -w2 127.0.0.1 5432 2>/dev/null || { echo "PostgreSQL 不可达（127.0.0.1:5432）" >&2; exit 1; }
fi

docker compose up -d

# 首次启动 web 要跑数据库迁移，耗时较长，最多等 3 分钟
printf '等待 Langfuse 就绪（首次启动含数据库迁移，较慢）'
ready=0
for _ in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:3000 2>/dev/null || echo 000)
  if [ "${code}" != "000" ] && [ "${code}" != "502" ]; then
    ready=1
    break
  fi
  printf '.'
  sleep 2
done
printf '\n'

if [ "${ready}" != "1" ]; then
  echo "未就绪，排查: docker logs langfuse-web ; docker logs langfuse-worker" >&2
  exit 1
fi

HOST_IP=$(ipconfig getifaddr en0 2>/dev/null || echo 127.0.0.1)

echo
echo "状态:   运行中"
echo "Web UI: http://${HOST_IP}:3000"
echo "存储:   Postgres=本地 h_agent_obs | Redis=本地 | ClickHouse=容器 | S3=MinIO(桶: $(sed -n 's/^LANGFUSE_S3_BUCKET=//p' .env))"
echo "说明:   浏览器首次打开注册的账号即管理员"
