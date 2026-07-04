"""
KAN-64: 리뷰 분석 Lambda (컨테이너 이미지) — 로컬 KoELECTRA로 긍부정+키워드 → S3 마트.

집계 Lambda(aggregate_mart_athena)와 동일하게 EventBridge 스케줄로 하루 1회 실행한다.
torch+transformers(~2GB)가 필요해 콘솔 붙여넣기가 안 됨 → 컨테이너 이미지(ECR).

- 리뷰: RDS(reviews) 직접 조회(pymysql). DB 접속정보는 Lambda 환경변수(이미지에 안 굽음).
- 모델: 이미지에 구워넣음(/opt/model) → 런타임 다운로드/HF 레이트리밋 없음. 콜드스타트 1회 로드 후 재사용.
- 긍부정: 리뷰 원문만 입력(별점·라벨 미제공=컨닝 방지), 승자확률<임계값 → 중립.
- 키워드: 10측면 사전 문자열 매칭(모델 불필요). keywords=전체 언급, painPoints=부정 리뷰 원인.
- 정확도: 모델 판별 vs DB sentiment 라벨 비교 (로컬 검증 96.5%).
- 출력: s3://<BUCKET>/mart/products/reviews.json (S3 쓰기는 실행역할 권한 — 액세스키 불필요).
"""
import json
import os
from collections import Counter, defaultdict

import boto3
import pymysql

MODEL_DIR = os.environ.get("MODEL_DIR", "/opt/model")
CONF_THRESHOLD = float(os.environ.get("CONF_THRESHOLD", "0.95"))  # 승자확률<이 값 → 중립
BUCKET = os.environ["BUCKET"]
MART_KEY = os.environ.get("MART_KEY", "mart/products/reviews.json")

# 팀원 설계 10측면 → 매칭 키워드 (build_reviews_mart.py와 동일)
ASPECTS = {
    "핏": ["핏", "오버핏", "슬림", "루즈", "품이", "몸에"],
    "사이즈": ["사이즈", "치수", "작아", "커요", "커서", "정사이즈", "크기"],
    "원단/소재": ["원단", "소재", "재질", "얇", "두께", "싸구려", "면"],
    "색상": ["색상", "색감", "컬러", "색이"],
    "배송": ["배송", "택배", "도착", "빨리", "늦", "지연"],
    "가성비": ["가격", "가성비", "값", "저렴", "비싸"],
    "봉제/마감": ["마감", "봉제", "박음질", "실밥", "바느질"],
    "보풀": ["보풀"],
    "세탁": ["세탁", "물빠짐", "줄어", "변형", "수축", "물들"],
    "냄새": ["냄새", "화학", "쿰쿰"],
}

_clf = None  # 콜드스타트에서 1회 로드 후 웜 인보크 재사용


def get_clf():
    global _clf
    if _clf is None:
        from transformers import pipeline
        _clf = pipeline("sentiment-analysis", model=MODEL_DIR, tokenizer=MODEL_DIR,
                        truncation=True, max_length=256)
    return _clf


def fetch_reviews():
    conn = pymysql.connect(
        host=os.environ["DB_HOST"], user=os.environ["DB_USER"],
        password=os.environ["DB_PASSWORD"], database=os.environ.get("DB_NAME", "hf4_db"),
        charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor, connect_timeout=10)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT review_id, product_id, sentiment, content FROM reviews "
                        "WHERE content IS NOT NULL ORDER BY product_id, review_id")
            reviews = cur.fetchall()
            cur.execute("SELECT product_id, product_name FROM products")
            names = {r["product_id"]: r["product_name"] for r in cur.fetchall()}
    finally:
        conn.close()
    return reviews, names


def match_aspects(text):
    return [a for a, words in ASPECTS.items() if any(w in text for w in words)]


def lambda_handler(event, context):
    reviews, names = fetch_reviews()
    clf = get_clf()
    preds = clf([r["content"] for r in reviews], batch_size=16)

    def to3(p):
        if p["score"] < CONF_THRESHOLD:
            return "중립"
        return "긍정" if p["label"] == "1" else "부정"

    correct = 0
    by_pid = defaultdict(lambda: {"sent": Counter(), "asp": Counter(), "neg_asp": Counter(), "n": 0})
    for r, p in zip(reviews, preds):
        judged = to3(p)
        if judged == r["sentiment"]:
            correct += 1
        b = by_pid[r["product_id"]]
        b["sent"][judged] += 1
        b["n"] += 1
        asp = match_aspects(r["content"])
        b["asp"].update(asp)
        if judged == "부정":
            b["neg_asp"].update(asp)
    overall_acc = round(100 * correct / len(reviews), 1) if reviews else 0.0

    products = []
    for pid in sorted(by_pid):
        b = by_pid[pid]
        n = b["n"]
        products.append({
            "productId": pid, "productName": names.get(pid, f"상품{pid}"), "reviewCount": n,
            "reviewSentiment": {
                "positive": round(100 * b["sent"]["긍정"] / n),
                "neutral": round(100 * b["sent"]["중립"] / n),
                "negative": round(100 * b["sent"]["부정"] / n)},
            "keywords": [a for a, _ in b["asp"].most_common(6)],
            "painPoints": [a for a, _ in b["neg_asp"].most_common(4)],
        })

    tot = Counter()
    for p in products:
        for k, v in p["reviewSentiment"].items():
            tot[k] += v
    n = len(products) or 1
    mart = {
        "reviewSentiment": {k: round(v / n) for k, v in tot.items()},
        "sentimentAccuracy": overall_acc,
        "model": "Copycats/koelectra-base-v3-generalized-sentiment-analysis",
        "totalReviews": len(reviews),
        "products": products,
    }

    boto3.client("s3").put_object(
        Bucket=BUCKET, Key=MART_KEY,
        Body=json.dumps(mart, ensure_ascii=False).encode("utf-8"),
        ContentType="application/json")
    print(f"[reviews_analysis] reviews={len(reviews)} acc={overall_acc}% "
          f"→ s3://{BUCKET}/{MART_KEY}")
    return {"statusCode": 200, "totalReviews": len(reviews), "accuracy": overall_acc}
