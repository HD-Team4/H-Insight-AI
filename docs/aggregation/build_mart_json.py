#!/usr/bin/env python3
"""
KAN-74: S3 raw(구매 이벤트) → 집계 테이블(mart) → Biz 대시보드 JSON 생성기.

2단계 구조 — 대시보드는 raw가 아니라 집계 테이블에서 파생된다:
  1단계  raw/purchase-history/*.gz → mart/sales_daily/   (일별×상품별, Parquet, 날짜 파티셔닝)
                                   → mart/sales_monthly/ (월별×상품별 롤업, Parquet)
  2단계  mart/sales_daily → mart/dashboard/latest.json   (대시보드 JSON 계약, 기간 키 1w/1m/6m/1y)

로컬은 MinIO를 s3:// 프로토콜로 읽고 쓴다 → 클라우드 전환 시 엔드포인트/자격증명만 교체.
visitors·searchRanking은 조회/검색 로그(KAN-71/72) 적재 후 채운다 — 지금은 0/[].
차원(products/categories)은 RDS 스냅샷 TSV로 조인한다 (클라우드에선 Lambda가 RDS 직조회).

사용:
  python3 build_mart_json.py --products dim_products.tsv --categories dim_categories.tsv \
      --out latest.json [--skip-tables] [--anchor 2026-07-02] [--endpoint localhost:9000]
"""
import argparse
import datetime as dt
import json
import subprocess

PERIODS = [  # (키, 라벨, 윈도우 일수, 일별매출 버킷)
    ("1w", "1주", 7, "day"),
    ("1m", "1개월", 30, "week"),
    ("6m", "6개월", 182, "month"),
    ("1y", "1년", 365, "month"),
]

S3_SETUP = """
INSTALL httpfs; LOAD httpfs;
SET s3_endpoint='{endpoint}';
SET s3_access_key_id='{access_key}';
SET s3_secret_access_key='{secret_key}';
SET s3_use_ssl={use_ssl};
SET s3_url_style='path';
"""

# ── 1단계: raw → 집계 테이블 (sales_daily / sales_monthly) ──
STAGE1 = """
CREATE VIEW raw AS
SELECT * FROM read_json('{raw_glob}', format='newline_delimited', compression='gzip');

-- 정제 규칙 (KAN-73에서 검증): 중복 제거 / 삭제 id 제외 / 수량 이상치 컷
CREATE VIEW clean AS
WITH dedup AS (
    SELECT *, row_number() OVER (PARTITION BY history_id ORDER BY created_at DESC) AS rn
    FROM raw
),
deleted_ids AS (SELECT DISTINCT history_id FROM raw WHERE __deleted = 'true')
SELECT history_id, user_id, product_id, quantity, price,
       price * quantity AS line_total,
       CAST(created_at AS DATE) AS d
FROM dedup
WHERE rn = 1
  AND __deleted = 'false'
  AND history_id NOT IN (SELECT history_id FROM deleted_ids)
  AND quantity BETWEEN 1 AND 100;

-- 일별×상품별 집계 → 날짜 파티셔닝 Parquet (Athena 파티션 프루닝 전제)
COPY (
    SELECT d, product_id,
           COUNT(*)                              AS orders,
           CAST(SUM(quantity) AS BIGINT)         AS units,
           CAST(ROUND(SUM(line_total)) AS BIGINT) AS revenue,
           year(d) AS year, month(d) AS month, day(d) AS day
    FROM clean GROUP BY d, product_id
) TO '{mart_root}/sales_daily'
  (FORMAT PARQUET, PARTITION_BY (year, month, day), OVERWRITE_OR_IGNORE true);

-- 월별×상품별 롤업
COPY (
    SELECT month_start, product_id, orders, units, revenue, year(month_start) AS year
    FROM (
        SELECT date_trunc('month', d) AS month_start, product_id,
               COUNT(*)                              AS orders,
               CAST(SUM(quantity) AS BIGINT)         AS units,
               CAST(ROUND(SUM(line_total)) AS BIGINT) AS revenue
        FROM clean GROUP BY 1, 2
    )
) TO '{mart_root}/sales_monthly'
  (FORMAT PARQUET, PARTITION_BY (year), OVERWRITE_OR_IGNORE true);
"""

# ── 2단계: 집계 테이블 → 대시보드 JSON (raw를 다시 읽지 않는다) ──
STAGE2_SETUP = """
CREATE VIEW daily AS
SELECT * FROM read_parquet('{mart_root}/sales_daily/**/*.parquet');
CREATE VIEW products AS SELECT * FROM read_csv('{products}', delim='\t', header=true);
CREATE VIEW categories AS SELECT * FROM read_csv('{categories}', delim='\t', header=true);
"""

DAILY_BUCKET = {
    "day": "strftime(d, '%m-%d')",
    "week": "CONCAT(CAST(FLOOR(DATE_DIFF('day', DATE '{start}', d) / 7) + 1 AS INT), '주차')",
    "month": "strftime(d, '%Y-%m')",
}


