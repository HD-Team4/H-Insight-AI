"""
KAN-76: S3 raw(행동 이벤트) → Biz 대시보드 mart JSON 생성 Lambda.

로컬에서 검증한 집계 로직(docs/aggregation/build_mart_json.py)을 Lambda로 이식.
"로컬에서 돌던 DuckDB 집계가 클라우드에서 그대로" — 같은 엔진(DuckDB), 같은 계약(mart JSON).

입력  : s3://{BUCKET}/raw/activity.purchase|search/**/*.gz  (ActivityEvent 엔벨로프)
        s3://{BUCKET}/dims/products.csv, categories.csv       (RDS 스냅샷)
출력  : s3://{BUCKET}/mart/dashboard/latest.json             (대시보드 계약, 기간 1w/1m/6m/1y)
트리거: EventBridge 스케줄 (예: 매시각). event/context 는 사용하지 않음.

이벤트 형식:
  {"type":"purchase","userId":1,"occurredAt":"2026-07-02T05:43:11Z",
   "payload":{"productId":1,"quantity":2,"price":39900}}
S3 접근은 DuckDB httpfs + credential_chain (= Lambda 실행 역할) 으로 서명.
"""
import datetime as dt
import json
import os

import boto3
import duckdb

BUCKET = os.environ.get("DATALAKE_BUCKET", "hf4-datalake")
REGION = os.environ.get("AWS_REGION", "ap-northeast-2")
OUT_KEY = os.environ.get("MART_KEY", "mart/dashboard/latest.json")

RAW_PURCHASE = f"s3://{BUCKET}/raw/activity.purchase/**/*.gz"
RAW_SEARCH = f"s3://{BUCKET}/raw/activity.search/**/*.gz"
DIM_PRODUCTS = f"s3://{BUCKET}/dims/products.csv"
DIM_CATEGORIES = f"s3://{BUCKET}/dims/categories.csv"

# (키, 라벨, 윈도우 일수, 일별매출 버킷)
PERIODS = [
    ("1w", "1주", 7, "day"),
    ("1m", "1개월", 30, "week"),
    ("6m", "6개월", 182, "month"),
    ("1y", "1년", 365, "month"),
]
DAILY_BUCKET = {
    "day": "strftime(d, '%m-%d')",
    "week": "CONCAT(CAST(FLOOR(DATE_DIFF('day', DATE '{start}', d) / 7) + 1 AS INT), '주차')",
    "month": "strftime(d, '%Y-%m')",
}


def _connect():
    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs;")
    # Lambda 실행 역할 자격증명으로 S3 접근 (키 하드코딩 없음)
    con.execute(f"CREATE SECRET (TYPE S3, PROVIDER credential_chain, REGION '{REGION}');")
    return con


def _setup_views(con):
    """raw + dim 을 뷰로. 데이터 없으면(첫 실행 등) 빈 뷰로 폴백해 0/[] 를 낸다."""
    # 구매: ActivityEvent 엔벨로프 → 평면화. at-least-once 중복은 DISTINCT 로 흡수, 수량 이상치 컷.
    try:
        con.execute(f"""
            CREATE OR REPLACE VIEW clean AS
            SELECT DISTINCT
                   userId                              AS user_id,
                   CAST(payload.productId AS BIGINT)   AS product_id,
                   CAST(payload.quantity AS INT)       AS quantity,
                   CAST(payload.price AS DOUBLE)       AS price,
                   CAST(payload.price AS DOUBLE) * CAST(payload.quantity AS INT) AS line_total,
                   CAST(occurredAt AS DATE)            AS d
            FROM read_json('{RAW_PURCHASE}', format='newline_delimited',
                           compression='gzip', ignore_errors=true)
            WHERE CAST(payload.quantity AS INT) BETWEEN 1 AND 100;
        """)
    except duckdb.Error:
        con.execute("CREATE OR REPLACE VIEW clean AS SELECT NULL::BIGINT user_id, "
                    "NULL::BIGINT product_id, NULL::INT quantity, NULL::DOUBLE price, "
                    "NULL::DOUBLE line_total, NULL::DATE d WHERE false;")

    # 검색: 인기 검색어용
    try:
        con.execute(f"""
            CREATE OR REPLACE VIEW searches AS
            SELECT payload.keyword AS keyword, CAST(occurredAt AS DATE) AS d
            FROM read_json('{RAW_SEARCH}', format='newline_delimited',
                           compression='gzip', ignore_errors=true)
            WHERE payload.keyword IS NOT NULL AND payload.keyword <> '';
        """)
    except duckdb.Error:
        con.execute("CREATE OR REPLACE VIEW searches AS "
                    "SELECT NULL::VARCHAR keyword, NULL::DATE d WHERE false;")

    con.execute(f"CREATE OR REPLACE VIEW products AS SELECT * FROM read_csv('{DIM_PRODUCTS}', header=true);")
    con.execute(f"CREATE OR REPLACE VIEW categories AS SELECT * FROM read_csv('{DIM_CATEGORIES}', header=true);")


