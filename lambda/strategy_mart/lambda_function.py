"""
KAN-97: 상품 전략 Lambda (순수 파이썬 — urllib+boto3만, 패키지 불필요 → 콘솔 붙여넣기 가능).

입력 = S3 두 마트만 (RDS·raw 미접근):
  ① mart/products/sales.json    전체 상품별 매출·성장률·가격·재구매율 (집계 Lambda)
  ② mart/products/reviews.json  상품별 긍부정%·키워드·painPoints (리뷰 Lambda)
→ 리뷰 분석 대상 상품 중 주간 급등/급락 TOP5 선별
→ 요약만 LLM(NVIDIA 우선, Gemini 폴백)에 전달 → 상품별 향상 전략 생성
→ mart/products/latest.json 저장 (상품 분석 화면이 읽는 최종 데이터). LLM 실패 시 템플릿 폴백.

환경변수: NVIDIA_API_KEY (권장), GEMINI_API_KEY (폴백). S3 쓰기는 실행역할 권한.
트리거: EventBridge (집계·리뷰 Lambda 이후 실행되게 스케줄).
"""
import json
import os
import time
import urllib.request
import urllib.error
import datetime as dt

import boto3

BUCKET = "hf4-datalake"
SALES_KEY = "mart/products/sales.json"
REVIEWS_KEY = "mart/products/reviews.json"
OUT_KEY = "mart/products/latest.json"

NVIDIA_MODELS = ["meta/llama-4-maverick-17b-128e-instruct", "meta/llama-3.1-70b-instruct"]
GEMINI_MODELS = ["gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.0-flash"]

s3 = boto3.client("s3")

ASPECT_FIX = {
    "봉제/마감": "봉제/마감 — 출고 전 검수 공정 강화, 불량 로트 회수 검토",
    "보풀": "보풀 — 원단 혼용률 재검토, 세탁 주의 안내 태그 보강",
    "사이즈": "사이즈 — 실측 사이즈표·모델 착용 정보 상세화",
    "색상": "색상 — 상세페이지 색감 보정, 실물 촬영컷 추가",
    "원단/소재": "원단/소재 — 두께·재질 스펙 명시, 차기 생산분 소재 개선",
    "세탁": "세탁 — 세탁 후 변형 이슈 품질 점검, 케어라벨 안내 강화",
    "냄새": "냄새 — 포장·보관 공정 점검, 출고 전 에어링 처리",
    "배송": "배송 — 출고 리드타임 단축, 지연 시 자동 알림 도입",
    "가성비": "가성비 — 가격 재검토 또는 구성 강화(쿠폰·묶음) 검토",
    "핏": "핏 — 착용컷 다양화, 체형별 핏 가이드 제공",
}


def read_s3_json(key):
    return json.loads(s3.get_object(Bucket=BUCKET, Key=key)["Body"].read().decode("utf-8"))


def extract_json(text):
    s, e = text.find("{"), text.rfind("}")
    if s == -1 or e <= s:
        raise json.JSONDecodeError("JSON 객체 없음", text, 0)
    return json.loads(text[s:e + 1])


def nvidia(prompt, retries=3):
    errors = []
    for model in NVIDIA_MODELS:
        body = json.dumps({"model": model, "messages": [{"role": "user", "content": prompt}],
                           "temperature": 0.3, "max_tokens": 2048}).encode()
        for attempt in range(retries):
            try:
                req = urllib.request.Request(
                    "https://integrate.api.nvidia.com/v1/chat/completions", data=body,
                    headers={"Content-Type": "application/json",
                             "Authorization": f"Bearer {os.environ['NVIDIA_API_KEY']}"})
                with urllib.request.urlopen(req, timeout=60) as r:
                    text = json.load(r)["choices"][0]["message"]["content"]
                return extract_json(text), model.split("/")[-1]
            except urllib.error.HTTPError as e:
                errors.append(f"{model} HTTP {e.code}")
                if e.code in (429, 500, 502, 503):
                    time.sleep(5 * (attempt + 1)); continue
                break
            except (KeyError, json.JSONDecodeError, TimeoutError, OSError):
                errors.append(f"{model} 파싱/네트워크"); time.sleep(3)
    raise RuntimeError("NVIDIA 실패: " + " | ".join(errors[-3:]))


