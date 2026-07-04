# KAN-79 리뷰 분석 Lambda (arm64/Graviton). M시리즈 맥에서 네이티브 빌드.
# x86_64로 바꾸려면: build 시 --platform linux/amd64,
#   requirements의 torch를 CPU 인덱스(https://download.pytorch.org/whl/cpu)로.
FROM public.ecr.aws/lambda/python:3.12

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

ENV MODEL_DIR=/opt/model
# 런타임 HF 캐시는 쓰기 가능한 /tmp로 (Lambda는 /tmp만 쓰기 가능)
ENV HF_HOME=/tmp/hf
ENV TRANSFORMERS_CACHE=/tmp/hf

# 모델 repo 전체를 "실제 파일"로 이미지에 굽는다 (심볼릭 링크 X → 런타임에 안 깨짐).
# save_pretrained가 가중치를 누락하던 문제를 회피. 빌드 로그에서 model.safetensors 확인 가능.
RUN python -c "from huggingface_hub import snapshot_download; \
snapshot_download('Copycats/koelectra-base-v3-generalized-sentiment-analysis', \
local_dir='/opt/model', local_dir_use_symlinks=False)" \
    && echo '--- /opt/model ---' && ls -la /opt/model

# 다운로드 이후: 런타임엔 네트워크 접근 끔 (Lambda는 인터넷 없음 → 로컬 파일만 사용)
ENV HF_HUB_OFFLINE=1
ENV TRANSFORMERS_OFFLINE=1

COPY handler.py ${LAMBDA_TASK_ROOT}/
CMD ["handler.lambda_handler"]