def _rows(con, sql):
    cur = con.execute(sql)
    cols = [c[0] for c in cur.description]
    return [dict(zip(cols, r)) for r in cur.fetchall()]


def _build_period(con, anchor, days, bucket, label):
    win = f"d > DATE '{anchor}' - INTERVAL {days} DAY"
    prev = (f"d <= DATE '{anchor}' - INTERVAL {days} DAY "
            f"AND d > DATE '{anchor}' - INTERVAL {days * 2} DAY")

    kpi = _rows(con, f"""
        SELECT CAST(COALESCE(SUM(line_total),0) AS BIGINT) AS totalSales,
               COUNT(*)                                    AS orderCount,
               CAST(COALESCE(SUM(quantity),0) AS BIGINT)   AS totalQuantity
        FROM clean WHERE {win};""")[0]
    kpi["visitors"] = 0  # 조회 로그(activity.click) distinct user 로 확장 가능

    start = (dt.date.fromisoformat(str(anchor)) - dt.timedelta(days=days - 1)).isoformat()
    bucket_expr = DAILY_BUCKET[bucket].format(start=start)
    daily = _rows(con, f"""
        SELECT {bucket_expr} AS date,
               CAST(SUM(line_total) AS BIGINT) AS sales,
               COUNT(*)                        AS orders
        FROM clean WHERE {win} GROUP BY 1 ORDER BY MIN(d);""")

    category = _rows(con, f"""
        SELECT cat.category_name AS category, CAST(SUM(c.line_total) AS BIGINT) AS sales
        FROM clean c JOIN products p USING (product_id)
                     JOIN categories cat USING (category_id)
        WHERE {win} GROUP BY 1 ORDER BY sales DESC;""")

    top = _rows(con, f"""
        SELECT any_value(p.product_name) AS productName,
               any_value(cat.category_name) AS category,
               CAST(SUM(CASE WHEN {win} THEN c.quantity ELSE 0 END) AS BIGINT)    AS unitsSold,
               CAST(SUM(CASE WHEN {win} THEN c.line_total ELSE 0 END) AS BIGINT)  AS revenue,
               CAST(SUM(CASE WHEN {prev} THEN c.line_total ELSE 0 END) AS BIGINT) AS prevRevenue
        FROM clean c JOIN products p USING (product_id)
                     JOIN categories cat USING (category_id)
        GROUP BY c.product_id HAVING revenue > 0 ORDER BY revenue DESC LIMIT 10;""")
    for row in top:
        prev_rev = row.pop("prevRevenue") or 0
        row["growth"] = round(100 * (row["revenue"] - prev_rev) / prev_rev) if prev_rev else None

    ranking = _rows(con, f"""
        SELECT ROW_NUMBER() OVER (ORDER BY COUNT(*) DESC) AS rank,
               keyword, COUNT(*) AS count
        FROM searches WHERE {win} GROUP BY keyword ORDER BY count DESC LIMIT 30;""")

    return {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "period": label,
        "kpi": kpi,
        "dailySales": daily,
        "categorySales": category,
        "topProducts": top,
        "searchRanking": ranking,
    }


def lambda_handler(event, context):
    con = _connect()
    _setup_views(con)

    anchor_row = _rows(con, "SELECT MAX(d) AS d FROM clean;")
    anchor = anchor_row[0]["d"] or dt.date.today()

    mart = {key: _build_period(con, anchor, days, bucket, label)
            for key, label, days, bucket in PERIODS}

    body = json.dumps(mart, ensure_ascii=False, indent=2)
    boto3.client("s3").put_object(
        Bucket=BUCKET, Key=OUT_KEY,
        Body=body.encode("utf-8"), ContentType="application/json",
    )
    summary = {k: v["kpi"] for k, v in mart.items()}
    print(f"[aggregate_mart] anchor={anchor} → s3://{BUCKET}/{OUT_KEY}  {summary}")
    return {"statusCode": 200, "anchor": str(anchor), "out": f"s3://{BUCKET}/{OUT_KEY}"}