def gemini(prompt, retries=3):
    body = json.dumps({"contents": [{"parts": [{"text": prompt}]}],
                       "generationConfig": {"temperature": 0.3, "maxOutputTokens": 2048,
                                            "responseMimeType": "application/json"}}).encode()
    errors = []
    for model in GEMINI_MODELS:
        url = (f"https://generativelanguage.googleapis.com/v1beta/models/"
               f"{model}:generateContent?key={os.environ['GEMINI_API_KEY']}")
        for attempt in range(retries):
            try:
                req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
                with urllib.request.urlopen(req, timeout=60) as r:
                    text = json.load(r)["candidates"][0]["content"]["parts"][0]["text"]
                return extract_json(text), model
            except urllib.error.HTTPError as e:
                errors.append(f"{model} HTTP {e.code}")
                if e.code == 404:
                    break
                if e.code in (429, 500, 503):
                    time.sleep(10 * (attempt + 1)); continue
                break
            except (KeyError, json.JSONDecodeError, TimeoutError, OSError):
                errors.append(f"{model} 파싱/네트워크"); time.sleep(3)
    raise RuntimeError("Gemini 실패: " + " | ".join(errors[-3:]))


def call_llm(prompt):
    if os.environ.get("NVIDIA_API_KEY"):
        try:
            return nvidia(prompt)
        except RuntimeError:
            pass
    if os.environ.get("GEMINI_API_KEY"):
        return gemini(prompt)
    raise RuntimeError("LLM 키 없음")


def dummy_strategy(d):
    """LLM 실패 시 폴백 — 문장은 템플릿, 수치는 마트 실제값."""
    up = d["growth"] >= 0
    neg = d["reviewSentiment"]["negative"]
    pains = d["painPoints"][:4]
    diff = d.get("categoryDiff")
    diff_txt = "" if diff is None else f", 동일 카테고리 평균 대비 {diff:+d}%"
    title = ("급등 모멘텀 유지 전략" if up and neg < 30 else
             "급등 기회 속 품질 리스크 관리" if up else
             "품질 불만 해소로 급락 방어" if neg >= 30 else "노출·프로모션 회복으로 반등 유도")
    summary = (f"주간 매출 {d['revenue']:,}원으로 전주 대비 {d['growth']:+d}%{diff_txt}. "
               f"리뷰 부정 {neg}%, 주요 원인 {'·'.join(pains[:2]) if pains else '없음'}.")
    signals = [{"name": "판매 흐름",
                "detail": f"전주 {d['prevRevenue']:,}원 → 이번 주 {d['revenue']:,}원 ({d['growth']:+d}%)",
                "level": "높음"},
               {"name": "리뷰 감성",
                "detail": f"긍정 {d['reviewSentiment']['positive']}% / 부정 {neg}% (리뷰 {d['reviewCount']}건)",
                "level": "높음" if neg >= 50 else ("중간" if neg >= 30 else "낮음")}]
    improvements = [ASPECT_FIX.get(p, f"{p} — 불만 원인 분석 및 개선") for p in pains]
    conclusion = (f"{'·'.join(pains[:2])} 개선이 선행되어야 " + ("성장세를 지킬 수 있습니다." if up else "반등이 가능합니다.")
                  if pains else "품질 신호가 좋아 노출 확대에 집중하면 됩니다.")
    return {"title": title, "summary": summary, "signals": signals,
            "improvements": improvements, "conclusion": conclusion}


PROMPT = """당신은 홈쇼핑 커머스의 시니어 상품 전략 애널리스트입니다. 상품 "{name}"({category})의 주간 지표입니다.
(모든 수치는 데이터 파이프라인이 집계한 실제 값이며, 리뷰 원문은 제공되지 않습니다.)

[주간 판매] 매출 {revenue:,}원 (직전주 {prev:,}원, {growth:+d}%) · 판매량 {units}개 → {direction}
[카테고리 비교] 동일 카테고리({category}) 평균 주간 매출 대비 {catdiff}
[가격] 판매가 {price}원 · 카테고리 평균가 대비 {pricediff}
[재구매율] {rr_rate}%
[리뷰 감성] 긍정 {pos}% / 중립 {neu}% / 부정 {neg}%  (리뷰 {rc}건, 감성분석 정확도 {acc}%)
[주요 언급 키워드] {keywords}
[부정 리뷰 원인] {pains}

작성 지침:
- 지표를 나열하지 말고 인과·모순을 연결해 진단하세요 (급등인데 재구매율 낮으면 일시적 노출 효과 가능성,
  급락인데 긍정 높으면 품질이 아닌 노출·유입 문제로 구분).
- 모든 주장에 근거 수치를 포함하고, 제공되지 않은 수치는 지어내지 마세요.

아래 JSON 형식으로만 답하세요:
{{
  "title": "...", "summary": "...(2~3문장)",
  "signals": [{{"name":"...","detail":"...","level":"높음|중간|낮음"}}],
  "improvements": ["...(근거 수치 인용, 2~4개)"],
  "conclusion": "..."
}}"""


