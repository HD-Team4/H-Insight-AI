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
