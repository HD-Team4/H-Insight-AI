#!/usr/bin/env bash
# 구매이력(purchase_history) CDC 커넥터를 Kafka Connect에 등록한다.
# 비밀번호는 커밋하지 않고, 프로젝트 루트 .env 의 DB_PASSWORD 를 주입한다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"

DB_PASSWORD="$(grep '^DB_PASSWORD=' "$ROOT_DIR/.env" | cut -d= -f2-)"
if [ -z "$DB_PASSWORD" ]; then
  echo "ERROR: .env 에서 DB_PASSWORD 를 찾지 못했습니다." >&2
  exit 1
fi

config="$(sed "s|__DB_PASSWORD__|$DB_PASSWORD|" "$SCRIPT_DIR/purchase-history-connector.json")"

# 이미 등록돼 있으면 삭제 후 재등록 (설정 변경 반영용)
curl -s -X DELETE "$CONNECT_URL/connectors/purchase-history-connector" >/dev/null 2>&1 || true
sleep 1
curl -s -X POST "$CONNECT_URL/connectors" \
  -H "Content-Type: application/json" \
  -d "$config" \
  -w "\nHTTP %{http_code}\n"
