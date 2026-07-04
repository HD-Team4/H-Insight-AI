#!/usr/bin/env python3
"""
KAN-97 STEP2: 상품 분석 마트 (mart/products/latest.json) 생성기 — LLM은 여기서만.

입력 = 두 마트만 (RDS·raw 미접근):
  ① sales.json    전체 상품별 매출·성장률 (집계 Lambda 산출, 로컬은 dummy 백필)
  ② reviews.json  리뷰 분석 마트 — 긍부정%·키워드·painPoints (리뷰 Lambda 산출)

→ 리뷰 분석 대상 상품 중 주간(1w) 급등/급락 TOP5 선별
→ 해당 상품들만 Gemini 호출(집계 요약만 전달, 리뷰 원문 X → 토큰 최소·429 회피)
→ 상품 분석 화면 계약 JSON(products.json = 미래 mart/products/latest.json) 생성.

사용:
  python3 docs/aggregation/build_products_mart.py            # 급등/급락 10개 전략 생성
  python3 docs/aggregation/build_products_mart.py --no-llm   # 전략 없이 구조만(개발용)
"""
import argparse
import datetime as dt
import json
import time
import urllib.request
import urllib.error

ENV = {}
for line in open(".env"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        ENV[k] = v

# lite 우선: 무료티어 쿼터 버킷이 모델별 분리 — flash 일일쿼터 소진 시에도 lite는 살아있음
GEMINI_MODELS = ["gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.0-flash"]
# NVIDIA NIM 무료 엔드포인트 (OpenAI 호환). 쿼터가 Gemini와 완전 별개.
# 3.3-70b는 무료 엔드포인트 혼잡으로 행업 잦음 → maverick(다국어)·3.1-70b 사용
NVIDIA_MODELS = ["meta/llama-4-maverick-17b-128e-instruct", "meta/llama-3.1-70b-instruct"]


def extract_json(text):
    """모델이 ```json 펜스로 감싸도 파싱되게 첫 '{' ~ 마지막 '}' 만 추출."""
    s, e = text.find("{"), text.rfind("}")
    if s == -1 or e <= s:
        raise json.JSONDecodeError("JSON 객체 없음", text, 0)
    return json.loads(text[s:e + 1])


def nvidia(prompt, retries=3):
    errors = []
    for model in NVIDIA_MODELS:
        body = json.dumps({
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.3, "max_tokens": 2048,
        }).encode()
        for attempt in range(retries):
            try:
                req = urllib.request.Request(
                    "https://integrate.api.nvidia.com/v1/chat/completions", data=body,
                    headers={"Content-Type": "application/json",
                             "Authorization": f"Bearer {ENV['NVIDIA_API_KEY']}"})
                with urllib.request.urlopen(req, timeout=120) as r:
                    text = json.load(r)["choices"][0]["message"]["content"]
                return extract_json(text), model.split("/")[-1]
            except urllib.error.HTTPError as e:
                errors.append(f"{model} HTTP {e.code}")
                if e.code in (429, 500, 502, 503):
                    time.sleep(10 * (attempt + 1))
                    continue
                break                      # 401/403/404 → 다음 모델
            except (KeyError, json.JSONDecodeError, TimeoutError, OSError):
                errors.append(f"{model} 파싱/네트워크 실패")
                time.sleep(5)
    raise RuntimeError("NVIDIA 실패: " + " | ".join(errors[-3:]))


def call_llm(prompt):
    """NVIDIA(무료, 쿼터 별개) 우선 → 실패 시 Gemini 폴백."""
    if ENV.get("NVIDIA_API_KEY"):
        try:
            return nvidia(prompt)
        except RuntimeError as e:
            print(f"    ⚠️ {e} → Gemini 폴백", flush=True)
    return gemini(prompt)
DUMMY = "src/main/resources/dummy"


def gemini(prompt, retries=4):
    body = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.3, "maxOutputTokens": 2048,
                             "responseMimeType": "application/json"},
    }).encode()
    errors = []
    for model in GEMINI_MODELS:
        url = (f"https://generativelanguage.googleapis.com/v1beta/models/"
               f"{model}:generateContent?key={ENV['GEMINI_API_KEY']}")
        for attempt in range(retries):
            try:
                req = urllib.request.Request(url, data=body,
                                             headers={"Content-Type": "application/json"})
                with urllib.request.urlopen(req, timeout=90) as r:
                    text = json.load(r)["candidates"][0]["content"]["parts"][0]["text"]
                return json.loads(text), model
            except urllib.error.HTTPError as e:
                errors.append(f"{model} HTTP {e.code}")
                if e.code == 404:
                    break
                if e.code in (429, 500, 503):
                    wait = 20 * (attempt + 1)
                    print(f"    ⏳ {model} HTTP {e.code} — {wait}s 대기", flush=True)
                    time.sleep(wait)
                    continue
                raise
            except (KeyError, json.JSONDecodeError, TimeoutError, OSError):
                errors.append(f"{model} 파싱/네트워크 실패")
                time.sleep(5)
    raise RuntimeError("Gemini 실패: " + " | ".join(errors[-3:]))


