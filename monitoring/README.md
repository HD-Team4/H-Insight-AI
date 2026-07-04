# 모니터링 스택 (Prometheus + Grafana)

부하테스트 baseline 수집용. **별도 "모니터링 EC2"** 에서 실행하며, 대상 서버
(H-Insight-AI 앱 + exporter)를 스크레이프한다. 대상 서버쪽 노출 포트는 프로젝트
루트 [`README.md`](../README.md) 의 "모니터링" 표 참고.

```
monitoring/
├─ docker-compose.yml          # Prometheus + Grafana (데이터는 named volume 에 영속화)
├─ .env.example                # GRAFANA_ADMIN_PASSWORD
├─ prometheus/
│  └─ prometheus.yml           # 스크레이프 대상 — <TARGET_EC2_PRIVATE_IP> 치환 필요
└─ grafana/provisioning/
   ├─ datasources/datasource.yml   # Prometheus 자동 연결 (uid: prometheus)
   └─ dashboards/
      ├─ dashboards.yml             # json/ 자동 로드 프로바이더
      └─ json/
         ├─ loadtest-baseline.json  # ★ 커스텀: RPS·p95/p99·에러율·CPU·메모리·DB커넥션 한 화면
         ├─ jvm-micrometer.json     # 4701
         ├─ node-exporter-full.json # 1860
         ├─ cadvisor.json           # 14282
         └─ spring-boot.json        # 19004 (Spring Boot 3.x)
```

## 실행

```bash
# 1) 스크레이프 대상 IP 지정
#    prometheus/prometheus.yml 의 <TARGET_EC2_PRIVATE_IP> 를 대상 서버 프라이빗 IP 로 전부 치환.
#    (로컬 한 대에서 테스트: Docker Desktop 이면 host.docker.internal, Linux 면 호스트 LAN IP)

# 2) (선택) Grafana 관리자 비밀번호
cp .env.example .env      # 그리고 GRAFANA_ADMIN_PASSWORD 수정

# 3) 기동  (monitoring/ 안에서)
docker compose up -d
#   또는 프로젝트 루트에서:  docker compose -f monitoring/docker-compose.yml up -d
```

프라이빗 IP 를 바꾼 뒤 재적용은 컨테이너 재시작 없이:
```bash
docker exec monitoring-prometheus kill -HUP 1        # 또는
curl -X POST http://localhost:9090/-/reload          # (--web.enable-lifecycle 활성화되어 있음)
```

## 검증

1. **Prometheus targets 전부 UP**
   - <http://localhost:9090> → **Status → Targets**
   - `spring-actuator`, `node-exporter`, `cadvisor`, `redis-exporter` 는 UP 이어야 정상.
   - `mysqld-exporter` 는 대상 서버 `.env` 에 `MYSQLD_EXPORTER_DSN` 을 넣어야 UP.
   - `kafka-exporter` 는 대상 서버를 `--profile local` 로 띄웠을 때만 UP.
   - 빠른 확인: <http://localhost:9090/api/v1/targets> 응답의 `"health":"up"` 개수.

2. **Grafana 접속 & 대시보드**
   - <http://localhost:3000>  (ID: `admin`, PW: `.env` 의 `GRAFANA_ADMIN_PASSWORD`, 기본 `admin`)
   - 좌측 **Dashboards** 목록에 5개가 자동 프로비저닝되어 있음:
     - **Load Test Baseline (H-Insight-AI)** ← 부하테스트용 커스텀 (한 화면에 핵심 지표)
     - JVM (Micrometer) / Node Exporter Full / Cadvisor exporter / Spring Boot 3.x Statistics
   - 데이터가 안 보이면: 대상 IP 치환 여부, 대상 서버 보안그룹/포트, 앱 `bootRun` 실행 여부 확인.

## 부하 전/후 비교

- Prometheus 는 `prometheus_data` 볼륨에 **30일 보관**(`retention.time=30d`) → 재시작해도 baseline 유지.
- 개선 전 부하 → 스크린샷/기록 → 개선 후 부하 → 같은 대시보드 시간범위로 비교.
- Grafana 대시보드 시간범위를 부하 구간에 맞추고, 커스텀 대시보드의 legend 통계(mean/max)로 수치 비교.

## 로컬 한 대에서 전체 테스트 (선택)

대상 서버 = 내 맥, 모니터링도 같은 맥에서 볼 때:
1. 대상측 exporter 기동: `docker compose up -d node-exporter cadvisor redis-exporter` (루트에서)
2. 앱 기동: `./gradlew bootRun`
3. `prometheus/prometheus.yml` 의 `<TARGET_EC2_PRIVATE_IP>` → `host.docker.internal` 로 치환
4. `docker compose -f monitoring/docker-compose.yml up -d`
5. <http://localhost:9090/targets> UP 확인 → <http://localhost:3000> 대시보드 확인

> 참고: Docker Desktop(macOS)에서는 node-exporter/cadvisor 가 실제 맥이 아니라 Docker VM 을
> 관찰한다. 실제 호스트 지표는 대상이 리눅스 EC2 일 때 정확하다. 앱 자체 CPU/메모리는
> actuator 의 `process_cpu_usage` / `jvm_memory_used_bytes` 로 보는 게 정확하다.
