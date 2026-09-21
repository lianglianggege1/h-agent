#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

DEST="${1:-./backup}"
mkdir -p "${DEST}"
DEST="$(cd "${DEST}" && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"

docker volume inspect minio_minio-data >/dev/null 2>&1 || { echo "数据卷不存在，请先运行 ./start.sh"; exit 1; }

docker run --rm \
  -v minio_minio-data:/data:ro \
  -v "${DEST}:/backup" \
  alpine:3.20 \
  tar czf "/backup/minio-${STAMP}.tar.gz" -C /data .

echo "已备份: ${DEST}/minio-${STAMP}.tar.gz"
