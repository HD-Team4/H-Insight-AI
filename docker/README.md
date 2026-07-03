# Elasticsearch (로컬 개발 환경)

한글 검색/동의어 기능을 위한 Elasticsearch + Nori(한글 형태소 분석기) + Kibana 환경입니다.

## 사전 준비: Docker Desktop 설치

Windows 11 기준:

1. https://www.docker.com/products/docker-desktop/ 에서 Docker Desktop for Windows 다운로드
2. 설치 시 **WSL2 기반**으로 설치 (설치 마법사 기본값)
3. 설치 후 재부팅 → Docker Desktop 실행 → 고래 아이콘이 초록불이면 준비 완료
4. 확인:
   ```bash
   docker --version
   docker compose version
   ```
## Chan
1. docker compose --profile local build
2. docker compose --profile local up -d

## 실행

프로젝트 루트에서:

```bash
# 최초 1회: Nori 플러그인 포함 이미지 빌드 + 컨테이너 기동
docker compose up -d --build

# 이후에는 build 없이
docker compose up -d
```

- 첫 빌드는 이미지 다운로드 + Nori 설치로 몇 분 걸립니다.
- `-d`는 백그라운드 실행. 로그를 보려면 `docker compose logs -f elasticsearch`

## 상태 확인

```bash
# 컨테이너 상태 (elasticsearch가 healthy 여야 함)
docker compose ps

# ES 응답 확인
curl http://localhost:9200

# 클러스터 상태 (status가 green 또는 yellow면 정상)
curl http://localhost:9200/_cluster/health?pretty

# Nori 플러그인 설치 확인 (analysis-nori 가 목록에 있어야 함)
curl http://localhost:9200/_cat/plugins
```

Kibana는 http://localhost:5601 (Dev Tools 콘솔에서 쿼리 테스트 편함)

## Nori 동작 테스트

한글이 형태소 단위로 쪼개지는지 확인:

```bash
curl -X POST "http://localhost:9200/_analyze?pretty" -H "Content-Type: application/json" -d "{\"analyzer\":\"nori\",\"text\":\"청바지를 검색합니다\"}"
```

`청바지`, `검색` 등이 토큰으로 분리되면 정상입니다.

## 중지 / 정리

```bash
docker compose stop          # 중지 (데이터 유지)
docker compose down          # 컨테이너 삭제 (데이터 볼륨은 유지)
docker compose down -v       # 데이터 볼륨까지 완전 삭제
```

## 접속 정보

| 항목 | 값 |
|------|-----|
| Elasticsearch | http://localhost:9200 |
| Kibana        | http://localhost:5601 |
| 보안(TLS)     | 로컬 개발용으로 비활성화 (`xpack.security.enabled=false`) |

---

# Ollama (임베딩 서버 · 상품추천 챗봇)

챗봇이 사용자 질문을 **BGE-M3(1024차원)** 로 임베딩하기 위한 로컬 임베딩 서버입니다.
pgvector에 적재된 상품 벡터와 **동일 모델**이라 정합성 검증을 마쳤습니다(코사인 0.99999).

## 실행

```bash
# 다른 서비스와 함께 기동 (bge-m3 자동 pull 포함)
docker compose up -d

# ollama 만 기동
docker compose up -d ollama ollama-setup
```

- 최초 1회 `ollama-setup` 컨테이너가 **bge-m3(약 1.2GB)** 를 자동 다운로드합니다.
  볼륨 `ollama-data`에 저장되어 다음부터는 재다운로드가 없습니다.
- 다운로드 진행 로그: `docker compose logs -f ollama-setup`

## 확인

```bash
# 모델 목록에 bge-m3 가 보이면 준비 완료
curl http://localhost:11434/api/tags

# 임베딩 테스트 (1024차원 벡터 반환)
curl http://localhost:11434/api/embed -d '{"model":"bge-m3","input":"테스트"}'
```

## 접속 정보

| 항목 | 값 |
|------|-----|
| Ollama API | http://localhost:11434 |
| 모델 | bge-m3 (1024d, 임베딩 전용) |

> **맥 + Docker 참고:** Docker 컨테이너는 Apple Metal GPU를 사용하지 못해 맥에서는 CPU로 동작합니다
> (임베딩은 가벼워 체감 부담은 거의 없음). NVIDIA GPU 서버라면 compose 의 `ollama` 서비스에 GPU 예약을 추가하세요.
> 맥에서 굳이 GPU 가속이 필요하면 네이티브 설치(`brew install ollama` + `ollama pull bge-m3`)를 쓰고
> Docker `ollama` 는 끄면 됩니다(둘 다 11434 포트라 하나만 띄워야 함).
