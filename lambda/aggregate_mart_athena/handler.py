"""
KAN-76: 클라우드 집계 Lambda (Athena 버전) — 순수 파이썬, ECR/컨테이너 불필요.

Athena가 S3 raw(ActivityEvent)를 SQL로 스캔·집계 → Lambda가 mart JSON 조립 → S3.
차원(products/categories)은 S3의 dims CSV를 파이썬으로 읽어 조인 (dim용 Athena 테이블 불필요).

선행:
  - Athena 테이블: docs/cloud/athena/ddl.sql (activity_purchase / activity_search)
  - dims CSV:      s3://hf4-datalake/dims/products.csv, categories.csv
  - Athena 결과 위치: s3://hf4-datalake/athena-results/
트리거: EventBridge 스케줄. 실행역할 권한: Athena + Glue(카탈로그) + S3(raw 읽기/mart·결과 쓰기).
"""
import boto3, csv, io, json, time
import datetime as dt

DB = "hf4_datalake"
BUCKET = "hf4-datalake"
ATHENA_OUT = f"s3://{BUCKET}/athena-results/"
MART_KEY = "mart/dashboard/latest.json"
PRODUCT_SALES_KEY = "mart/products/sales.json"  # 전체 상품별 매출·성장률 (급등/급락 선별·LLM 전략 입력용)

PERIODS = [("1w", "1주", 7, "day"), ("1m", "1개월", 30, "week"),
           ("6m", "6개월", 182, "month"), ("1y", "1년", 365, "month")]

athena = boto3.client("athena")
s3 = boto3.client("s3")


def run_athena(query):
    qid = athena.start_query_execution(
        QueryString=query,
        QueryExecutionContext={"Database": DB},
        ResultConfiguration={"OutputLocation": ATHENA_OUT},
    )["QueryExecutionId"]
    while True:
        ex = athena.get_query_execution(QueryExecutionId=qid)["QueryExecution"]["Status"]
        if ex["State"] in ("SUCCEEDED", "FAILED", "CANCELLED"):
            break
        time.sleep(1)
    if ex["State"] != "SUCCEEDED":
        raise RuntimeError(f"Athena {ex['State']}: {ex.get('StateChangeReason','')}")
    rows, header, token = [], None, None
    while True:
        kw = {"QueryExecutionId": qid, "MaxResults": 1000}
        if token:
            kw["NextToken"] = token
        r = athena.get_query_results(**kw)
        for row in r["ResultSet"]["Rows"]:
            vals = [c.get("VarCharValue") for c in row["Data"]]
            if header is None:
                header = vals
            else:
                rows.append(dict(zip(header, vals)))
        token = r.get("NextToken")
        if not token:
            break
    return rows


def read_dim(key):
    body = s3.get_object(Bucket=BUCKET, Key=key)["Body"].read().decode("utf-8")
    return list(csv.DictReader(io.StringIO(body)))


def bucket_label(d, mode, start):
    if mode == "day":
        return d.strftime("%m-%d")
    if mode == "week":
        return f"{(d - start).days // 7 + 1}주차"
    return d.strftime("%Y-%m")


def build_period(pur, srch, clk, prod2cat, cat_name, anchor, days, mode, label):
    lo, plo = anchor - dt.timedelta(days=days), anchor - dt.timedelta(days=days * 2)
    start = anchor - dt.timedelta(days=days - 1)
    win = [r for r in pur if lo < r["d"] <= anchor]

    total_sales = sum(r["revenue"] for r in win)
    total_qty = sum(r["units"] for r in win)
    orders = sum(r["orders"] for r in win)

    daily = {}
    for r in win:
        b = daily.setdefault(bucket_label(r["d"], mode, start), {"sales": 0, "orders": 0, "_min": r["d"]})
        b["sales"] += r["revenue"]; b["orders"] += r["orders"]; b["_min"] = min(b["_min"], r["d"])
    daily_out = [{"date": k, "sales": v["sales"], "orders": v["orders"]}
                 for k, v in sorted(daily.items(), key=lambda kv: kv[1]["_min"])]

    cat_rev = {}
    for r in win:
        cn = cat_name.get(prod2cat.get(r["pid"], (None, None))[1])
        if cn:
            cat_rev[cn] = cat_rev.get(cn, 0) + r["revenue"]
    category_out = [{"category": k, "sales": v} for k, v in sorted(cat_rev.items(), key=lambda x: -x[1])]

    cur, prev = {}, {}
    for r in pur:
        if lo < r["d"] <= anchor:
            c = cur.setdefault(r["pid"], [0, 0]); c[0] += r["units"]; c[1] += r["revenue"]
        elif plo < r["d"] <= lo:
            prev[r["pid"]] = prev.get(r["pid"], 0) + r["revenue"]
    top = []
    for pid, (units, rev) in sorted(cur.items(), key=lambda x: -x[1][1])[:10]:
        pr = prev.get(pid, 0)
        name, cid = prod2cat.get(pid, ("(unknown)", None))
        top.append({"productName": name, "category": cat_name.get(cid, "-"),
                    "unitsSold": units, "revenue": rev,
                    "growth": round(100 * (rev - pr) / pr) if pr else None})

    kw = {}
    for r in srch:
        if lo < r["d"] <= anchor:
            kw[r["kw"]] = kw.get(r["kw"], 0) + r["cnt"]
    ranking = [{"rank": i + 1, "keyword": k, "count": c}
               for i, (k, c) in enumerate(sorted(kw.items(), key=lambda x: -x[1])[:30])]

    # 방문자 = 조회 로그(activity_click) 일별 순방문자(DAU) 합
    visitors = sum(r["dau"] for r in clk if lo < r["d"] <= anchor)

    return {"generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
            "period": label,
            "kpi": {"totalSales": total_sales, "orderCount": orders,
                    "totalQuantity": total_qty, "visitors": visitors},
            "dailySales": daily_out, "categorySales": category_out,
            "topProducts": top, "searchRanking": ranking}


