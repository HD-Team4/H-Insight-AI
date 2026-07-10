# BGE-M3 임베딩 서빙: EC2 Ollama → AWS Lambda 이전 총정리

> 2026-07-06 ~ 07-07 작업. 상품추천 챗봇/리뷰 RAG의 쿼리 임베딩(BGE-M3, 1024d)을
> EC2 로컬 Ollama에서 AWS Lambda 컨테이너로 이전한 전 과정과 실측 비교.

## 1. 배경 — 왜 옮겼나

- 임베딩은 적재 파이프라인과 **같은 모델(BGE-M3)** 이어야 pgvector 저장 벡터와 유사도가 성립.
- 기존엔 EC2에서 Ollama 데몬(`lo calhost:11434`)이 모델을 상주(1.2GB+)시키는 구조.
  - EC2(7.6GB)는 ES/Kafka/Redis 등으로 이미 메모리 포화.
  - 콜드 로딩 27초(실측), 그리고 EC2에는 bge-m3가 pull조차 안 돼 있어 챗봇이 500을 내고 있었음.
- 목표: **앱을 실행해도 임베딩 모델이 호스트 메모리에 올라가지 않는 구조.**

## 2. 최종 아키텍처

```
[Spring App (EC2)]
  EmbeddingService ──(AWS SDK invoke, IAM 서명)──▶ [Lambda: hf4-embedding]
                                                    ├─ ECR 컨테이너 이미지 (BGE-M3 → ONNX fp32 베이크)
                                                    ├─ x86_64, 메모리 6GB(vCPU 비례), 타임아웃 300s
                                                    ├─ 콜드스타트 1회 로드 → 웜 환경 재사용
                                                    └─ {"input":[텍스트,...]} → {"embeddings":[[1024d],...]}
[EventBridge: hf4-embedding-warm] ──(5분마다 {"warm":true})──▶ 콜드스타트 상시 차단
```

- **API Gateway를 안 쓴 이유**: 소비자가 우리 Spring 앱 하나(내부 서비스간 호출)라 SDK invoke가 표준 패턴.
  API 키 발급·사용량 제한·커스텀 도메인이 필요해지면 그때 붙이면 되고, 앱은 `EmbeddingService` 한 클래스만 바뀜.
- **Function URL은 보조**: 조직 SCP가 익명(NONE) URL 호출을 차단(403)해서 AWS_IAM 모드로만 유지(curl 테스트용).

## 3. 이미지 굽는 과정 (`lambda/embedding/Dockerfile`, 2-스테이지)

**Stage 1 — export (모델 다운로드 + ONNX 변환)**
1. torch를 CPU 인덱스에서 설치(PyPI 기본은 CUDA 포함 5GB+) + optimum/transformers.
2. `BAAI/bge-m3` 를 `snapshot_download`로 베이크 — 가중치는 `pytorch_model.bin`(safetensors 없음),
   colbert/sparse 헤드 등 dense 검색에 불필요한 파일 제외.
3. `optimum-cli export onnx --task feature-extraction` → **fp32 그대로 변환**(양자화 아님 → 벡터 정합 유지).
4. `chmod -R a+rX /opt/model` ← 필수. optimum이 가중치를 600 권한으로 써서 비루트인 Lambda가 못 읽음.

**Stage 2 — runtime (실행만)**
1. `onnxruntime==1.19.2` + `tokenizers` + `numpy`만 설치(토치 제외 → 이미지 절반 이하).
2. Stage 1의 `/opt/model` COPY (권한 보존).
3. **빌드 타임 검증**: 핸들러로 실제 임베딩 1회 실행, 1024차원 확인 — 실패 시 빌드가 죽어서 런타임 장애 예방.

**핸들러(handler.py)**: dense 벡터 = CLS 풀링 + L2 정규화(적재 파이프라인과 동일).
SDK 직접 invoke / Function URL 이벤트 모두 처리, `{"warm":true}` 워밍, 배치 최대 64건.

**빌드/푸시**:
```bash
docker build --platform linux/amd64 --provenance=false --sbom=false -t <ECR>/hf4-embedding:latest .
docker push <ECR>/hf4-embedding:latest
aws lambda update-function-code --function-name hf4-embedding --image-uri <ECR>/hf4-embedding:latest
```
`--provenance=false --sbom=false`: 최신 Docker의 attestation manifest(OCI 인덱스)를 Lambda가 거부하므로 단일 매니페스트로 푸시.

## 4. AWS 리소스 설정

| 리소스 | 값 |
|---|---|
| ECR | `hf4-embedding` |
| Lambda | 컨테이너 이미지, **x86_64**, 6144MB, 300s, env `EMBED_API_KEY` |
| 실행 롤 | `hf4-reviews-analysis-role-gwfv40db` 공유 + 인라인 `hf4-embedding-logs`(로그 그룹 권한) |
| 워밍 | EventBridge `hf4-embedding-warm` — rate(5 minutes), `{"warm":true}` |
| 호출 권한 | 앱 IAM 유저 `hf4-mart-reader`에 인라인 `hf4-embedding-invoke`(해당 함수 한정 lambda:InvokeFunction) |
| Function URL | AWS_IAM 인증(익명은 조직 SCP 차단) — 테스트용 |

