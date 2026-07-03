# Step 5 — 집계 Lambda + EventBridge (KAN-75, KAN-76)

S3 raw(행동 이벤트)를 주기적으로 집계해 **대시보드 mart JSON**을 생성한다.
로컬에서 검증한 DuckDB 집계(`docs/aggregation/build_mart_json.py`)를 Lambda로 이식 —
같은 엔진·같은 계약, 입출력만 S3.

```
[EventBridge 스케줄] → [Lambda(DuckDB)] → s3://hf4-datalake/mart/dashboard/latest.json
                              ↑ 읽기: raw/activity.purchase|search + dims/products|categories.csv
```

- **비용**: Lambda는 실행 시에만, EventBridge 규칙은 사실상 무료 → 미리 만들어둬도 부담 없음.
- MSK와 **독립적** — raw 데이터만 있으면 동작. (MSK 파이프라인이 raw를 채우거나, 로컬 데이터를 시드)

## 사전 준비 — 차원(dim) 스냅샷을 S3로
Lambda는 상품/카테고리를 S3에서 읽는다 (운영 DB 직접 조회 회피 = OLTP 부하 분리).
RDS → CSV → S3 업로드 (주기적/수동):
```bash
mysql -h <RDS> -u admin -p --batch --default-character-set=utf8mb4 \
  -e "SELECT product_id, category_id, product_name, price FROM hf4_db.products" \
  | tr '\t' ',' > products.csv   # (간이. 상품명에 쉼표 있으면 정식 CSV 이스케이프 필요)
# → aws s3 cp products.csv s3://hf4-datalake/dims/products.csv
# → categories.csv 동일
```
> 정석은 CSV 이스케이프/따옴표 처리. 상품명에 쉼표가 있으므로 `--batch` 대신 SELECT ... INTO OUTFILE 또는 Python csv 모듈 권장.

## 1. 이미지 빌드 → ECR 푸시
```bash
cd lambda/aggregate_mart
ACCOUNT=601202752151; REGION=ap-northeast-2; REPO=hf4-aggregate-mart

aws ecr create-repository --repository-name $REPO --region $REGION
aws ecr get-login-password --region $REGION | docker login --username AWS \
  --password-stdin $ACCOUNT.dkr.ecr.$REGION.amazonaws.com

docker build --platform linux/amd64 -t $REPO .
docker tag $REPO:latest $ACCOUNT.dkr.ecr.$REGION.amazonaws.com/$REPO:latest
docker push $ACCOUNT.dkr.ecr.$REGION.amazonaws.com/$REPO:latest
```

## 2. Lambda 실행 역할 (IAM)
- 신뢰: `lambda.amazonaws.com`
- 정책:
  - `hf4-datalake-s3-write` (Step 1에서 만든 것 — mart 쓰기 + raw/dims 읽기 포함)
  - `AWSLambdaBasicExecutionRole` (CloudWatch 로그)

## 3. Lambda 생성 (콘솔)
- **함수 생성 → 컨테이너 이미지** → 위 ECR 이미지 선택
- 실행 역할: 위 2번 역할
- **메모리 1024MB, 타임아웃 2분** (DuckDB 집계 여유)
- 환경변수: `DATALAKE_BUCKET=hf4-datalake` (필요 시 `MART_KEY` 재정의)
- **VPC 설정 불필요** (S3만 접근, RDS 직접 안 씀)

## 4. 테스트
- Lambda 콘솔 → **테스트** (빈 이벤트 `{}`) → 실행
- 성공 시 응답 `{"statusCode":200, "out":"s3://.../mart/dashboard/latest.json"}`
- S3에서 `mart/dashboard/latest.json` 확인 (raw 비어있으면 KPI 0 · 배열 [] 로 정상 생성)

## 5. EventBridge 스케줄
- **EventBridge → 규칙 생성 → 일정** → cron/rate (예: `rate(1 hour)`)
- 대상: 위 Lambda
- → 매시각 mart 갱신 → 대시보드(KAN-81)가 이 JSON을 fetch

## 이벤트 형식 주의 (로컬 CDC와 다름)
| | 로컬 검증본(build_mart_json.py) | 클라우드 Lambda(handler.py) |
|---|---|---|
| 입력 | CDC 평면 (`history_id, product_id, __deleted`) | **ActivityEvent 엔벨로프** (`payload.productId`) |
| 중복 제거 | history_id 기준 | DISTINCT (이벤트 id 없음 → 전체 필드) |
| 삭제 | `__deleted` 제외 | 없음 (append-only) |

→ 앱 프로듀서가 발행하는 실제 형식(`{type,userId,occurredAt,payload}`)에 맞춰 handler.py 작성됨.

## 💸 정리
Lambda·EventBridge는 idle 비용 거의 0 → 삭제 급하지 않음. (과금 주범은 MSK)
