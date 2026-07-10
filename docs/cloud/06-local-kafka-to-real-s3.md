# 로컬 Kafka → 진짜 S3 데이터레이크 (MSK 대안)

MSK는 **상시 과금 + VPC/퍼블릭서브넷 제약**이 커서, 데모/포트폴리오엔 과했다.
대신 **이미 있는 로컬 Kafka + Kafka Connect를 그대로 쓰고, sink 목적지만 진짜 S3로** 바꾼다.

```
앱 → 로컬 Kafka → 로컬 Kafka Connect ──(S3 sink)──> 진짜 AWS S3 (hf4-datalake)
     (무료·기존)                                        ↓
                                        Athena / Lambda 집계 (클라우드)
```

- **바뀐 것은 sink 설정뿐** — MinIO 엔드포인트/키 → 진짜 S3(리전+IAM 키). RDS 때 ".env 한 줄"과 같은 엔드포인트 스왑.
- **비용**: S3 저장분(거의 0) + 앱 발행 트래픽뿐. MSK 상시요금 없음.
- Kafka/Connect만 로컬이고 **데이터레이크·집계는 진짜 클라우드**.

## 사전 준비 — AWS 액세스 키
로컬 Connect는 EC2가 아니라 인스턴스 역할이 없다 → IAM 사용자 액세스 키로 S3 인증.
1. IAM 사용자 생성 + `hf4-datalake-s3-write` 정책 연결 → 액세스 키 발급
2. `.env`에 (gitignore됨):
   ```
   AWS_ACCESS_KEY_ID=...
   AWS_SECRET_ACCESS_KEY=...
   AWS_REGION=ap-northeast-2
   ```

## 커넥터 등록
설정: [docker/connect/activity-s3-aws.json](../../docker/connect/activity-s3-aws.json) (키는 `.env`에서 주입).
```bash
# .env의 AWS 키를 주입해 등록 (시크릿을 파일에 남기지 않음)
python3 - <<'PY'
import json
def g(k):
    for line in open(".env"):
        if line.startswith(k+"="): return line.split("=",1)[1].strip()
cfg=json.load(open("docker/connect/activity-s3-aws.json"))["config"]
cfg["aws.access.key.id"]=g("AWS_ACCESS_KEY_ID"); cfg["aws.secret.access.key"]=g("AWS_SECRET_ACCESS_KEY")
open("/tmp/c.json","w").write(json.dumps({"name":"activity-s3-datalake","config":cfg}))
PY
curl -X POST http://localhost:8083/connectors -H 'Content-Type: application/json' -d @/tmp/c.json; rm /tmp/c.json
```

## 핵심 설정 gotcha (겪은 것)
| 설정 | 이유 |
|---|---|
| `aws.s3.endpoint` **제거** | 있으면 MinIO로 감. 없어야 진짜 AWS S3 |
| `aws.s3.region=ap-northeast-2` | 버킷 리전과 일치 (MinIO는 아무거나 됐음) |
| **`format.output.envelope=false`** + `format.output.fields=value` | 없으면 `{"value":{...}}` 로 한 겹 감싸져서 집계가 `payload.productId` 를 못 찾음 |
| `consumer.override.auto.offset.reset=earliest` | 토픽에 이미 쌓인 이벤트까지 처음부터 재적재 |
| `file.max.records=1` | 데모용(이벤트 즉시 적재·확인). **운영은 100+로 상향** (작은 파일 폭증 방지) |

## 적재 형식 (검증됨)
`raw/activity.purchase/year=2026/month=07/day=03/*.gz`, 한 줄이 하나의 ActivityEvent:
```json
{"type":"purchase","userId":1002,"occurredAt":"2026-07-03T03:01:43Z","payload":{"productId":4,"quantity":1,"price":49900}}
```
→ `activity.search`/`activity.click` 도 `topics.regex: activity\..*` 로 자동 적재.
→ 이 형식이 [Lambda 집계](../../lambda/aggregate_mart/handler.py)·[Athena DDL](athena/ddl.sql)이 기대하는 그 형식.

## 다음
S3 raw가 쌓이므로 → dims(products/categories) S3 업로드 → Lambda 집계 → `mart/dashboard/latest.json` → 대시보드(KAN-81) 연결.
