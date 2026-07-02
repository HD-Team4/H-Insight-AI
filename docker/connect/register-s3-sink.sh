#!/usr/bin/env bash
# 행동 로그(activity.*) 토픽 → S3(MinIO) 적재 Sink 커넥터 등록
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"

# 이미 있으면 삭제 후 재등록 (설정 변경 반영)
curl -s -X DELETE "$CONNECT_URL/connectors/activity-s3-sink" >/dev/null 2>&1 || true
sleep 1
curl -s -X POST "$CONNECT_URL/connectors" \
  -H "Content-Type: application/json" \
  --data-binary "@$SCRIPT_DIR/activity-s3-sink.json" \
  -w "\nHTTP %{http_code}\n"