PROMPT = """당신은 홈쇼핑 커머스의 시니어 상품 전략 애널리스트입니다. 상품 "{name}"({category})의 주간 지표입니다.
(모든 수치는 데이터 파이프라인이 집계한 실제 값이며, 리뷰 원문은 제공되지 않습니다.)

[주간 판매] 매출 {revenue:,}원 (직전주 {prev:,}원, {growth:+d}%) · 판매량 {units}개 → {direction}
[카테고리 비교] 동일 카테고리({category}) 평균 주간 매출 대비 {catdiff}
[가격] 판매가 {price}원 · 카테고리 평균가 대비 {pricediff}
[평점·재구매] 평균 평점 {rating}점 / 재구매율 {rr_rate}%
[리뷰 감성] 긍정 {pos}% / 중립 {neu}% / 부정 {neg}%  (리뷰 {rc}건, 감성분석 정확도 96.5%)
[주요 언급 키워드] {keywords}
[부정 리뷰 원인] {pains}

작성 지침 (중요):
- 지표를 개별 나열하지 말고 지표들 사이의 인과·모순을 연결해 "왜 이런 판매 흐름이 나왔고,
  이대로면 다음에 무슨 일이 일어날지"를 진단하세요.
  (예: 급등인데 재구매율이 낮으면 일시적 노출 효과 가능성과 그 리스크,
   급락인데 긍정 비율이 높으면 품질 문제가 아니라 노출·유입 문제로 구분)
- signals의 detail은 수치 반복이 아니라 "그 수치가 의미하는 것"을 근거 수치와 함께 한 문장으로.
- improvements는 부정 리뷰 원인·가격·평점 수치를 인용해 우선순위가 높은 것부터 2~4개.
- 실무 보고서처럼 간결하게, 모든 주장에 근거 수치를 포함하세요. 제공되지 않은 수치를 지어내지 마세요.

아래 JSON 형식으로만 답하세요:
{{
  "title": "...",                     // 이 상품 상황에 맞는 전략 한 줄 제목
  "summary": "...",                   // 신호를 인과로 연결한 종합 진단 2~3문장
  "signals": [                        // 핵심 신호 3~5개
    {{"name": "...", "detail": "...", "level": "높음"|"중간"|"낮음"}}
  ],
  "improvements": ["...", "..."],     // 우선순위 순 구체 개선 액션 2~4개 (근거 수치 인용)
  "conclusion": "..."                 // 결론 1문장
}}"""


# 측면별 개선 액션 템플릿 (--dummy 전략용. 수치·원인은 마트의 실제 값)
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


