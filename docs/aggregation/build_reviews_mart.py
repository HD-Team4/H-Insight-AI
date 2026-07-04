#!/usr/bin/env python3
"""
KAN-64 STEP1: 리뷰 분석 마트 — 긍부정(로컬 모델) + 주요 키워드(측면 매칭). LLM 없음, 쿼터 0.

긍부정: KoELECTRA 한국어 감성 모델(Copycats/koelectra-...)을 리뷰 원문에만 적용
        (별점·DB라벨 미제공 = 컨닝 방지) → 신뢰도 임계값으로 중립 처리.
        DB 정답 라벨과 비교해 정확도 산출 (검증: 실측 97%).
키워드: 팀원이 설계한 10개 측면 사전으로 매칭 → 상품별 주요 측면 + 부정 원인.

출력: mart/products/reviews.json  (전략(strategy)은 STEP2에서 Gemini가 이 결과를 입력받아 생성)
      S3 키 있으면 s3://hf4-datalake/mart/products/reviews.json 업로드.

사용:  python3 build_reviews_mart.py [--pilot 1,3] [--out reviews.json]
"""
import argparse
import json
import subprocess
from collections import Counter, defaultdict

ENV = {}
for line in open(".env"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        ENV[k] = v

RDS = "hf4-db.caz9bnevtpvk.ap-northeast-2.rds.amazonaws.com"
MODEL = "Copycats/koelectra-base-v3-generalized-sentiment-analysis"
CONF_THRESHOLD = 0.95   # 승자확률 < 0.95 → 중립 (검증에서 97% 정확도)
BUCKET, MART_KEY = "hf4-datalake", "mart/products/reviews.json"

# 팀원 설계 10측면 → 매칭 키워드
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


def mysql(query):
    out = subprocess.run(
        ["mysql", "-h", RDS, "-u", ENV["DB_USERNAME"], "--batch",
         "--ssl-verify-server-cert=0", "--default-character-set=utf8mb4", "-e", query, "hf4_db"],
        capture_output=True, text=True,
        env={"MYSQL_PWD": ENV["DB_PASSWORD"], "PATH": "/opt/homebrew/bin:/usr/bin:/bin"})
    if out.returncode != 0:
        raise SystemExit(f"mysql 실패: {out.stderr[:300]}")
    lines = [l for l in out.stdout.splitlines() if l]
    if not lines:
        return []
    header = lines[0].split("\t")
    return [dict(zip(header, l.split("\t"))) for l in lines[1:]]


def match_aspects(text):
    hit = []
    for aspect, words in ASPECTS.items():
        if any(w in text for w in words):
            hit.append(aspect)
    return hit


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pilot", default=None)
    ap.add_argument("--out", default="reviews.json")
    args = ap.parse_args()

    where = f"AND product_id IN ({args.pilot})" if args.pilot else ""
    print("[1/4] RDS 리뷰 로드")
    reviews = mysql(f"SELECT review_id, product_id, sentiment, "
                    f"REPLACE(REPLACE(content,'\\t',' '),'\\n',' ') content "
                    f"FROM reviews WHERE content IS NOT NULL {where} ORDER BY product_id, review_id;")
    names = {int(r["product_id"]): r["product_name"] for r in mysql(
        "SELECT product_id, product_name FROM products;")}
    print(f"  리뷰 {len(reviews)}건 / 상품 {len({r['product_id'] for r in reviews})}개")

    print(f"[2/4] 긍부정 분류 (로컬 모델, 신뢰도<{CONF_THRESHOLD}→중립)")
    from transformers import pipeline
    clf = pipeline("sentiment-analysis", model=MODEL, truncation=True, max_length=256)
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
        b = by_pid[int(r["product_id"])]
        b["sent"][judged] += 1
        b["n"] += 1
        asp = match_aspects(r["content"])
        b["asp"].update(asp)
        if judged == "부정":
            b["neg_asp"].update(asp)
    overall_acc = round(100 * correct / len(reviews), 1)
    print(f"  ✅ 전체 정확도(모델 vs DB 정답): {overall_acc}%")

    print("[3/4] 상품별 마트 조립 (감성비율 + 키워드)")
    products = []
    for pid in sorted(by_pid):
        b = by_pid[pid]
        n = b["n"]
        products.append({
            "productId": pid,
            "productName": names.get(pid, f"상품{pid}"),
            "reviewCount": n,
            "reviewSentiment": {
                "positive": round(100 * b["sent"]["긍정"] / n),
                "neutral": round(100 * b["sent"]["중립"] / n),
                "negative": round(100 * b["sent"]["부정"] / n),
            },
            "keywords": [a for a, _ in b["asp"].most_common(6)],
            "painPoints": [a for a, _ in b["neg_asp"].most_common(4)],  # 부정 리뷰 원인
        })

    tot = Counter()
    for p in products:
        for k, v in p["reviewSentiment"].items():
            tot[k] += v
    n = len(products) or 1
    mart = {
        "reviewSentiment": {k: round(v / n) for k, v in tot.items()},
        "sentimentAccuracy": overall_acc,
        "model": MODEL,
        "totalReviews": len(reviews),
        "products": products,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(mart, f, ensure_ascii=False, indent=2)
    print(f"  → {args.out} (전체 긍{mart['reviewSentiment']['positive']}% · 정확도 {overall_acc}%)")

    print("[4/4] S3 업로드 시도")
    try:
        import boto3
        boto3.client("s3", aws_access_key_id=ENV["AWS_ACCESS_KEY_ID"],
                     aws_secret_access_key=ENV["AWS_SECRET_ACCESS_KEY"],
                     region_name=ENV.get("AWS_REGION", "ap-northeast-2")).put_object(
            Bucket=BUCKET, Key=MART_KEY,
            Body=json.dumps(mart, ensure_ascii=False).encode(), ContentType="application/json")
        print(f"  ✅ s3://{BUCKET}/{MART_KEY}")
    except Exception as e:
        print(f"  ⚠️ S3 생략 (키 확인 필요): {type(e).__name__} — 로컬 파일 생성됨")


if __name__ == "__main__":
    main()
