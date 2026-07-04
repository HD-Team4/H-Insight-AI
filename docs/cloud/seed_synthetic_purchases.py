#!/usr/bin/env python3
"""데모용 합성 구매 이벤트 시더 — 리뷰 상품(1~30)의 최근 2주치 구매를 S3 raw에 적재.

주간(1w) 창에서 상승률/하락률 TOP5가 채워지도록, 상품마다 직전주+최근주 매출을 만들되
홀수 id는 상승·짝수 id는 하락하게 배율을 준다. 앱이 발행하는 ActivityEvent와 동일 형식.

CloudShell에서 실행 (boto3·인증 내장):  python3 seed_synthetic_purchases.py
Athena가 즉시 스캔하므로, 이후 집계 Lambda → 전략 Lambda 재실행하면 반영됨.
"""
import datetime as dt
import gzip
import io
import json
import random

import boto3

BUCKET = "hf4-datalake"
KEY = "raw/activity.purchase/year=2026/month=07/day=03/synthetic-boost-week.gz"
PRICE = {1: 39900, 2: 29900, 3: 19900, 4: 49900, 5: 29900, 6: 29900, 7: 49900, 8: 39900,
         9: 19900, 10: 49900, 11: 39900, 12: 29900, 13: 19900, 14: 29900, 15: 19900,
         16: 29900, 17: 12900, 18: 19900, 19: 19900, 20: 29900, 21: 29900, 22: 19900,
         23: 29900, 24: 29900, 25: 39900, 26: 29900, 27: 19900, 28: 12900, 29: 29900, 30: 29900}

random.seed(42)
PRIOR_START = dt.date(2026, 6, 20)   # 직전주 (06-20 ~ 06-26)
RECENT_START = dt.date(2026, 6, 27)  # 최근주 (06-27 ~ 07-03), anchor=07-03

events = []


def emit(pid, day_start, n_orders, users):
    for _ in range(n_orders):
        d = day_start + dt.timedelta(days=random.randint(0, 6))
        occurred = f"{d}T{random.randint(9,21):02d}:{random.randint(0,59):02d}:00Z"
        events.append({"type": "purchase", "userId": random.choice(users),
                       "occurredAt": occurred,
                       "payload": {"productId": pid, "quantity": 1, "price": PRICE[pid]}})


for pid in range(1, 31):
    users = [random.randint(1, 300) for _ in range(6)]   # 작은 풀 → 재구매 발생
    prior = random.randint(3, 6)
    if pid % 2 == 1:                                       # 홀수 = 상승
        recent = round(prior * random.uniform(1.6, 4.0))
    else:                                                  # 짝수 = 하락 (일부 0 → -100%)
        recent = round(prior * random.uniform(0.0, 0.55))
    emit(pid, PRIOR_START, prior, users)
    emit(pid, RECENT_START, recent, users)

buf = io.BytesIO()
with gzip.GzipFile(fileobj=buf, mode="wb") as gz:
    gz.write(("\n".join(json.dumps(e, ensure_ascii=False) for e in events) + "\n").encode("utf-8"))
boto3.client("s3").put_object(Bucket=BUCKET, Key=KEY, Body=buf.getvalue())
print(f"✅ {len(events)}건 구매 이벤트 → s3://{BUCKET}/{KEY}")
print(f"   상품 30개 · 직전주({PRIOR_START}~)+최근주({RECENT_START}~) · 홀수 상승/짝수 하락")