def product_sales(pur, prod2cat, cat_name, price_by, rr_by, anchor, days):
    """기간 내 전체 상품별 매출·성장률 + 가격·재구매율 (topProducts와 같은 계산, 10개 컷 없이 전부).
    이번 기간 매출 0인데 직전 기간 매출이 있던 상품(급락 -100%)도 포함."""
    lo, plo = anchor - dt.timedelta(days=days), anchor - dt.timedelta(days=days * 2)
    cur, prev = {}, {}
    for r in pur:
        if lo < r["d"] <= anchor:
            c = cur.setdefault(r["pid"], [0, 0]); c[0] += r["units"]; c[1] += r["revenue"]
        elif plo < r["d"] <= lo:
            prev[r["pid"]] = prev.get(r["pid"], 0) + r["revenue"]

    def row(pid, units, rev, pr):
        name, cid = prod2cat.get(pid, ("(unknown)", None))
        return {"productId": pid, "productName": name, "category": cat_name.get(cid, "-"),
                "unitsSold": units, "revenue": rev, "prevRevenue": pr,
                "growth": round(100 * (rev - pr) / pr) if pr else None,
                "price": price_by.get(pid), "repurchaseRate": rr_by.get(pid, 0)}

    out = [row(pid, u, rev, prev.get(pid, 0))
           for pid, (u, rev) in sorted(cur.items(), key=lambda x: -x[1][1])]
    out += [row(pid, 0, 0, pr) for pid, pr in prev.items() if pid not in cur]  # 급락 -100%
    return out


def lambda_handler(event, context):
    prod2cat = {int(r["product_id"]): (r["product_name"], int(r["category_id"]))
                for r in read_dim("dims/products.csv")}
    cat_name = {int(r["category_id"]): r["category_name"] for r in read_dim("dims/categories.csv")}

    pur = run_athena("""
        SELECT date(from_iso8601_timestamp(occurredat)) AS d, payload.productid AS pid,
               sum(payload.quantity) AS units,
               sum(payload.price * payload.quantity) AS revenue, count(*) AS orders
        FROM hf4_datalake.activity_purchase
        WHERE payload.quantity BETWEEN 1 AND 100
        GROUP BY 1, 2""")
    srch = run_athena("""
        SELECT date(from_iso8601_timestamp(occurredat)) AS d, payload.keyword AS kw, count(*) AS cnt
        FROM hf4_datalake.activity_search
        WHERE payload.keyword <> '' GROUP BY 1, 2""")
    clk = run_athena("""
        SELECT date(from_iso8601_timestamp(occurredat)) AS d,
               count(distinct userid) AS dau
        FROM hf4_datalake.activity_click GROUP BY 1""")
    # 상품별 판매가(구매 이벤트의 payload.price) + 재구매율(2회 이상 구매 고객 비율)
    prc = run_athena("""
        SELECT payload.productid AS pid, max(payload.price) AS price
        FROM hf4_datalake.activity_purchase
        WHERE payload.quantity BETWEEN 1 AND 100 GROUP BY 1""")
    rep = run_athena("""
        SELECT pid, round(100.0 * count_if(cnt >= 2) / count(*)) AS rr FROM (
          SELECT payload.productid AS pid, userid, count(*) AS cnt
          FROM hf4_datalake.activity_purchase GROUP BY 1, 2) GROUP BY pid""")

    # 문자열 → 타입 변환
    for r in pur:
        r["d"] = dt.date.fromisoformat(r["d"]); r["pid"] = int(r["pid"])
        r["units"] = int(r["units"]); r["revenue"] = int(float(r["revenue"])); r["orders"] = int(r["orders"])
    for r in srch:
        r["d"] = dt.date.fromisoformat(r["d"]); r["cnt"] = int(r["cnt"])
    for r in clk:
        r["d"] = dt.date.fromisoformat(r["d"]); r["dau"] = int(r["dau"])
    price_by = {int(r["pid"]): int(float(r["price"])) for r in prc if r.get("price")}
    rr_by = {int(r["pid"]): int(float(r["rr"])) for r in rep if r.get("rr")}

    anchor = max((r["d"] for r in pur), default=dt.date.today())
    mart = {key: build_period(pur, srch, clk, prod2cat, cat_name, anchor, days, mode, label)
            for key, label, days, mode in PERIODS}

    s3.put_object(Bucket=BUCKET, Key=MART_KEY,
                  Body=json.dumps(mart, ensure_ascii=False).encode("utf-8"),
                  ContentType="application/json")

    # 전체 상품별 매출 마트 (기간별) — 급등/급락 선별과 LLM 전략 생성의 입력
    sales_mart = {"generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                  "anchor": str(anchor),
                  **{key: product_sales(pur, prod2cat, cat_name, price_by, rr_by, anchor, days)
                     for key, label, days, mode in PERIODS}}
    s3.put_object(Bucket=BUCKET, Key=PRODUCT_SALES_KEY,
                  Body=json.dumps(sales_mart, ensure_ascii=False).encode("utf-8"),
                  ContentType="application/json")

    summary = {k: v["kpi"]["totalSales"] for k, v in mart.items()}
    print(f"[aggregate_mart_athena] anchor={anchor} → s3://{BUCKET}/{MART_KEY} {summary} "
          f"+ {PRODUCT_SALES_KEY}({len(sales_mart['1m'])}개 상품)")
    return {"statusCode": 200, "anchor": str(anchor)}