## 5. 트러블슈팅 연대기 (전부 실측으로 확인)

| # | 문제 | 원인 | 해결 |
|---|---|---|---|
| 1 | `create-function` 이미지 거부 | Docker attestation manifest | `--provenance=false --sbom=false` |
| 2 | Function URL 익명 403 | 조직 SCP가 익명 URL 차단 | SDK invoke(IAM)로 전환 |
| 3 | 쿼리 1건 27초 | torch fp32가 Lambda CPU에서 극도로 느림 | ONNX(fp32) + onnxruntime |
| 4 | ORT 세션 초기화 크래시 | **arm64** ORT가 Firecracker에서 /sys cpuinfo 못 읽음 | x86_64로 전환 |
| 5 | 여전히 SystemError 13 | `model.onnx_data` 권한 600 → 비루트 EACCES | 빌드 시 chmod (빌드 검증은 root라 통과했던 함정) |
| 6 | 첫 invoke 120초 타임아웃 | 배포 직후 이미지 청크(2.3GB) 지연 로딩 | 타임아웃 300s; 2회차부터 콜드 4.7초 |
| 7 | 앱에서 403 "security token invalid" | 로컬 .env에 AWS 키 없음 → placeholder 자격증명 | LambdaConfig가 기본 자격증명 체인 폴백 |
| 8 | 키 넣어도 403 AccessDenied | `hf4-mart-reader`에 invoke 권한 없음 | 함수 한정 인라인 정책 부여(전파 ~2분) |

## 6. 앱 코드 변경

| 파일 | 변경 |
|---|---|
| `ai/embedding/EmbeddingService` | Ollama HTTP → `LambdaClient.invoke()`. `embed(String)` 시그니처 유지 → 호출부 4곳 무변경 |
| `config/LambdaConfig` (신규) | LambdaClient 빈 — .env 키 있으면 static, 없으면 기본 체인 |
| `build.gradle` | `software.amazon.awssdk:lambda:2.28.0` |
| `application.yml` | `ollama:` 블록 → `embedding.lambda.function-name` |
| `docker-compose.yml` | ollama/ollama-setup 서비스·볼륨 제거 |

## 7. 검증

- **정합성 게이트**: pgvector에 저장된 document 5건을 Lambda로 재임베딩 → 저장 벡터와 코사인 **1.000000** (기준 ≥0.999, 기존 Ollama 검증치 0.99999보다 좋음 — fp32 그대로 변환한 덕).
- E2E: 로컬/EC2(ALB)에서 챗봇 ask 실측 통과.

## 8. Ollama vs Lambda 실측 비교 (`benchmark/embedding_benchmark.py`)

단건 쿼리 20회, 워밍업 3회 제외. Ollama는 로컬 Docker(bge-m3 F16), Lambda는 hf4-embedding.

| 항목 | Ollama (호스트 상주) | Lambda (hf4-embedding) |
|---|---|---|
| 콜드(모델 로딩) | **26,964ms** | 4.7s (워밍 핑이 흡수; 배포 직후 최초 1회만 ~2분) |
| 워밍 평균 | 259.9ms | **148.0ms** (서버 추론 113.5ms + 왕복 ~35ms) |
| p50 / p95 | 254 / 317ms | **140 / 214ms** |
| 호스트 상주 메모리 | **1.29GiB** (모델 1,219MB) | **0 MB** |
| 서빙 측 메모리 | 호스트 부담 | Lambda 2,849MB/6,144MB (AWS 부담, 프리티어 내 ~0원) |

**결론**: 호스트 메모리 1.3GB 해방 + 워밍 레이턴시 ~40% 개선 + 콜드 27초 문제 소멸.
EC2에 Ollama·모델 설치가 필요 없어져 "EC2에서만 챗봇 500"(bge-m3 미pull) 문제도 구조적으로 사라짐.

콜드스타트: EventBridge 스케줄러를 통해서 5분간격으로 해당 람다를 실행하도록 설정. 

재실행:
```bash
python3 -m venv .venv && .venv/bin/pip install boto3
docker run -d --rm --name hinsight-ollama-bench -p 11434:11434 \
    -v h-insight-ai_ollama-data:/root/.ollama ollama/ollama
.venv/bin/python benchmark/embedding_benchmark.py --mode both --n 20
docker stop hinsight-ollama-bench
```

## 9. 운영 노트

- 새 이미지 배포 직후 첫 호출만 ~2분(청크 캐시 워밍) — 배포 후 워밍 invoke 1회 권장.
- 장문 문서 벌크 임베딩은 배치 ≤8 권장(5건 배치 28초 실측).
- EC2의 `hinsight-ollama`/`hinsight-ollama-setup` 컨테이너·볼륨은 제거 대상.
- (별개 이슈) 챗봇 "느림/필터 안 됨"의 다른 축은 **Gemini 무료 쿼터(2.5-flash 하루 20건)** — 조건추출 4초/멘트 8초 데드라인으로 폴백은 빨라졌지만, 근본 해결은 결제 또는 모델 변경.
