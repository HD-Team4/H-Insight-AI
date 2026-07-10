# Kafka Connect (S3 Sink)

`quay.io/debezium/connect` 이미지를 **Kafka Connect 런타임**으로만 사용하고, 여기에
**Aiven S3 Sink 커넥터**(Apache-2.0)를 얹은 커스텀 이미지다. (`Dockerfile`)

> CDC(Debezium 소스 커넥터)는 쓰지 않는다. 앱이 행동 로그를 **`activity.<type>` 토픽으로 직접 produce**하고
> ([ActivityEventPublisher](../../src/main/java/com/hinsight/common/event/ActivityEventPublisher.java)),
> S3 Sink 하나가 `activity.*` 토픽을 모두 읽어 타입별 폴더로 S3(로컬은 MinIO)에 적재한다.

```
앱  --produce-->  Kafka 토픽 "activity.purchase" / "activity.search" / ...  --S3 Sink-->  S3 / MinIO(hf4-datalake)
```

## 빌드 & 기동 (로컬)

Kafka·MinIO·Connect는 `local` 프로파일에 있다.

```bash
docker compose --profile local up -d --build
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

`topics.regex: "activity\\..*"` 로 **모든 `activity.*` 토픽**을 잡아, MinIO `hf4-datalake` 버킷에
**토픽별 + 날짜 파티션**으로 적재한다:
```
raw/<topic>/year=YYYY/month=MM/day=dd/<topic>-<partition>-<offset>.gz
예) raw/activity.purchase/year=2026/month=07/day=02/...
```

> 새 이벤트 타입을 추가할 때 sink 를 수정할 필요가 없다. 앱에서 `activity.<newtype>` 토픽으로
> produce 하면 이 커넥터가 자동으로 포함한다.

## 확인 (MinIO)

- 웹 콘솔: http://localhost:9001 (minioadmin / minioadmin) — 버킷 `hf4-datalake` 는 `minio-setup` 이 자동 생성
- CLI:
  ```bash
  docker compose run --rm --entrypoint sh minio-setup -c \
    "mc alias set local http://minio:9000 minioadmin minioadmin && mc ls -r local/hf4-datalake"
  ```

## 설정 메모

- `format.output.type=jsonl` + `file.compression.type=gzip` — Athena/집계가 읽기 좋은 형태
- `file.max.records=100` — 파일당 최대 레코드 (오프셋 커밋 주기(기본 60초)에도 flush됨)
- `file.name.template` — `{{topic}}` + `year=/month=/day=` 파티셔닝 (Athena 파티션 프루닝용)
- MinIO 접속정보(minioadmin)는 로컬 전용 더미. **실제 S3 전환 시** `aws.s3.endpoint` 제거 +
  `aws.s3.region` 실제값 + IAM 자격증명으로 교체.
