# 구매이력 CDC 파이프라인 (Debezium → Kafka)

운영 DB의 `purchase_history` 변경분을 **Debezium CDC**로 잡아 **Kafka 토픽**에 싣는 로컬 파이프라인. (KAN-49)

```
앱 주문 / INSERT → 팀 DB(MySQL 8.4, purchase_history) → Debezium → Kafka 토픽 hf4.hf4_db.purchase_history
```

## 구성 요소 (`docker-compose.yml`)

| 서비스 | 포트 | 역할 |
|--------|------|------|
| zookeeper | 2181 | 카프카 클러스터 관리 |
| kafka | 9092(외부) / 29092(내부) | 메시지 브로커 |
| connect | 8083 | Debezium 커넥트 (CDC) |
| kafka-ui | 8090 | 토픽/메시지 확인 UI |
| mysql | 3307 | (선택) 격리 테스트용 로컬 DB |

> **Debezium은 3.0 이상 필수.** 팀 DB가 MySQL 8.4라 `SHOW MASTER STATUS`가 제거됨 → 2.x는 스냅샷 실패.

## 사전 준비 (한 번만)

운영 DB에서 **root로** Debezium 접속 계정에 복제 권한 부여:

```sql
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT
  ON *.* TO 'admin'@'%';
FLUSH PRIVILEGES;
```

MySQL binlog 조건: `log_bin=ON`, `binlog_format=ROW`, `binlog_row_image=FULL`.

## 실행

```bash
# 1) 인프라 기동
docker compose up -d

# 2) connect 준비 확인 (MySqlConnector 뜨면 OK)
curl -s http://localhost:8083/connector-plugins | grep -i mysql

# 3) 커넥터 등록 (.env 의 DB_PASSWORD 를 자동 주입)
./docker/debezium/register-connector.sh
```

## 확인

```bash
# 커넥터 상태 (connector/tasks 모두 RUNNING)
curl -s http://localhost:8083/connectors/purchase-history-connector/status | python3 -m json.tool

# 토픽 메시지
docker compose exec kafka /kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:29092 --topic hf4.hf4_db.purchase_history \
  --from-beginning --timeout-ms 8000
```

또는 브라우저 **http://localhost:8090** → Topics → `hf4.hf4_db.purchase_history`.

## 설정 메모

- `decimal.handling.mode=double` — DECIMAL(price)을 숫자로. (기본 `precise`는 base64 바이트라 집계에 부적합)
- `database.connectionTimeZone=Asia/Seoul` — 서버 시간대가 한글 로케일이라 JDBC 파싱 에러 방지.
- `database.ssl.mode=preferred` — 자체 서명 인증서라 검증 생략.
- 커넥터의 `database.password`는 `__DB_PASSWORD__` 플레이스홀더 → 등록 스크립트가 `.env`에서 주입 (커밋 안전).

## 실제 운영 DB로 전환

`purchase-history-connector.json` 의 `database.hostname` / `database.user` 만 실제 운영 DB로 바꾸면 됨.

## 다음 단계

Kafka → S3 적재 (로컬은 MinIO) → Athena/집계 → 대시보드.
