# Step 7 — 클라우드 집계: Athena + Lambda + EventBridge (KAN-75/76)

집계를 **클라우드에서 자동 실행**한다 (로컬 백필이 아니라 정식 집계). 컨테이너/ECR 없이 **콘솔로 배포**.

```
[EventBridge 스케줄] → [Lambda(Python)] → Athena로 S3 raw 집계 → mart JSON → S3
                                            ↑ dims/*.csv 조인(파이썬)
```

## 선행 (완료됨)
- ✅ S3 raw 데이터 적재 중 (activity.purchase/search/click)
- ✅ `s3://hf4-datalake/dims/products.csv`, `categories.csv` 업로드
- 코드: `lambda/aggregate_mart_athena/handler.py` (순수 파이썬, boto3만 — Lambda 런타임 내장)

## 1. Athena 설정 + 테이블 생성
1. **Athena 콘솔** → (첫 사용 시) **쿼리 결과 위치** 설정 → `s3://hf4-datalake/athena-results/`
2. 쿼리 편집기에 [athena/ddl.sql](athena/ddl.sql) 붙여넣고 실행 → `hf4_datalake` DB + 3개 테이블 생성
3. 검증 쿼리:
   ```sql
   SELECT count(*) FROM hf4_datalake.activity_purchase;
   SELECT payload.keyword, count(*) FROM hf4_datalake.activity_search GROUP BY 1 ORDER BY 2 DESC LIMIT 5;
   ```

## 2. Lambda 실행 역할 (IAM)
IAM → 역할 생성 → 신뢰: `lambda.amazonaws.com` → 아래 정책 연결:
- `AWSLambdaBasicExecutionRole` (로그)
- `hf4-datalake-s3-write` (S3 읽기/쓰기 — 이미 있음)
- 인라인 정책 (Athena + Glue):
```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow",
      "Action": ["athena:StartQueryExecution","athena:GetQueryExecution","athena:GetQueryResults","athena:StopQueryExecution"],
      "Resource": "*" },
    { "Effect": "Allow",
      "Action": ["glue:GetDatabase","glue:GetTable","glue:GetPartitions","glue:GetTables"],
      "Resource": "*" }
  ]
}
```

## 3. Lambda 함수 생성 (콘솔)
- **함수 생성 → 새로 작성** → 이름 `hf4-aggregate-mart` → 런타임 **Python 3.12**
- 실행 역할: 2번 역할
- **코드**: `lambda/aggregate_mart_athena/handler.py` 전체를 편집기에 붙여넣기 → **Deploy**
  - 핸들러 이름: `lambda_function.lambda_handler` (파일명이 lambda_function.py면) 또는 파일명을 맞춤
- **구성 → 일반 구성**: 타임아웃 **5분**, 메모리 **512MB**

## 4. 테스트
- **테스트** → 빈 이벤트 `{}` → 실행
- 성공 시 `{"statusCode":200,"anchor":"..."}` + 로그에 요약
- `s3://hf4-datalake/mart/dashboard/latest.json` 갱신 확인

## 5. EventBridge 스케줄
- **EventBridge → 규칙 생성 → 일정** → `rate(1 hour)` (또는 원하는 주기)
- 대상: `hf4-aggregate-mart` Lambda
- → 매시각 자동 집계 → mart 갱신

## 비용
- Athena: 스캔한 데이터량 과금 (gzip이라 적음, 쿼리당 수 원)
- Lambda: 실행 시에만 / EventBridge: 무료급
- **상시 과금 없음** (MSK와 다름) → 삭제 급하지 않음

## 참고 — 두 버전
| 버전 | 위치 | 배포 |
|---|---|---|
| **Athena (권장)** | `lambda/aggregate_mart_athena/` | 콘솔 붙여넣기 (ECR 불필요) |
| DuckDB 컨테이너 | `lambda/aggregate_mart/` | ECR 필요 (대안) |
