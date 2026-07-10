#!/usr/bin/env bash
#
# 가상 대기열 부하 테스트 실행기 — 별도 loadgen 서버(EC2 Ubuntu/Amazon Linux)에서 실행한다.
# 앱 서버에는 이 레포를 배포만 하고(WAITING_ROOM_TEST_HOLD_MS 는 코드 기본 2000 으로 켜져 있음),
# k6 는 여기 loadgen 서버에서 ALB(BASE) 를 두드린다.
#
# 하는 일: 1) k6 없으면 설치  2) 파일 디스크립터 상향  3) k6 실행 + 요약 JSON 저장
#
# 사용 예:
#   BASE=http://hf4-alb-....elb.amazonaws.com VUS=500 ./run-loadtest.sh
#
# 조절 가능한 환경변수(기본값):
#   BASE  대상 ALB URL      (미지정 시 스크립트/JS 안 기본 ALB 사용 — 되도록 명시)
#   VUS   동시 접속 목표     (500)
#   RAMP  급증 구간          (15s)   ← 짧을수록 '급격한' 스파이크
#   HOLD  최대 부하 유지      (120s)
#   DOWN  감소 구간          (20s)
#
set -euo pipefail
cd "$(dirname "$0")"

SCRIPT="k6-waiting-room-spike.js"
OUT="result-$(date +%Y%m%d-%H%M%S).json"

# ── 1) k6 설치 확인/설치 ────────────────────────────────────────────────
if ! command -v k6 >/dev/null 2>&1; then
  echo "[setup] k6 미설치 → 설치 시도"
  if command -v apt-get >/dev/null 2>&1; then                 # Ubuntu/Debian
    sudo apt-get update -y
    sudo apt-get install -y gnupg ca-certificates curl
    curl -fsSL https://dl.k6.io/key.gpg | sudo gpg --dearmor -o /usr/share/keyrings/k6-archive-keyring.gpg
    echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
      | sudo tee /etc/apt/sources.list.d/k6.list >/dev/null
    sudo apt-get update -y && sudo apt-get install -y k6
  elif command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1; then   # Amazon Linux/RHEL
    VER="v0.49.0"
    case "$(uname -m)" in x86_64) K=amd64 ;; aarch64) K=arm64 ;; *) K=amd64 ;; esac
    curl -fsSL "https://github.com/grafana/k6/releases/download/${VER}/k6-${VER}-linux-${K}.tar.gz" -o /tmp/k6.tgz
    tar -xzf /tmp/k6.tgz -C /tmp
    sudo mv "/tmp/k6-${VER}-linux-${K}/k6" /usr/local/bin/k6
  else
    echo "[setup] 패키지 매니저를 못 찾음 — https://k6.io/docs/get-started/installation/ 참고해 수동 설치" >&2
    exit 1
  fi
fi
echo "[setup] $(k6 version)"

# ── 2) 파일 디스크립터 상향 (수백~수천 동시연결이면 필요) ──────────────────
ulimit -n 1048576 2>/dev/null || ulimit -n 65535 2>/dev/null || true
echo "[setup] ulimit -n = $(ulimit -n)"

# ── 3) 실행 ────────────────────────────────────────────────────────────
echo "[run] BASE=${BASE:-<JS default ALB>} VUS=${VUS:-500} RAMP=${RAMP:-15s} HOLD=${HOLD:-120s} DOWN=${DOWN:-20s}"
k6 run \
  ${BASE:+-e BASE=$BASE} \
  ${VUS:+-e VUS=$VUS} \
  ${RAMP:+-e RAMP=$RAMP} \
  ${HOLD:+-e HOLD=$HOLD} \
  ${DOWN:+-e DOWN=$DOWN} \
  --summary-export "$OUT" \
  "$SCRIPT"

echo "[done] 요약 저장 → $(pwd)/$OUT"
echo "[check] wr_queued / wr_admitted_after_wait 가 0 보다 크면 대기열이 실제로 동작한 것"