def lambda_handler(event, context):
    sales = read_s3_json(SALES_KEY)
    reviews = read_s3_json(REVIEWS_KEY)
    rprods = {p["productId"]: p for p in reviews["products"]}

    # 카테고리 평균 매출/가격 (근거 수치)
    cat_rev, cat_price, seen = {}, {}, set()
    for s in sales["1w"]:
        if s["revenue"] > 0:
            cat_rev.setdefault(s["category"], []).append(s["revenue"])
    for s in sales.get("1y", sales["1w"]):
        if s.get("price") and s["productId"] not in seen:
            seen.add(s["productId"]); cat_price.setdefault(s["category"], []).append(s["price"])
    cat_avg = {c: sum(v) / len(v) for c, v in cat_rev.items()}
    cat_price_avg = {c: sum(v) / len(v) for c, v in cat_price.items()}

    # 리뷰 분석 대상 상품의 주간 매출 → 상승률(양수)/하락률(음수) TOP5 (부호로 분리, 겹침 없음)
    cand = [s for s in sales["1w"] if s["productId"] in rprods and s.get("growth") is not None]
    surging = sorted([s for s in cand if s["growth"] > 0], key=lambda s: -s["growth"])[:5]
    plunging = sorted([s for s in cand if s["growth"] < 0],
                      key=lambda s: (s["growth"], -s.get("prevRevenue", 0)))[:5]
    movers = {s["productId"]: s for s in surging + plunging}
    print(f"[strategy] 후보 {len(cand)} → 상승 {len(surging)} 하락 {len(plunging)}")

    details = {}
    for pid, s in movers.items():
        r = rprods[pid]
        sent = r["reviewSentiment"]
        avg, pavg = cat_avg.get(s["category"]), cat_price_avg.get(s["category"])
        d = {"productId": pid, "productName": s["productName"], "category": s["category"],
             "revenue": s["revenue"], "prevRevenue": s.get("prevRevenue", 0),
             "unitsSold": s["unitsSold"], "growth": s["growth"],
             "categoryAvgRevenue": round(avg) if avg else None,
             "categoryDiff": round(100 * (s["revenue"] - avg) / avg) if avg else None,
             "price": s.get("price"),
             "priceDiff": round(100 * (s["price"] - pavg) / pavg) if s.get("price") and pavg else None,
             "repurchaseRate": s.get("repurchaseRate", 0), "avgRating": r.get("avgRating"),
             "reviewCount": r["reviewCount"], "reviewSentiment": sent,
             "keywords": r["keywords"], "painPoints": r["painPoints"], "aiStrategy": None}
        try:
            cd, pd = d["categoryDiff"], d["priceDiff"]
            strat, model = call_llm(PROMPT.format(
                name=s["productName"], category=s["category"], revenue=s["revenue"],
                prev=s.get("prevRevenue", 0), growth=s["growth"], units=s["unitsSold"],
                direction="급등" if s["growth"] > 0 else "급락",
                catdiff=f"{cd:+d}%" if cd is not None else "데이터 없음",
                price=f"{d['price']:,}" if d["price"] else "-",
                pricediff=f"{pd:+d}%" if pd is not None else "데이터 없음",
                rr_rate=d["repurchaseRate"], acc=reviews.get("sentimentAccuracy", "-"),
                pos=sent["positive"], neu=sent["neutral"], neg=sent["negative"],
                rc=r["reviewCount"], keywords=", ".join(r["keywords"]) or "-",
                pains=", ".join(r["painPoints"]) or "-"))
            d["aiStrategy"], d["strategyEngine"] = strat, model
        except (RuntimeError, KeyError) as e:
            d["aiStrategy"], d["strategyEngine"] = dummy_strategy(d), "template-fallback"
            print(f"[strategy] 상품{pid} LLM 실패 → 템플릿 ({e})")
        details[str(pid)] = d

    keep = ("productId", "productName", "category", "unitsSold", "revenue", "prevRevenue", "growth")
    mart = {"generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
            "anchor": sales.get("anchor"), "period": "1w", "basis": "리뷰 분석 대상 상품 기준",
            "sentimentAccuracy": reviews.get("sentimentAccuracy"),
            "reviewSentiment": reviews.get("reviewSentiment"),
            "surging": [{k: s[k] for k in keep} for s in surging],
            "plunging": [{k: s[k] for k in keep} for s in plunging],
            "details": details}
    s3.put_object(Bucket=BUCKET, Key=OUT_KEY,
                  Body=json.dumps(mart, ensure_ascii=False).encode("utf-8"),
                  ContentType="application/json")
    print(f"[strategy] → s3://{BUCKET}/{OUT_KEY} (급등 {len(surging)} 급락 {len(plunging)})")
    return {"statusCode": 200, "movers": len(movers)}
