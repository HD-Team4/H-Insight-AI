"""BGE-M3 임베딩 서빙 비교 벤치마크 — Ollama(EC2/로컬 상주) vs AWS Lambda(hf4-embedding).

무엇을 재나
  1) 레이턴시: 단건 쿼리 n회(워밍업 제외) → mean/p50/p95/min/max. 첫 워밍업 콜은 "콜드(모델 로딩)"로 별도 기록.
  2) 메모리:
     - Ollama = 호스트(EC2)가 직접 부담하는 상주 메모리. docker stats(컨테이너) + /api/ps(로딩된 모델 크기).
     - Lambda = 호스트 부담 0. Lambda 쪽 사용량은 CloudWatch REPORT(Max Memory Used)에서 수집.

사용법
  python3 -m venv .venv && .venv/bin/pip install boto3     # 최초 1회
  .venv/bin/python benchmark/embedding_benchmark.py --mode both --n 20

  Ollama 서버가 없으면(로컬에서 컨테이너 제거된 상태):
  docker run -d --rm --name hinsight-ollama-bench -p 11434:11434 \
      -v h-insight-ai_ollama-data:/root/.ollama ollama/ollama
  (기존 볼륨에 bge-m3가 있으면 재다운로드 없음. 측정 후 docker stop hinsight-ollama-bench)

  Lambda 모드는 AWS 자격증명 필요(기본 체인: ~/.aws/credentials 또는 환경변수).
"""
import argparse
import json
import statistics
import subprocess
import time
import urllib.request

QUERIES = [
    "엄마 사줄 5만원대 원피스 추천해줘",
    "여름에 시원하게 입을 남자 반팔 셔츠",
    "출근할 때 입기 좋은 슬랙스 있어?",
    "10만원 이하 가벼운 바람막이",
    "결혼식 하객룩으로 입을 블라우스",
]


def percentile(values, p):
    vs = sorted(values)
    idx = (len(vs) - 1) * p / 100
    lo, hi = int(idx), min(int(idx) + 1, len(vs) - 1)
    return vs[lo] + (vs[hi] - vs[lo]) * (idx - lo)


def stats_row(name, ms_list, cold_ms):
    return {
        "serving": name,
        "cold_first_call_ms": round(cold_ms),
        "mean_ms": round(statistics.mean(ms_list), 1),
        "p50_ms": round(percentile(ms_list, 50), 1),
        "p95_ms": round(percentile(ms_list, 95), 1),
        "min_ms": round(min(ms_list), 1),
        "max_ms": round(max(ms_list), 1),
        "n": len(ms_list),
    }


# ── Ollama ──────────────────────────────────────────────────────────────────

def ollama_embed(base_url, text, timeout=180):
    body = json.dumps({"model": "bge-m3", "input": text, "keep_alive": "30m"}).encode()
    req = urllib.request.Request(f"{base_url}/api/embed", data=body,
                                 headers={"content-type": "application/json"})
    t0 = time.perf_counter()
    resp = json.loads(urllib.request.urlopen(req, timeout=timeout).read())
    ms = (time.perf_counter() - t0) * 1000
    assert resp["embeddings"] and len(resp["embeddings"][0]) == 1024
    return ms


def ollama_memory(base_url, container):
    mem = {}
    try:  # 로딩된 모델이 점유한 크기 (Ollama 자체 보고)
        ps = json.loads(urllib.request.urlopen(f"{base_url}/api/ps", timeout=5).read())
        mem["ollama_loaded_models"] = [
            {"name": m["name"], "size_mb": round(m["size"] / 1e6)} for m in ps.get("models", [])
        ]
    except Exception as e:
        mem["ollama_loaded_models"] = f"조회 실패: {e}"
    try:  # 컨테이너 실측 RSS (호스트가 부담하는 메모리)
        out = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", container],
            capture_output=True, text=True, timeout=20)
        mem["container_mem_usage"] = out.stdout.strip() or out.stderr.strip()
    except Exception as e:
        mem["container_mem_usage"] = f"조회 실패: {e}"
    return mem


