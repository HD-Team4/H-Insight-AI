# 매출 집계 마트 (KAN-63)

S3 데이터레이크 **raw 존**(구매 이벤트 gz JSONL)을 **집계 테이블**로 적재하고,
대시보드 JSON은 raw가 아니라 **집계 테이블에서 파생**한다 (2단계 구조).

```
raw/purchase-history/year=/month=/day=/*.gz        (원본, gz JSONL)
   │ 1단계: 정제 + 집계
   ├─> mart/sales_daily/year=/month=/day=/*.parquet   (일별×상품별)
   └─> mart/sales_monthly/year=/*.parquet             (월별×상품별 롤업)
          │ 2단계: 기간 슬라이스 + 차원 조인
          └─> mart/dashboard/latest.json               (대시보드 fetch, JSON 계약)
```

- **집계 테이블 = Parquet** (분석·Athena 표준), **서빙 = JSON** (화면 계약)
- 대시보드 외 소비자(BI·분석)는 `sales_daily/monthly`를 직접 쿼리 가능
- 로컬 검증은 **DuckDB**, 클라우드는 동일 SQL을 **Athena + Lambda**로 이식 (KAN-75/76)
- 로컬 MinIO ↔ 클라우드 S3는 **엔드포인트/자격증명만 교체** (s3:// 프로토콜 동일)
- 파생 무결성 검증: 집계 테이블 경유 KPI = raw 직접 집계 KPI 일치 확인

## 파일

| 파일 | 용도 | 티켓 |
|---|---|---|
| `agg_validation.sql` | 정제 규칙 + 집계 5종 검증 쿼리 | KAN-73 |
| `build_mart_json.py` | 1단계 집계 테이블(Parquet) 적재 + 2단계 대시보드 JSON 파생 | KAN-74 |

`--skip-tables` 옵션으로 2단계만 재실행 가능 (집계 테이블 재사용).

## 정제 규칙 (프로파일링으로 확정)

| 규칙 | 이유 |
|---|---|
| `history_id` 중복 제거 | 브로커/커넥터 at-least-once 재전송 (실측 39건) |
| 삭제 이벤트 id 통째 제외 | CDC 삭제 의미론 — insert 행도 함께 제거 |
| 수량 1~100 컷 | 테스트 오염 차단 (수량 56,456,449 주문 1건이 매출을 3.4조로 왜곡) |

## 실행

```bash
# 1) 차원 스냅샷 (RDS → TSV, 한글은 utf8mb4 필수)
mysql -h <RDS호스트> -u admin -p --batch --default-character-set=utf8mb4 \
  -e "SELECT product_id, category_id, product_name, price FROM hf4_db.products;" > dim_products.tsv
mysql -h <RDS호스트> -u admin -p --batch --default-character-set=utf8mb4 \
  -e "SELECT category_id, parent_category_id, category_name FROM hf4_db.categories;" > dim_categories.tsv

# 2) mart JSON 생성 (로컬 MinIO 기준. 클라우드는 --endpoint 교체)
python3 build_mart_json.py --products dim_products.tsv --categories dim_categories.tsv --out latest.json

# 3) mart 존 업로드 (로컬 MinIO)
docker run --rm --network h-insight-ai_default -v "$PWD:/work" --entrypoint sh minio/mc -c \
  "mc alias set m http://minio:9000 minioadmin minioadmin && \
   mc cp /work/latest.json m/hf4-datalake/mart/dashboard/latest.json"
```

## 출력 형식 (대시보드 JSON 계약)

루트는 기간 키 `{ "1w", "1m", "6m", "1y" }`, 각 값:

```json
{
  "generatedAt": "2026-07-03T00:49:00+00:00",
  "period": "1개월",
  "kpi": { "totalSales": 166492100, "orderCount": 3131, "totalQuantity": 3775, "visitors": 0 },
  "dailySales": [ { "date": "1주차", "sales": 31905500, "orders": 602 } ],
  "categorySales": [ { "category": "니트", "sales": 21952800 } ],
  "topProducts": [ { "productName": "…", "category": "…", "unitsSold": 80, "revenue": 8789000, "growth": 69 } ],
  "searchRanking": []
}
```

- `dailySales.date` 버킷: 1w=일자(`MM-DD`), 1m=주차(`N주차`), 6m/1y=월(`YYYY-MM`)
- `topProducts.growth`: 직전 동일 길이 구간 대비 매출 성장률(%). 직전 매출 0이면 `null`(신규)
- `visitors`·`searchRanking`: 조회/검색 로그(KAN-71/72) 적재 후 집계 예정 — 현재 0/`[]`
