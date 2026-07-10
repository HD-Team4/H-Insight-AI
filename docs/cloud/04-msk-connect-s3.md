# Step 4 — MSK Connect S3 Sink (KAN-68)

로컬 `activity-s3-sink.json`을 MSK Connect로 이식. 바뀌는 건 **엔드포인트(MinIO→S3)와 인증(키→IAM 역할)** 뿐.

## 4-A. 커스텀 플러그인 등록
1. 로컬에서 쓴 **Aiven S3 Sink 커넥터 zip**(`docker/connect/Dockerfile`이 받던 그 tar/zip)을 준비
   - 없으면: github.com/Aiven-Open/s3-connector-for-apache-kafka 릴리스에서 `.zip` 다운로드
2. **S3에 업로드** (예: `s3://hf4-datalake/plugins/aiven-s3-sink.zip`)
3. MSK 콘솔 → **MSK Connect → 사용자 지정 플러그인 → 플러그인 생성** → 위 S3 경로 지정

## 4-B. 서비스 실행 역할 (IAM Role)
MSK Connect가 쓸 역할. IAM → 역할 생성:
- **신뢰 관계**: `kafkaconnect.amazonaws.com`
- **연결 정책**:
  - `hf4-datalake-s3-write` (Step 1에서 만든 것 — S3 쓰기)
  - MSK 읽기용 인라인 정책 (아래)
```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow",
      "Action": ["kafka-cluster:Connect","kafka-cluster:DescribeCluster","kafka-cluster:DescribeGroup",
                 "kafka-cluster:AlterGroup","kafka-cluster:ReadData","kafka-cluster:DescribeTopic"],
      "Resource": "arn:aws:kafka:ap-northeast-2:601202752151:*/hf4-msk/*" }
  ]
}
```

## 4-C. 커넥터 생성
MSK Connect → **커넥터 생성** → 클러스터 `hf4-msk`(IAM 인증) → 플러그인 선택 → 아래 설정 붙여넣기:
```json
{
  "connector.class": "io.aiven.kafka.connect.s3.AivenKafkaConnectS3SinkConnector",
  "tasks.max": "1",
  "topics.regex": "activity\\..*",
  "aws.s3.bucket.name": "hf4-datalake",
  "aws.s3.region": "ap-northeast-2",
  "format.output.type": "jsonl",
  "file.compression.type": "gzip",
  "file.name.template": "raw/{{topic}}/year={{timestamp:unit=yyyy}}/month={{timestamp:unit=MM}}/day={{timestamp:unit=dd}}/{{topic}}-{{partition}}-{{start_offset}}.gz",
  "file.name.timestamp.timezone": "Asia/Seoul",
  "file.name.timestamp.source": "wallclock",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter.schemas.enable": "false"
}
```
- **로컬과 차이**: `aws.s3.endpoint`(MinIO)·access key **제거** → 실행 역할(IAM)로 S3 접근. `region` 추가.
- 나머지(topics.regex, 파티셔닝, gzip jsonl)는 **로컬과 동일** → 검증된 설정 재사용.
- 네트워킹: 클러스터와 **같은 VPC/서브넷**, 보안 그룹 `sg-075ff3b098bffa7e9`
- 워커 수/MCU: **최소(1)** — 비용 절약

## 4-D. 검증
발행 → `s3://hf4-datalake/raw/activity.purchase/year=/month=/day=/*.gz` 생성 확인 (로컬 MinIO 때와 동일 구조).

## 💸 삭제
테스트 끝나면 **커넥터 먼저 삭제** (MCU 과금 중단) → 그다음 MSK 클러스터.