# ── Lambda ──────────────────────────────────────────────────────────────────

def lambda_client(region):
    import boto3
    return boto3.client("lambda", region_name=region)


def lambda_embed(client, function_name, text):
    t0 = time.perf_counter()
    resp = client.invoke(FunctionName=function_name,
                         Payload=json.dumps({"input": text}).encode())
    payload = json.loads(resp["Payload"].read())
    ms = (time.perf_counter() - t0) * 1000
    assert "embeddings" in payload, f"Lambda 오류 응답: {payload}"
    return ms, payload.get("ms")  # (왕복 전체, 서버측 순수 추론)


def lambda_memory(function_name, region, since_minutes=15):
    """CloudWatch 최근 REPORT 라인에서 Max Memory Used 수집."""
    import boto3
    logs = boto3.client("logs", region_name=region)
    try:
        events = logs.filter_log_events(
            logGroupName=f"/aws/lambda/{function_name}",
            startTime=int((time.time() - since_minutes * 60) * 1000),
            filterPattern="REPORT")["events"]
        if not events:
            return {"lambda_max_memory": "최근 REPORT 없음"}
        last = events[-1]["message"]
        fields = dict(p.strip().split(": ", 1) for p in last.split("\t") if ": " in p)
        return {"lambda_memory_size": fields.get("Memory Size"),
                "lambda_max_memory_used": fields.get("Max Memory Used")}
    except Exception as e:
        return {"lambda_max_memory": f"조회 실패: {e}"}


# ── main ────────────────────────────────────────────────────────────────────

def run_mode(name, embed_fn, n, warmup):
    print(f"\n=== {name} ===")
    cold = embed_fn(QUERIES[0])          # 첫 콜 = 모델 로딩 포함 가능성
    cold_ms = cold[0] if isinstance(cold, tuple) else cold
    print(f"콜드(첫 호출): {cold_ms:.0f}ms")
    for i in range(warmup - 1):
        embed_fn(QUERIES[(i + 1) % len(QUERIES)])
    lat, server_lat = [], []
    for i in range(n):
        r = embed_fn(QUERIES[i % len(QUERIES)])
        if isinstance(r, tuple):
            lat.append(r[0])
            if r[1] is not None:
                server_lat.append(r[1])
        else:
            lat.append(r)
        print(f"  {i+1:>2}/{n}: {lat[-1]:.0f}ms")
    row = stats_row(name, lat, cold_ms)
    if server_lat:
        row["server_inference_mean_ms"] = round(statistics.mean(server_lat), 1)
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["ollama", "lambda", "both"], default="both")
    ap.add_argument("--n", type=int, default=20, help="측정 횟수(워밍업 제외)")
    ap.add_argument("--warmup", type=int, default=3)
    ap.add_argument("--ollama-url", default="http://localhost:11434")
    ap.add_argument("--ollama-container", default="hinsight-ollama-bench")
    ap.add_argument("--function-name", default="hf4-embedding")
    ap.add_argument("--region", default="ap-northeast-2")
    args = ap.parse_args()

    results, memory = [], {}

    if args.mode in ("ollama", "both"):
        results.append(run_mode(
            "Ollama(호스트 상주)", lambda q: ollama_embed(args.ollama_url, q),
            args.n, args.warmup))
        memory["ollama"] = ollama_memory(args.ollama_url, args.ollama_container)

    if args.mode in ("lambda", "both"):
        client = lambda_client(args.region)
        results.append(run_mode(
            "Lambda(hf4-embedding)", lambda q: lambda_embed(client, args.function_name, q),
            args.n, args.warmup))
        time.sleep(3)  # REPORT 로그 반영 대기
        memory["lambda"] = lambda_memory(args.function_name, args.region)
        memory["lambda"]["host_resident_memory"] = "0 MB (호스트에 모델 없음)"

    print("\n" + "=" * 60)
    print("레이턴시 요약")
    for r in results:
        print(json.dumps(r, ensure_ascii=False, indent=2))
    print("\n메모리 요약")
    print(json.dumps(memory, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
