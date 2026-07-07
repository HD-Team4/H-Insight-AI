# BGE-M3 임베딩 Lambda (x86_64). M시리즈 맥에선 --platform linux/amd64 로 에뮬레이션 빌드.
# 임베딩 서빙을 EC2 로컬 Ollama → Lambda(SDK invoke)로 이전: 앱 기동 시 모델을 메모리에 올리지 않는다.
#
# 아키텍처가 x86_64인 이유(중요): onnxruntime arm64는 Lambda Firecracker에서
#   /sys/devices/system/cpu/* 를 못 읽어 cpuinfo 초기화가 실패하고 세션 생성이 크래시함(실측:
#   ORT 1.23 SystemError 13, ORT 1.19 SIGABRT). x86_64는 cpuid 명령 기반이라 문제 없음.
#
# 2-스테이지: torch fp32 추론은 Lambda CPU에서 쿼리 1건 27초로 사용 불가(실측) →
#   빌드 타임에 ONNX(fp32)로 변환하고 런타임은 onnxruntime만 싣는다.
#   fp32 그대로 변환이라 벡터 정합성 유지(양자화 아님). 최종 이미지에서 torch 제외 → 크기도 절반 이하.

# ── 스테이지 1: HF 모델 다운로드 + ONNX 변환 ──
FROM public.ecr.aws/lambda/python:3.12 AS export

# torch는 CPU 인덱스에서 먼저 설치 (PyPI 기본은 CUDA 포함 5GB+)
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir "optimum[onnxruntime]" transformers huggingface_hub

# 가중치는 pytorch_model.bin 으로 배포됨(safetensors 없음). colbert/sparse 헤드(.pt)·onnx 등
# dense 검색에 불필요한 파일은 제외.
RUN python -c "from huggingface_hub import snapshot_download; \
snapshot_download('BAAI/bge-m3', local_dir='/opt/hf', local_dir_use_symlinks=False, \
allow_patterns=['*.json','*.txt','pytorch_model.bin','*.safetensors','sentencepiece.bpe.model','tokenizer.model'])"

# feature-extraction = last_hidden_state 출력. optimum이 변환 후 torch와 수치 비교 검증까지 수행.
# chmod 필수: optimum이 가중치(model.onnx_data)를 600으로 쓰는데 Lambda는 비루트 실행이라
#   못 읽어 세션 초기화가 EACCES(SystemError 13)로 죽는다. COPY --from은 권한을 보존하므로 여기서 풀어준다.
RUN optimum-cli export onnx --model /opt/hf --task feature-extraction /opt/model \
    && chmod -R a+rX /opt/model \
    && echo '--- /opt/model ---' && ls -la /opt/model

# ── 스테이지 2: 런타임 (onnxruntime + tokenizers만) ──
FROM public.ecr.aws/lambda/python:3.12

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

ENV MODEL_DIR=/opt/model
COPY --from=export /opt/model /opt/model

# 빌드 타임 로드·추론 검증: 모델/토크나이저 누락이면 여기서 빌드가 실패한다 (런타임 장애 방지)
COPY handler.py ${LAMBDA_TASK_ROOT}
RUN cd ${LAMBDA_TASK_ROOT} && python -c "import handler; \
v = handler._embed(['빌드 검증용 문장']); \
assert len(v[0]) == 1024, f'dim={len(v[0])}'; print('onnx embed OK, dim=1024')"

CMD ["handler.lambda_handler"]