def duck(sql: str):
    """duckdb CLI로 실행. SELECT 결과가 있으면 행 목록(dict) 반환."""
    proc = subprocess.run(["duckdb", "-json", "-c", sql], capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit(f"duckdb 실패:\n{proc.stderr}")
    return json.loads(proc.stdout) if proc.stdout.strip() else []


def build_period(setup: str, anchor: str, days: int, bucket: str, label: str):
    win = f"d > DATE '{anchor}' - INTERVAL {days} DAY"
    prev = (f"d <= DATE '{anchor}' - INTERVAL {days} DAY "
            f"AND d > DATE '{anchor}' - INTERVAL {days * 2} DAY")

    kpi = duck(setup + f"""
        SELECT CAST(SUM(revenue) AS BIGINT)  AS totalSales,
               CAST(SUM(orders) AS BIGINT)   AS orderCount,
               CAST(SUM(units) AS BIGINT)    AS totalQuantity
        FROM daily WHERE {win};""")[0]
    kpi["visitors"] = 0  # TODO: 조회 로그(activity.view, KAN-71) 적재 후 집계

    start = (dt.date.fromisoformat(anchor) - dt.timedelta(days=days - 1)).isoformat()
    bucket_expr = DAILY_BUCKET[bucket].format(start=start)
    daily_sales = duck(setup + f"""
        SELECT {bucket_expr} AS date,
               CAST(SUM(revenue) AS BIGINT) AS sales,
               CAST(SUM(orders) AS BIGINT)  AS orders
        FROM daily WHERE {win}
        GROUP BY 1 ORDER BY MIN(d);""")

    category = duck(setup + f"""
        SELECT cat.category_name AS category,
               CAST(SUM(s.revenue) AS BIGINT) AS sales
        FROM daily s
        JOIN products p USING (product_id)
        JOIN categories cat USING (category_id)
        WHERE {win}
        GROUP BY 1 ORDER BY sales DESC;""")

    top = duck(setup + f"""
        SELECT any_value(p.product_name) AS productName,
               any_value(cat.category_name) AS category,
               CAST(SUM(CASE WHEN {win} THEN s.units ELSE 0 END) AS BIGINT)   AS unitsSold,
               CAST(SUM(CASE WHEN {win} THEN s.revenue ELSE 0 END) AS BIGINT) AS revenue,
               CAST(SUM(CASE WHEN {prev} THEN s.revenue ELSE 0 END) AS BIGINT) AS prevRevenue
        FROM daily s
        JOIN products p USING (product_id)
        JOIN categories cat USING (category_id)
        GROUP BY s.product_id
        HAVING revenue > 0
        ORDER BY revenue DESC LIMIT 10;""")
    for row in top:  # 직전 동일 길이 구간 대비 성장률(%). 직전 매출 0이면 신규 → null
        prev_rev = row.pop("prevRevenue") or 0
        row["growth"] = round(100 * (row["revenue"] - prev_rev) / prev_rev) if prev_rev else None

    return {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "period": label,
        "kpi": kpi,
        "dailySales": daily_sales,
        "categorySales": category,
        "topProducts": top,
        "searchRanking": [],  # TODO: 검색 로그(activity.search, KAN-72) 적재 후 집계
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--endpoint", default="localhost:9000", help="S3 엔드포인트 (클라우드는 s3.ap-northeast-2.amazonaws.com)")
    ap.add_argument("--access-key", default="minioadmin")
    ap.add_argument("--secret-key", default="minioadmin")
    ap.add_argument("--use-ssl", default="false")
    ap.add_argument("--raw", default="s3://hf4-datalake/raw/purchase-history/**/*.gz")
    ap.add_argument("--mart-root", default="s3://hf4-datalake/mart")
    ap.add_argument("--products", required=True, help="RDS products 스냅샷 TSV")
    ap.add_argument("--categories", required=True, help="RDS categories 스냅샷 TSV")
    ap.add_argument("--anchor", default=None, help="집계 기준일 (기본: 집계 테이블 최신일)")
    ap.add_argument("--out", default="dashboard_latest.json")
    ap.add_argument("--skip-tables", action="store_true", help="1단계(집계 테이블 재생성) 생략")
    args = ap.parse_args()

    s3 = S3_SETUP.format(endpoint=args.endpoint, access_key=args.access_key,
                         secret_key=args.secret_key, use_ssl=args.use_ssl)

    # ── 1단계: 집계 테이블 재생성 ──
    if not args.skip_tables:
        duck(s3 + STAGE1.format(raw_glob=args.raw, mart_root=args.mart_root))
        print(f"1단계 완료: {args.mart_root}/sales_daily, sales_monthly (Parquet)")

    # ── 2단계: 집계 테이블 → 대시보드 JSON ──
    setup = s3 + STAGE2_SETUP.format(mart_root=args.mart_root,
                                     products=args.products, categories=args.categories)
    anchor = args.anchor or duck(setup + "SELECT MAX(d) AS d FROM daily;")[0]["d"]

    mart = {key: build_period(setup, anchor, days, bucket, label)
            for key, label, days, bucket in PERIODS}

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(mart, f, ensure_ascii=False, indent=2)
    print(f"2단계 완료: anchor={anchor} → {args.out}")
    for key, val in mart.items():
        k = val["kpi"]
        print(f"  {key}: 매출 {k['totalSales']:,} / 주문 {k['orderCount']:,} / 수량 {k['totalQuantity']:,}")


if __name__ == "__main__":
    main()
