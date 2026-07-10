# EC2 팀 공용 Elasticsearch 배포 가이드

로컬마다 ES를 따로 띄우면 색인이 사람별로 어긋난다(예: "슬랙스+하의" 검색이 누구는 되고 누구는 안 됨).
EC2 한 대에 **ES(+Nori) + Kibana** 를 올려 팀이 같은 색인을 공유한다. 앱은 코드 변경 없이
`ES_URIS` 환경변수만 이 서버로 바꾸면 된다.

> 대상: 팀 내부 공용(개발/스테이징). 외부 공개용이 아니므로 보안그룹으로 접근을 반드시 제한한다.
> CDC(Debezium)는 사용하지 않기로 함 → DB를 바꾸면 **수동 재색인**이 필요하다(아래 5단계).

---

## 1. EC2 인스턴스

- OS: Amazon Linux 2023 또는 Ubuntu 22.04
- 크기: 최소 **t3.small(2GB)**, 권장 **t3.medium(4GB 이상)**
  - ES 힙은 RAM의 약 50%로. 4GB면 `ES_HEAP=2g`, 2GB면 기본 `1g`.
- 디스크: gp3 20GB 이상(색인 데이터 + 여유)

## 2. 보안그룹 (가장 중요)

인바운드를 **팀 IP / 앱 서버 보안그룹**으로만 제한한다. `0.0.0.0/0` 절대 금지.

| 포트 | 용도 | 허용 대상 |
|------|------|-----------|
| 22   | SSH  | 팀 관리자 IP |
| 9200 | ES   | 앱 서버 SG + 팀 개발자 IP |
| 5601 | Kibana | 팀 개발자 IP |

## 3. 서버 사전 설정

```bash
# Docker + compose 플러그인 설치 (Amazon Linux 2023 기준)
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user           # 재로그인 후 sudo 없이 docker 사용

DOCKER_CONFIG=${DOCKER_CONFIG:-$HOME/.docker}
mkdir -p $DOCKER_CONFIG/cli-plugins
curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o $DOCKER_CONFIG/cli-plugins/docker-compose
chmod +x $DOCKER_CONFIG/cli-plugins/docker-compose

# ES 필수 커널 설정 (Linux). 안 하면 부팅 시 컨테이너가 죽는다.
sudo sysctl -w vm.max_map_count=262144
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-elasticsearch.conf
```

## 4. 배포

```bash
# 저장소를 올리거나, 최소한 docker-compose.yml 과 docker/elasticsearch/ 를 복사
git clone <repo-url> hinsight && cd hinsight

# (선택) 인스턴스 RAM에 맞춰 힙 지정
export ES_HEAP=2g

# 프로파일 없이 up → ES + Kibana 만 기동 (검색 서버).
# 로컬 개발 풀스택(MySQL/Kafka 등)은 'docker compose --profile local up' 로 띄운다.
docker compose up -d --build

# 상태 확인
curl -s localhost:9200/_cluster/health | jq .
```

## 5. 인덱스 초기화 & 색인

앱이 이 ES를 바라보도록 띄운 뒤, 관리자 엔드포인트로 초기화한다.

```bash
curl -X POST http://<app-host>:8080/api/es/init          # 인덱스 + 동의어 세트 생성
curl -X POST http://<app-host>:8080/api/es/reindex        # DB 전체 → ES 재색인
```

> Debezium을 쓰지 않으므로, **DB의 상품/카테고리를 바꾼 뒤에는 `/api/es/reindex` 를 다시 호출**해야
> 검색 결과가 최신 상태와 일치한다.

## 6. 앱 연결 (코드 변경 없음)

앱은 `spring.elasticsearch.uris` = `${ES_URIS:http://localhost:9200}` 를 사용한다.
배포 환경의 `.env`/환경변수만 바꾸면 된다.

```bash
# 앱 서버 .env
ES_URIS=http://<ec2-private-ip>:9200      # 같은 VPC면 프라이빗 IP 권장
```

---

## 운영 메모

- `restart: unless-stopped` 라 EC2 재부팅 후 자동 기동된다.
- 데이터는 `es-data` 볼륨에 보존된다. **스냅샷/백업 정책**은 별도로 마련할 것(팀 공용이라 유실 시 전체 재색인 필요).
- 나중에 외부 공개가 필요해지면: `xpack.security.enabled=true` + TLS + 9200 직접 노출 차단(앱만 접근) 으로 하드닝.
