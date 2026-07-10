"""
BGE-M3 임베딩 Lambda — 임베딩 서빙을 EC2 로컬 Ollama에서 이전 (KAN-120 작업 중 분리).

호출 경로 2가지:
  1) SDK 직접 invoke (Spring 앱 기본 경로, IAM 인증)
     요청  {"input": "텍스트" | ["텍스트", ...]}  →  응답 {"embeddings": [...], "dim": 1024, "ms": 123}
  2) Function URL POST (AWS_IAM 인증 + 헤더 x-embed-key — curl 테스트용)
     조직 SCP가 익명(NONE) URL 호출을 차단하므로 공개 URL은 불가 (2026-07-06 실측).

워밍: EventBridge가 5분마다 {"warm": true}를 직접 invoke → 콜드 로딩(~19초 실측)을 미리 치러
      사용자 요청이 모델 로딩을 기다리지 않게 한다 (Ollama keep_alive의 Lambda판).

모델은 ONNX(fp32)로 변환해 이미지에 베이크(/opt/model), 콜드스타트에 1회 로드 후 웜 환경에서 재사용.
(torch fp32는 Graviton CPU에서 쿼리 1건 27초 실측 → onnxruntime으로 전환. fp32 그대로라 정합성 유지)
dense 벡터 = CLS 풀링 + L2 정규화 (적재 파이프라인과 동일 — pgvector 저장 벡터와 정합).
배치 최대 64건.
"""
import base64
import json
import os
import time

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

MODEL_DIR = os.environ.get("MODEL_DIR", "/opt/model")
API_KEY = os.environ.get("EMBED_API_KEY", "")
MAX_TOKENS = int(os.environ.get("MAX_TOKENS", "2048"))
MAX_BATCH = int(os.environ.get("MAX_BATCH", "64"))

_t0 = time.time()
with open(f"{MODEL_DIR}/config.json") as f:
    _pad_id = json.load(f).get("pad_token_id", 1)  # XLM-R 계열 <pad> = 1
tokenizer = Tokenizer.from_file(f"{MODEL_DIR}/tokenizer.json")
tokenizer.enable_truncation(max_length=MAX_TOKENS)
tokenizer.enable_padding(pad_id=_pad_id, pad_token="<pad>")
_opts = ort.SessionOptions()
_opts.intra_op_num_threads = int(os.environ.get("ORT_THREADS", "0")) or max(1, os.cpu_count() or 1)
session = ort.InferenceSession(f"{MODEL_DIR}/model.onnx", sess_options=_opts,
                               providers=["CPUExecutionProvider"])
_input_names = {i.name for i in session.get_inputs()}
COLD_LOAD_MS = int((time.time() - _t0) * 1000)


def _embed(texts):
    encs = tokenizer.encode_batch(texts)
    feed = {"input_ids": np.array([e.ids for e in encs], dtype=np.int64),
            "attention_mask": np.array([e.attention_mask for e in encs], dtype=np.int64)}
    feed = {k: v for k, v in feed.items() if k in _input_names}
    last_hidden = session.run(None, feed)[0]      # (B, T, H)
    cls = last_hidden[:, 0]                       # BGE dense = CLS 풀링
    vecs = cls / np.linalg.norm(cls, axis=1, keepdims=True)
    return vecs.tolist()


def _resp(code, body):
    return {"statusCode": code,
            "headers": {"content-type": "application/json"},
            "body": json.dumps(body, ensure_ascii=False)}


def _validate(texts):
    """input 값 검증. 정상이면 리스트로 정규화, 아니면 에러 문자열 반환."""
    if isinstance(texts, str):
        texts = [texts]
    if not texts or not isinstance(texts, list) or not all(isinstance(t, str) and t for t in texts):
        return None, "input required: non-empty string or list of strings"
    if len(texts) > MAX_BATCH:
        return None, f"batch too large (max {MAX_BATCH})"
    return texts, None


def _run(texts):
    t = time.time()
    vecs = _embed(texts)
    return {"embeddings": vecs, "dim": len(vecs[0]), "ms": int((time.time() - t) * 1000)}


def lambda_handler(event, context):
    # ── 직접 invoke 경로 (EventBridge 워밍 / Spring SDK 호출) — IAM이 이미 인증 ──
    if event.get("warm"):
        return {"warm": True, "coldLoadMs": COLD_LOAD_MS}
    if "input" in event and "requestContext" not in event:
        texts, err = _validate(event["input"])
        if err:
            return {"error": err}
        return _run(texts)

    # ── Function URL(HTTP) 경로 — AWS_IAM 인증 + x-embed-key 이중 확인 ──
    body = event.get("body") or "{}"
    if event.get("isBase64Encoded"):
        body = base64.b64decode(body).decode("utf-8")
    try:
        payload = json.loads(body)
    except json.JSONDecodeError:
        return _resp(400, {"error": "invalid json"})

    if payload.get("warm"):  # URL 경유 워밍/헬스체크용
        return _resp(200, {"warm": True, "coldLoadMs": COLD_LOAD_MS})

    headers = {k.lower(): v for k, v in (event.get("headers") or {}).items()}
    if not API_KEY or headers.get("x-embed-key") != API_KEY:
        return _resp(403, {"error": "forbidden"})

    texts, err = _validate(payload.get("input"))
    if err:
        return _resp(400, {"error": err})
    return _resp(200, _run(texts))
