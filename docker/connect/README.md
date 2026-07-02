# Kafka Connect (CDC + S3 Sink)

`quay.io/debezium/connect` 이미지에 **Aiven S3 Sink 커넥터**(Apache-2.0)를 추가한 커스텀 이미지. (`Dockerfile`)

- **Debezium MySQL** (source): 구매이력 CDC → Kafka (설정: `../debezium/`)
- **Aiven S3 Sink**: Kafka 토픽 → S3(로컬은 MinIO) 적재

## 빌드 & 기동

```bash
docker compose build connect
docker compose up -d
```

플러그인 로드 확인:
```bash
curl -s http://localhost:8083/connector-plugins | grep -i s3
# io.aiven.kafka.connect.s3.AivenKafkaConnectS3SinkConnector 가 보이면 OK
```

## S3 Sink 커넥터 등록

```bash
./docker/connect/register-s3-sink.sh
```

`hf4.hf4_db.purchase_history` 토픽 → MinIO `hf4-datalake` 버킷에 **날짜 파티션**으로 적재:
```
raw/purchase-history/year=YYYY/month=MM/day=dd/<topic>-<partition>-<offset>.gz
```

## 확인 (MinIO)

- 웹 콘솔: http://localhost:9001 (minioadmin / minioadmin)
- CLI:
  ```bash
  docker run --rm --network h-insight-ai_default --entrypoint sh minio/mc:latest -c \
    "mc alias set local http://minio:9000 minioadmin minioadmin && mc ls -r local/hf4-datalake"
  ```

## 설정 메모

- `format.output.type=jsonl` + `file.compression.type=gzip` — Athena/집계가 읽기 좋은 형태
- `file.max.records=100` — 파일당 최대 레코드 (오프셋 커밋 주기(기본 60초)에도 flush됨)
- `file.name.template` — `year=/month=/day=` 파티셔닝 (Athena 파티션 프루닝용)
- MinIO 접속정보(minioadmin)는 로컬 전용 더미. **실제 S3 전환 시** `aws.s3.endpoint` 제거 + `aws.s3.region` 실제값 + IAM 자격증명으로 교체.