def dummy_strategy(d):
    """발표용 템플릿 전략 — 문장은 템플릿, 수치·원인은 전부 마트의 실제 계산값.
    이후 Gemini 배치(KAN-97)가 같은 필드를 실제 LLM 생성으로 교체한다."""
    up = d["growth"] >= 0
    neg = d["reviewSentiment"]["negative"]
    pains = d["painPoints"][:4]
    diff = d.get("categoryDiff")
    diff_txt = "" if diff is None else f", 동일 카테고리 평균 대비 {diff:+d}%"

    rating = d.get("avgRating")
    rr = d.get("repurchaseRate", 0)
    pdiff = d.get("priceDiff")

    if up:
        title = "급등 모멘텀 유지 전략" if neg < 30 else "급등 기회 속 품질 리스크 관리"
    else:
        title = "품질 불만 해소로 급락 방어" if neg >= 30 else "노출·프로모션 회복으로 반등 유도"
    summary = (f"주간 매출 {d['revenue']:,}원으로 전주 대비 {d['growth']:+d}%{diff_txt}예요. "
               f"리뷰 부정 비율은 {neg}%(평균 평점 {rating}점)이고, "
               f"주요 원인은 {'·'.join(pains[:2]) if pains else '없음'}이라 "
               f"{'이 부분 수정이 필요해 보여요.' if pains else '품질 신호는 안정적이에요.'}")

    signals = [
        {"name": "판매 흐름",
         "detail": f"전주 {d['prevRevenue']:,}원 → 이번 주 {d['revenue']:,}원 ({d['growth']:+d}%)",
         "level": "높음"},
    ]
    if diff is not None:
        signals.append({"name": "카테고리 대비",
                        "detail": f"동일 카테고리({d['category']}) 평균 주간 매출 대비 {diff:+d}%",
                        "level": "높음" if diff <= -50 else ("중간" if diff < 0 else "낮음")})
    signals.append(
        {"name": "리뷰 감성",
         "detail": f"긍정 {d['reviewSentiment']['positive']}% / 부정 {neg}% (리뷰 {d['reviewCount']}건)",
         "level": "높음" if neg >= 50 else ("중간" if neg >= 30 else "낮음")})
    signals.append(
        {"name": "평점·재구매",
         "detail": f"평균 평점 {rating}점 · 재구매율 {rr}%",
         "level": "높음" if (rating or 5) < 3.0 else ("중간" if (rating or 5) < 4.0 else "낮음")})
    if pdiff is not None:
        signals.append(
            {"name": "가격 포지션",
             "detail": f"판매가 {d['price']:,}원 — 카테고리 평균가 대비 {pdiff:+d}%",
             "level": "높음" if ("가성비" in pains and pdiff > 0) else ("중간" if abs(pdiff) >= 20 else "낮음")})

    # 가성비 불만은 가격 근거 수치로 구체화, 나머지는 측면별 템플릿
    improvements = []
    for p in pains:
        if p == "가성비" and pdiff is not None and pdiff > 0:
            improvements.append(f"가성비 — 판매가가 카테고리 평균 대비 {pdiff:+d}% 높음, 가격/프로모션 재검토")
        else:
            improvements.append(ASPECT_FIX.get(p, f"{p} — 불만 원인 분석 및 개선"))
    if not up and neg < 30:
        improvements.append("노출 — 품질 신호는 양호(긍정 "
                            f"{d['reviewSentiment']['positive']}%)하므로 검색·기획전 노출 확대 우선")

    conclusion = ((f"{'·'.join(pains[:2])} 개선이 선행되어야 "
                   + ("성장세를 지킬 수 있어요." if up else "반등이 가능해요."))
                  if pains else "현재 품질 신호가 좋아 노출 확대에 집중하면 돼요.")
    return {"title": title, "summary": summary, "signals": signals,
            "improvements": improvements, "conclusion": conclusion}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sales", default=f"{DUMMY}/sales.json")
    ap.add_argument("--reviews", default=f"{DUMMY}/reviews.json")
    ap.add_argument("--out", default=f"{DUMMY}/products.json")
    ap.add_argument("--no-llm", action="store_true")
    ap.add_argument("--dummy", action="store_true", help="LLM 대신 템플릿 전략(발표용 더미)")
    ap.add_argument("--sleep", type=float, default=3.0)
    args = ap.parse_args()

    sales = json.load(open(args.sales, encoding="utf-8"))
    reviews = json.load(open(args.reviews, encoding="utf-8"))
    rprods = {p["productId"]: p for p in reviews["products"]}

    # 기존 산출물 (LLM 실패 시 이전 전략 유지용)
    try:
        existing = json.load(open(args.out, encoding="utf-8")).get("details", {})
    except (OSError, json.JSONDecodeError):
        existing = {}

    # 카테고리 평균 주간 매출 (매출 있는 상품 기준) → "카테고리 대비" 근거 수치
    cat_rev = {}
    for s in sales["1w"]:
        if s["revenue"] > 0:
            cat_rev.setdefault(s["category"], []).append(s["revenue"])
    cat_avg = {c: sum(v) / len(v) for c, v in cat_rev.items()}

    # 카테고리 평균 판매가 → "가성비 불만"의 근거 수치 (가격 경쟁력)
    cat_price = {}
    seen = set()
    for s in sales.get("1y", sales["1w"]):
        if s.get("price") and s["productId"] not in seen:
            seen.add(s["productId"])
            cat_price.setdefault(s["category"], []).append(s["price"])
    cat_price_avg = {c: sum(v) / len(v) for c, v in cat_price.items()}

    # 리뷰 분석 대상 상품의 주간 매출 (급등/급락 후보)
    weekly = [s for s in sales["1w"] if s["productId"] in rprods]
    ranked = [s for s in weekly if s.get("growth") is not None]
    surging = sorted(ranked, key=lambda s: -s["growth"])[:5]
    plunging = sorted(ranked, key=lambda s: (s["growth"], -s.get("prevRevenue", 0)))[:5]
    movers = {s["productId"]: s for s in surging + plunging}
    print(f"[1/3] 급등/급락 선별 — 후보 {len(ranked)}개 중")
    print("  급등:", [(s['productId'], f"+{s['growth']}%") for s in surging])
    print("  급락:", [(s['productId'], f"{s['growth']}%") for s in plunging])

    mode = "더미(템플릿)" if args.dummy else ("생략" if args.no_llm else f"Gemini {len(movers)}콜, {args.sleep}s 간격")
    print(f"[2/3] 전략 생성 — {mode}")
    details = {}
    for i, (pid, s) in enumerate(movers.items()):
        r = rprods[pid]
        sent = r["reviewSentiment"]
        avg = cat_avg.get(s["category"])
        pavg = cat_price_avg.get(s["category"])
        detail = {
            "productId": pid, "productName": s["productName"], "category": s["category"],
            "revenue": s["revenue"], "prevRevenue": s.get("prevRevenue", 0),
            "unitsSold": s["unitsSold"], "growth": s["growth"],
            "categoryAvgRevenue": round(avg) if avg else None,
            "categoryDiff": round(100 * (s["revenue"] - avg) / avg) if avg else None,
            "price": s.get("price"),
            "priceDiff": (round(100 * (s["price"] - pavg) / pavg)
                          if s.get("price") and pavg else None),
            "repurchaseRate": s.get("repurchaseRate", 0),
            "avgRating": r.get("avgRating"),
            "reviewCount": r["reviewCount"], "reviewSentiment": sent,
            "keywords": r["keywords"], "painPoints": r["painPoints"],
            "aiStrategy": None,
        }
        if args.dummy:
            detail["aiStrategy"] = dummy_strategy(detail)
            detail["strategyEngine"] = "template"
            print(f"  [{i+1}/{len(movers)}] 상품{pid} {s['growth']:+d}% / 카테고리 대비 "
                  f"{detail['categoryDiff']:+d}% → 「{detail['aiStrategy']['title']}」")
        elif not args.no_llm:
            cd, pd = detail["categoryDiff"], detail["priceDiff"]
            try:
                strat, model = call_llm(PROMPT.format(
                    name=s["productName"], category=s["category"],
                    revenue=s["revenue"], prev=s.get("prevRevenue", 0), growth=s["growth"],
                    units=s["unitsSold"], direction="급등" if s["growth"] > 0 else "급락",
                    catdiff=(f"{cd:+d}%" if cd is not None else "비교 데이터 없음"),
                    price=(f"{detail['price']:,}" if detail["price"] else "-"),
                    pricediff=(f"{pd:+d}%" if pd is not None else "비교 데이터 없음"),
                    rating=detail["avgRating"], rr_rate=detail["repurchaseRate"],
                    pos=sent["positive"], neu=sent["neutral"], neg=sent["negative"],
                    rc=r["reviewCount"],
                    keywords=", ".join(r["keywords"]) or "-",
                    pains=", ".join(r["painPoints"]) or "-"))
                detail["aiStrategy"] = strat
                detail["strategyEngine"] = model
                print(f"  [{i+1}/{len(movers)}] 상품{pid} {s['growth']:+d}% → 「{strat.get('title','')[:30]}」 ({model})", flush=True)
            except RuntimeError as e:
                # LLM 실패 → 기존 전략(템플릿) 유지, 빈 화면 방지
                prev_st = (existing.get(str(pid)) or {}).get("aiStrategy")
                detail["aiStrategy"] = prev_st or dummy_strategy(detail)
                detail["strategyEngine"] = "template-fallback"
                print(f"  [{i+1}/{len(movers)}] 상품{pid} ⚠️ LLM 실패 → 기존 전략 유지 ({e})", flush=True)
            if i < len(movers) - 1:
                time.sleep(args.sleep)
        details[str(pid)] = detail

    print("[3/3] 상품 분석 마트 조립")
    mart = {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "anchor": sales.get("anchor"),
        "period": "1w",
        "basis": "리뷰 분석 대상 상품 기준",
        "sentimentAccuracy": reviews["sentimentAccuracy"],
        "reviewSentiment": reviews["reviewSentiment"],
        "surging": [{k: s[k] for k in ("productId", "productName", "category",
                                       "unitsSold", "revenue", "prevRevenue", "growth")} for s in surging],
        "plunging": [{k: s[k] for k in ("productId", "productName", "category",
                                        "unitsSold", "revenue", "prevRevenue", "growth")} for s in plunging],
        "details": details,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(mart, f, ensure_ascii=False, indent=2)
    print(f"  → {args.out} (급등 {len(surging)} · 급락 {len(plunging)} · 전략 "
          f"{sum(1 for d in details.values() if d['aiStrategy'])}건)")


if __name__ == "__main__":
    main()
