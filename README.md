# H-Insight-AI
홈쇼핑 판매 분석 및 방송 전략 추천 플랫폼

---

## 모니터링 (Prometheus + Grafana) — 부하테스트 baseline

성능 baseline 측정 및 개선 전/후 비교용 모니터링 구성.
**이 프로젝트(대상 서버)에는 앱 메트릭 노출 + exporter 만** 두고,
**Prometheus·Grafana 는 별도 "모니터링 EC2"**([`monitoring/`](monitoring/) 폴더)에서 스크레이프한다.

```
[대상 서버]  Spring 앱(:8080 actuator) + exporter 들   ──스크레이프──▶  [모니터링 EC2] Prometheus(:9090) ─▶ Grafana(:3000)
```

### 1. 대상 서버에서 노출되는 것

| 대상 | 노출 포트 | 경로 | 비고 |
|------|-----------|------|------|
| Spring Boot 앱 (actuator) | **8080** | `/actuator/prometheus` | 호스트에서 `./gradlew bootRun` 실행 (컨테이너 아님) |
| node-exporter | **9100** | `/metrics` | 호스트 CPU/메모리/디스크/네트워크 |
| cadvisor | **8085** | `/metrics` | 컨테이너별 리소스 (컨테이너 내부는 8080, 앱과 충돌 피해 호스트 8085 매핑) |
| redis-exporter | **9121** | `/metrics` | Redis 지표 |
| mysqld-exporter | **9104** | `/metrics` | 외부 MySQL 지표 — `.env` 의 `MYSQLD_EXPORTER_DSN` 설정 필요 |
| kafka-exporter | **9308** | `/metrics` | Kafka 지표 — `--profile local` 로 띄웠을 때만 동작 |

> **운영(EC2) 보안**: 위 포트들은 외부 스크레이프용으로 호스트에 열려 있다.
> EC2 에서는 **보안그룹으로 "모니터링 EC2 의 IP" 에서 오는 트래픽만** 각 포트에 허용할 것.
> `/actuator/**` 는 인증 없이 열려 있으므로(시큐리티 체인 미적용 경로) 외부 노출 금지.

### 2. 대상 서버 실행

```bash
# (a) exporter 들 기동 — 앱은 호스트에서 별도 실행
docker compose up -d node-exporter cadvisor redis-exporter mysqld-exporter
#   Kafka 도 함께 볼 때:
docker compose --profile local up -d          # kafka + kafka-exporter 포함

# (b) 앱 실행 (actuator 노출)
./gradlew bootRun

# (c) mysqld-exporter 를 실제로 쓰려면 .env 에 아래 추가(모니터 계정은 나중에 발급 가능)
#   MYSQLD_EXPORTER_DSN=모니터유저:비번@(DB호스트:3306)/
#   (계정 권한: GRANT PROCESS, REPLICATION CLIENT, SELECT)
```

### 3. 검증

1. **앱 메트릭**: <http://localhost:8080/actuator/prometheus> 접속 →
   `http_server_requests_seconds_*`, `jvm_memory_used_bytes`, `hikaricp_connections_active`,
   `jvm_gc_*`, `executor_*` 등이 보이면 OK.
   ```bash
   curl -s localhost:8080/actuator/prometheus | grep -E "http_server_requests_seconds_bucket|hikaricp_connections_active" | head
   ```
2. **exporter 살아있는지**:
   ```bash
   curl -s localhost:9100/metrics | head -1   # node-exporter
   curl -s localhost:8085/metrics | head -1   # cadvisor
   curl -s localhost:9121/metrics | head -1   # redis-exporter
   ```
3. **Prometheus targets / Grafana** 는 아래 모니터링 스택 참고.

### 4. 모니터링 스택 (Prometheus + Grafana)

별도 EC2(또는 로컬 테스트)에서 실행. 자세한 실행/검증은 [`monitoring/README.md`](monitoring/README.md) 참고. 요약:

```bash
# 1) 스크레이프 대상 IP 지정: prometheus.yml 의 <TARGET_EC2_PRIVATE_IP> 를 대상 서버 IP 로 치환
#    (로컬 한 대 테스트면 host.docker.internal 로)
# 2) (선택) Grafana 비번:  cp monitoring/.env.example monitoring/.env  후 값 수정
docker compose -f monitoring/docker-compose.yml up -d
```

- **Prometheus**: <http://localhost:9090> → *Status → Targets* 에서 전부 **UP** 확인
- **Grafana**: <http://localhost:3000> (admin / `GRAFANA_ADMIN_PASSWORD`, 기본 `admin`)
  - *Dashboards* 에 자동 프로비저닝됨: **Load Test Baseline (H-Insight-AI)**(커스텀),
    JVM (Micrometer), Node Exporter Full, Cadvisor exporter, Spring Boot 3.x Statistics
